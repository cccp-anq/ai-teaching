"""HTTP 层：契约 §2 的三个路由。

| 方法/路径 | 行为 |
| --- | --- |
| `POST /api/v1/jobs` | 首次 **202**、同键同内容 **200**、同键异内容 **409**，body 为完整 `JobEnvelope` |
| `GET /api/v1/jobs/{jobId}` | **200** `JobEnvelope`；不存在 → **404**（与"无权"同形，不泄漏归属） |
| `GET /api/v1/health` | **200**，只含身份/接口版本/能力边界 |

错误体统一为 `{code, message, retryable, requestId, details}`（契约 §5），message 不含堆栈、
凭证与媒体地址。`Idempotency-Key` 可选，但出现时必须等于 `body.clientRequestId`（否则 422）。

与转写模块的两处差异已显式登记（见 `docs/交接表-head-up-v0.1.md` §9）：
1. `GET /api/v1/health` **不要求 Bearer**（便于存活探测），其余路由一律要求；
2. 请求体超限返回 **413 PAYLOAD_TOO_LARGE**（真正强制执行，不是仅声明）。
"""

from __future__ import annotations

import json
import logging
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import unquote, urlparse

from .config import Config
from .service import JobService
from .wire import ApiError

LOG = logging.getLogger("headup.http")


class HeadUpApp:
    """与 HTTP 框架无关的应用层，便于测试直接调用。"""

    def __init__(self, cfg: Config, service: JobService):
        self.cfg = cfg
        self.service = service
        self._events = None
        import threading
        self._events_lock = threading.Lock()

    # -- 路由 ------------------------------------------------------------
    def handle(self, method: str, path: str, headers: dict[str, str],
               body: bytes) -> tuple[int, dict[str, Any], dict[str, str]]:
        request_id = str(uuid.uuid4())
        lowered = {key.lower(): value for key, value in headers.items()}
        authenticated = False
        try:
            route = urlparse(path).path.rstrip("/") or "/"
            if route == "/api/v1/health" or route == "/health":
                return 200, self.cfg.health(), {"Content-Type": "application/json; charset=utf-8"}

            self._authenticate(lowered)
            authenticated = True

            if route.startswith('/internal/v1/event-observations'):
                from .event_observations import EventObservations
                with self._events_lock:
                    if self._events is None:self._events=EventObservations(self.cfg)
                prefix='/internal/v1/event-observations'
                if route==prefix and method=='POST':
                    if len(body)>1500000:raise ApiError(413,'PAYLOAD_TOO_LARGE','截图请求过大')
                    try:raw=json.loads(body)
                    except (ValueError,UnicodeDecodeError):raise ApiError(400,'MALFORMED_JSON','截图请求无效')
                    if not isinstance(raw,dict):raise ApiError(422,'VALIDATION_FAILED','截图请求无效')
                    return 200,self._events.observe(raw),{'Content-Type':'application/json'}
                key=route[len(prefix)+1:]
                if method=='GET':return 200,self._events.get(key),{'Content-Type':'application/json'}
                if method=='DELETE':
                    self._events.delete(key)
                    return 204,{},{}
                raise ApiError(405,'METHOD_NOT_ALLOWED','无效方法')
            if route=='/internal/v1/jobs/resume'  and method=='POST':
                if len(body)>4096:raise ApiError(413,'PAYLOAD_TOO_LARGE','续跑请求过大')
                try:identity=json.loads(body.decode('utf-8'))
                except (ValueError,UnicodeDecodeError):raise ApiError(400,'MALFORMED_JSON','无效续跑请求')
                if not isinstance(identity,dict):raise ApiError(400,'MALFORMED_JSON','无效续跑请求')
                envelope=self.service.store.resume_unknown(identity,lowered.get('x-client-request-id'))
                return 200,envelope,{'Content-Type':'application/json; charset=utf-8'}

            if route.startswith('/internal/v1/jobs/') and method == 'DELETE':
                job_id=route[len('/internal/v1/jobs/'):]
                if not job_id or '/' in job_id:raise ApiError(404,'NOT_FOUND','任务不存在')
                row=self.service.store.delete_terminal(job_id)
                if row is None:raise ApiError(404,'NOT_FOUND','任务不存在')
                # Only task-owned descendants of configured work roots may be removed.
                from pathlib import Path
                import shutil
                from .pipeline import safe_work_key
                for dirname in ('tasks/headup','jobs'):
                    base=(Path(self.cfg.work_dir)/dirname).resolve()
                    key=job_id if dirname=='tasks/headup' else safe_work_key(row['client_request_id'])
                    target=(base/key).resolve()
                    if target != base and target.is_relative_to(base) and target.is_dir():shutil.rmtree(target)
                return 204,{},{}

            if route == "/api/v1/jobs" and method == "POST":
                if len(body) > self.cfg.max_body_bytes:
                    raise ApiError(413, "PAYLOAD_TOO_LARGE",
                                   f"请求体超过本部署上限 {self.cfg.max_body_bytes} 字节")
                try:
                    raw = json.loads(body.decode("utf-8")) if body else None
                except (UnicodeDecodeError, ValueError) as exc:
                    raise ApiError(400, "MALFORMED_JSON", "请求体不是合法 UTF-8 JSON") from exc
                envelope, status = self.service.submit(raw, lowered.get("idempotency-key"))
                return status, envelope, {"Content-Type": "application/json; charset=utf-8"}

            if route.startswith("/api/v1/jobs/") and method == "GET":
                job_id = unquote(route[len("/api/v1/jobs/"):])
                if not job_id or "/" in job_id:
                    raise ApiError(404, "NOT_FOUND", "任务不存在")
                expected_key = lowered.get("x-client-request-id")
                envelope = self.service.envelope(job_id)
                if envelope is None:
                    raise ApiError(404, "NOT_FOUND", "任务不存在")
                if expected_key is not None and expected_key != envelope["clientRequestId"]:
                    raise ApiError(404, "NOT_FOUND", "任务不存在")
                # FRAME-5/6 增量字段（契约 v0.1 未定义，主项目可忽略）：
                #   progress  内部阶段（非终态时最有用）
                #   samples   逐窗口样本（与 payload.windows 同源，便于核对落库结果）
                #   metrics   实测性能指标（FRAME-6；成本只在配置了单价时给出）
                envelope = dict(envelope)
                stage = self.service.stage(job_id) or {}
                envelope["progress"] = {**stage, "moduleStatus": envelope.get("status")}
                if envelope.get("status") == "SUCCEEDED":
                    envelope["samples"] = self.service.samples(job_id)
                # FRAME-6：实测性能指标（分阶段耗时/调用数/token/字节数/成本）；
                # 失败任务同样返回——"在哪个阶段花了多久才失败"同样有用。
                metrics = self.service.metrics(job_id)
                if metrics is not None:
                    envelope["metrics"] = metrics
                return 200, envelope, {"Content-Type": "application/json; charset=utf-8"}

            if route == "/api/v1/jobs" and method != "POST":
                return 405, self._error_body("METHOD_NOT_ALLOWED", "只支持 POST", request_id, False), {}
            raise ApiError(404, "NOT_FOUND", "接口不存在")
        except ApiError as exc:
            if not authenticated and exc.code == "UNAUTHENTICATED":
                LOG.info("未认证请求被拒绝 path=%s", path)
            return exc.http_status, exc.body(request_id), {
                "Content-Type": "application/json; charset=utf-8"}
        except Exception:  # pragma: no cover - 兜底
            LOG.exception("未预期错误 path=%s", path)
            return 500, self._error_body("PROCESSING_FAILED", "内部错误", request_id, True), {}

    # -- 鉴权 ------------------------------------------------------------
    def _authenticate(self, headers: dict[str, str]) -> None:
        tokens = self.cfg.bearer_tokens
        if not tokens:
            raise ApiError(401, "UNAUTHENTICATED", "本部署未配置模块凭证（失败关闭）")
        header = headers.get("authorization", "")
        if not header.lower().startswith("bearer "):
            raise ApiError(401, "UNAUTHENTICATED", "缺少 Bearer 凭证")
        presented = header[7:].strip()
        for token in tokens:
            if presented and _constant_time_equals(presented, token):
                return
        raise ApiError(401, "UNAUTHENTICATED", "Bearer 凭证无效")

    @staticmethod
    def _error_body(code: str, message: str, request_id: str,
                    retryable: bool) -> dict[str, Any]:
        return {"code": code, "message": message, "retryable": retryable,
                "requestId": request_id, "details": []}


def _constant_time_equals(left: str, right: str) -> bool:
    import hmac

    return hmac.compare_digest(left.encode("utf-8"), right.encode("utf-8"))


class _Handler(BaseHTTPRequestHandler):
    server_version = "headup/0.1"
    protocol_version = "HTTP/1.1"

    # 注入点：server.app
    @property
    def app(self) -> HeadUpApp:
        return self.server.app  # type: ignore[attr-defined]

    def _dispatch(self, method: str) -> None:
        # Reject Host, auth and size before consuming bytes or allocating a JSON body.
        self.connection.settimeout(15)
        self.close_connection = True
        status = None
        payload = None
        extra = {}
        hosts = self.headers.get_all("Host") or []
        configured = self.app.cfg.server_host
        display_host = f"[{configured}]" if ':' in configured and not configured.startswith('[') else configured
        expected = f"{display_host}:{self.server.server_port}"
        if hosts != [expected] or self.headers.get("Origin") is not None:
            status,payload = 403,{"code":"FORBIDDEN","message":"Host/Origin rejected"}
        elif self.headers.get("Transfer-Encoding") is not None:
            status,payload = 411,{"code":"LENGTH_REQUIRED","message":"Chunked request rejected"}
        else:
            try:
                if urlparse(self.path).path not in ("/health","/api/v1/health"):
                    self.app._authenticate({k.lower():v for k,v in self.headers.items()})
                lengths = self.headers.get_all("Content-Length") or []
                if len(lengths)>1: raise ValueError("duplicate length")
                length = int(lengths[0]) if lengths else 0
                if length<0:raise ValueError("negative length")
                if length>self.app.cfg.max_body_bytes:
                    status,payload=413,{"code":"PAYLOAD_TOO_LARGE","message":"JSON body exceeds limit"}
                else:
                    body=self.rfile.read(length) if length else b""
                    if len(body)!=length:raise ValueError("truncated body")
                    status,payload,extra=self.app.handle(method,self.path,dict(self.headers.items()),body)
            except ApiError as exc:
                status,payload=exc.http_status,{"code":exc.code,"message":exc.message}
            except (ValueError,TimeoutError):
                status,payload=400,{"code":"MALFORMED_REQUEST","message":"Invalid or incomplete request"}
        data = b"" if status == 204 else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        for key, value in extra.items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:  # noqa: N802
        self._dispatch("GET")

    def do_DELETE(self) -> None:
        self._dispatch("DELETE")

    def do_POST(self) -> None:  # noqa: N802
        self._dispatch("POST")

    def log_message(self, fmt: str, *args: Any) -> None:  # noqa: A003
        LOG.info("%s - %s", self.address_string(), fmt % args)


class HeadUpServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, cfg: Config, app: HeadUpApp):
        super().__init__((cfg.server_host, cfg.server_port), _Handler)
        self.app = app
        self.cfg = cfg


def create_server(cfg: Config, app: HeadUpApp) -> HeadUpServer:
    return HeadUpServer(cfg, app)
