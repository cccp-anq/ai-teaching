"""受控媒体获取与核验（契约 §1、§3）。

契约把这段责任交给模块：下载原媒体、**核验 sha256**、拒绝任意内网/本机/重定向到未授权地址、
限制下载量。失败分类映射到契约 §5 的任务级错误码：

| 情形 | 任务级错误 | retryable |
| --- | --- | --- |
| 下载被拒（HTTP 401/403/404/410） | `MEDIA_URL_EXPIRED` | true |
| 内容与登记 SHA-256 不一致 | `MEDIA_HASH_MISMATCH` | false |
| 体积超本部署上限 | `PAYLOAD_TOO_LARGE`（补充码） | false |
| 明文 http / 主机不在白名单 / 解析到内网保留地址 | `UNSUPPORTED_MEDIA` | false |
| 网络中断、5xx 等临时故障 | `PROCESSING_FAILED` | true |

白名单与内网拦截属于**部署安全策略**而非任务内容错误：归类为 `UNSUPPORTED_MEDIA`（不可重试），
主项目据此修地址而不是盲重试。`PAYLOAD_TOO_LARGE`、`FFMPEG_MISSING` 是契约 §5 未列举的补充码，
已在 `docs/交接表-head-up-v0.1.md` 显式登记，待联调评审。
"""

from __future__ import annotations

import hashlib
import http.client
import ipaddress
import os
import shutil
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .config import Config
from .wire import TaskError

_REDIRECT_CODES = {301, 302, 303, 307, 308}
_EXPIRED_CODES = {401, 403, 404, 410}
_MAX_REDIRECTS = 3


@dataclass(frozen=True)
class DownloadResult:
    path: Path
    sha256: str
    bytes_written: int


class MediaError(TaskError):
    """下载/探测失败。本身就是一个任务级错误（契约 §5），因此可以直接被
    `except TaskError` 捕获，不必再加一层包装。"""

    def __init__(self, task_error: TaskError | str, message: str | None = None):
        if isinstance(task_error, TaskError):
            super().__init__(task_error.code, task_error.message, task_error.retryable)
        else:
            super().__init__(task_error, message or task_error)

    @property
    def task_error(self) -> "MediaError":
        """兼容旧调用点（`exc.task_error.code`）。"""
        return self


# --------------------------------------------------------------------------
# 地址安全
# --------------------------------------------------------------------------

def _is_blocked_ip(ip: ipaddress._BaseAddress) -> bool:
    return (
        ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast
        or ip.is_reserved or ip.is_unspecified
        or (ip.version == 4 and ip in ipaddress.ip_network("100.64.0.0/10"))  # CGNAT
    )


def check_url_static(url: str, cfg: Config) -> urllib.parse.ParseResult:
    """不涉及 DNS 的地址检查：scheme、主机名、白名单。"""
    parsed = urllib.parse.urlparse(url)
    scheme = (parsed.scheme or "").lower()
    host = parsed.hostname or ""
    if scheme not in ("https", "http"):
        raise MediaError(TaskError("UNSUPPORTED_MEDIA",
                                   f"仅支持 https 下载地址，收到 scheme={scheme!r}"))
    if scheme == "http" and not cfg.allow_loopback_http:
        raise MediaError(TaskError(
            "UNSUPPORTED_MEDIA",
            "拒绝明文 http 下载地址（本地联调可用 MEDIA_ALLOW_LOOPBACK_HTTP=true 显式开启）"))
    if not host:
        raise MediaError(TaskError("UNSUPPORTED_MEDIA", "下载地址缺少主机名"))
    if cfg.host_allow_list and host.lower() not in {h.lower() for h in cfg.host_allow_list}:
        raise MediaError(TaskError(
            "UNSUPPORTED_MEDIA",
            "下载主机不在本部署白名单内（MEDIA_HOST_ALLOW_LIST）"))
    # Integrated local mode permits exactly the signed media route, never arbitrary private hosts.
    if os.environ.get("MERGE_MODE") == "1" and cfg.allow_loopback_http:
        allowed_port = int(os.environ.get("MERGE_MEDIA_PORT","8080"))
        if (parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.port != allowed_port
                or parsed.username is not None or parsed.password is not None
                or not parsed.path.startswith("/module-media/v1/") or parsed.fragment):
            raise MediaError("UNSUPPORTED_MEDIA","本机模式只允许指定签名媒体路由")
    return parsed


def check_host_addresses(host: str, port: int, cfg: Config) -> list[str]:
    """解析主机并拒绝内网/环回/保留地址（契约 §3）。"""
    addresses: list[ipaddress._BaseAddress] = []
    try:
        for _family, _type, _proto, _canon, sockaddr in socket.getaddrinfo(
                host, port, proto=socket.IPPROTO_TCP):
            addresses.append(ipaddress.ip_address(sockaddr[0]))
    except socket.gaierror as exc:
        raise MediaError(TaskError("MEDIA_URL_EXPIRED",
                                   f"下载地址无法解析: {type(exc).__name__}")) from exc
    if not addresses:
        raise MediaError(TaskError("MEDIA_URL_EXPIRED", "下载地址无法解析出任何 IP"))
    blocked = [str(ip) for ip in addresses
               if _is_blocked_ip(ip) and not (cfg.allow_loopback_http and ip.is_loopback)]
    if blocked:
        raise MediaError(TaskError(
            "UNSUPPORTED_MEDIA",
            "下载地址解析到内网/环回/保留地址，已被拒绝（本地开关仅豁免 loopback）"))
    return [str(ip) for ip in addresses]


def validate_url(url: str, cfg: Config, *, resolve: bool = True) -> urllib.parse.ParseResult:
    parsed = check_url_static(url, cfg)
    if resolve:
        check_host_addresses(parsed.hostname or "",
                             parsed.port or (443 if parsed.scheme == "https" else 80), cfg)
    return parsed


class _SafeRedirect(urllib.request.HTTPRedirectHandler):
    """每跳重新校验地址，避免重定向到内网。"""

    def __init__(self, cfg: Config):
        super().__init__()
        self.cfg = cfg
        self.hops = 0

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        if os.environ.get("MERGE_MODE") == "1": raise MediaError("UNSUPPORTED_MEDIA","本机媒体禁止重定向")
        self.hops += 1
        if self.hops > _MAX_REDIRECTS:
            raise MediaError(TaskError("UNSUPPORTED_MEDIA", f"重定向超过 {_MAX_REDIRECTS} 跳"))
        validate_url(newurl, self.cfg)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


# --------------------------------------------------------------------------
# 下载
# --------------------------------------------------------------------------

class _PinnedHTTPConnection(http.client.HTTPConnection):
    def __init__(self, host: str, port: int, pinned_ip: str, timeout: float):
        super().__init__(host, port, timeout=timeout)
        self._pinned_ip = pinned_ip

    def connect(self) -> None:
        self.sock = socket.create_connection((self._pinned_ip, self.port), self.timeout)


class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, host: str, port: int, pinned_ip: str, timeout: float):
        super().__init__(host, port, timeout=timeout, context=ssl.create_default_context())
        self._pinned_ip = pinned_ip

    def connect(self) -> None:
        raw = socket.create_connection((self._pinned_ip, self.port), self.timeout)
        self.sock = self._context.wrap_socket(raw, server_hostname=self.host)


def _open_pinned(url: str, cfg: Config, timeout: float):
    parsed = check_url_static(url, cfg)
    host = parsed.hostname or ""
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    addresses = check_host_addresses(host, port, cfg)
    pinned = addresses[0]
    connection = (_PinnedHTTPSConnection(host, port, pinned, timeout)
                  if parsed.scheme == "https" else _PinnedHTTPConnection(host, port, pinned, timeout))
    target = parsed.path or "/"
    if parsed.query:
        target += "?" + parsed.query
    host_header = host if port in (80, 443) else f"{host}:{port}"
    connection.request("GET", target, headers={
        "Host": host_header,
        "User-Agent": f"{cfg.provider_id}/{cfg.module_version}",
        "Accept": "application/octet-stream,video/*;q=0.9,*/*;q=0.1",
    })
    return connection, connection.getresponse()


def download_media(url: str, cfg: Config, work_dir: str | os.PathLike[str]) -> DownloadResult:
    deadline = time.monotonic() + max(1.0, cfg.media_total_timeout_s)
    current_url = url
    digest = hashlib.sha256()
    written = 0
    target_dir = Path(work_dir)
    target_dir.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix="head-up-media-", suffix=".bin", dir=target_dir)
    tmp_path = Path(tmp_name)
    connection = None
    try:
        with os.fdopen(fd, "wb") as sink:
            for hop in range(_MAX_REDIRECTS + 1):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("media wall clock timeout")
                connection, response = _open_pinned(
                    current_url, cfg, min(cfg.media_read_timeout_s, remaining))
                if response.status in _REDIRECT_CODES:
                    location = response.getheader("Location")
                    response.close(); connection.close(); connection = None
                    if not location or hop >= _MAX_REDIRECTS or os.environ.get("MERGE_MODE") == "1":
                        raise MediaError(TaskError("UNSUPPORTED_MEDIA", "媒体重定向被拒绝或超过上限"))
                    current_url = urllib.parse.urljoin(current_url, location)
                    check_url_static(current_url, cfg)
                    continue
                if response.status in _EXPIRED_CODES:
                    raise MediaError(TaskError("MEDIA_URL_EXPIRED", f"下载被拒（HTTP {response.status}）", True))
                if response.status < 200 or response.status >= 300:
                    raise MediaError(TaskError("PROCESSING_FAILED", f"下载失败（HTTP {response.status}）", True))
                declared = response.getheader("Content-Length")
                if declared and int(declared) > cfg.max_media_bytes:
                    raise MediaError(TaskError("PAYLOAD_TOO_LARGE", f"媒体体积超过本部署上限 {cfg.max_media_bytes} 字节"))
                while True:
                    if time.monotonic() >= deadline:
                        raise TimeoutError("media wall clock timeout")
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    written += len(chunk)
                    if written > cfg.max_media_bytes:
                        raise MediaError(TaskError(
                            "PAYLOAD_TOO_LARGE",
                            f"媒体体积超过本部署上限 {cfg.max_media_bytes} 字节"))
                    digest.update(chunk)
                    sink.write(chunk)
                response.close(); connection.close(); connection = None
                break
    except MediaError:
        tmp_path.unlink(missing_ok=True)
        raise
    except (urllib.error.URLError, http.client.HTTPException, socket.timeout, TimeoutError, OSError,
            ValueError) as exc:
        tmp_path.unlink(missing_ok=True)
        raise MediaError(TaskError(
            "PROCESSING_FAILED", f"下载失败: {type(exc).__name__}", retryable=True)) from exc
    finally:
        if connection is not None:
            connection.close()
    if written == 0:
        tmp_path.unlink(missing_ok=True)
        raise MediaError(TaskError("UNSUPPORTED_MEDIA", "下载内容为空"))
    return DownloadResult(path=tmp_path, sha256=digest.hexdigest(), bytes_written=written)


def verify_hash(result: DownloadResult, expected_sha256: str) -> None:
    if result.sha256 != expected_sha256:
        raise MediaError(TaskError(
            "MEDIA_HASH_MISMATCH",
            f"媒体内容与登记 SHA-256 不一致（实际 {result.sha256[:12]}…）"))


# --------------------------------------------------------------------------
# ffmpeg / ffprobe
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class FfmpegTools:
    ffmpeg: str
    ffprobe: str
    available: bool
    detail: str

    @staticmethod
    def locate(cfg: Config) -> "FfmpegTools":
        ffmpeg = _resolve_tool(cfg.ffmpeg_path)
        ffprobe = _resolve_tool(cfg.ffprobe_path)
        if ffmpeg and ffprobe:
            return FfmpegTools(ffmpeg, ffprobe, True, "ok")
        missing = [name for name, value in (("ffmpeg", ffmpeg), ("ffprobe", ffprobe)) if not value]
        return FfmpegTools(ffmpeg or cfg.ffmpeg_path, ffprobe or cfg.ffprobe_path, False,
                           "缺少外部工具: " + ", ".join(missing))


def _resolve_tool(configured: str) -> str | None:
    if not configured:
        return None
    candidate = Path(configured)
    if candidate.is_file():
        return str(candidate)
    found = shutil.which(configured)
    if found:
        return found
    # 便携目录约定（与转写模块一致）：./.work/tools/ffmpeg/bin/
    for suffix in (".exe", ""):
        fallback = Path(".work") / "tools" / "ffmpeg" / "bin" / f"{configured}{suffix}"
        if fallback.is_file():
            return str(fallback)
    return None


@dataclass(frozen=True)
class MediaProbe:
    duration_ms: int
    width: int | None
    height: int | None


def probe_media(tools: FfmpegTools, path: Path, cfg: Config) -> MediaProbe:
    if not tools.available:
        raise MediaError(TaskError("FFMPEG_MISSING", tools.detail))
    import json as _json

    cmd = [tools.ffprobe, "-v", "error", "-print_format", "json",
           "-show_format", "-show_streams", str(path)]
    try:
        completed = subprocess.run(cmd, capture_output=True, text=True, timeout=cfg.media_probe_timeout_s,
                                   check=False)
    except (subprocess.TimeoutExpired, OSError) as exc:
        raise MediaError(TaskError("DECODE_FAILED", f"ffprobe 执行失败: {type(exc).__name__}")) from exc
    if completed.returncode != 0:
        raise MediaError(TaskError("DECODE_FAILED", "ffprobe 无法解析该媒体（容器损坏或格式不支持）"))
    try:
        info: dict[str, Any] = _json.loads(completed.stdout or "{}")
    except ValueError as exc:
        raise MediaError(TaskError("DECODE_FAILED", "ffprobe 输出无法解析")) from exc
    duration = (info.get("format") or {}).get("duration")
    if duration is None:
        raise MediaError(TaskError("DECODE_FAILED", "读不出媒体时长"))
    try:
        duration_ms = int(round(float(duration) * 1000))
    except (TypeError, ValueError) as exc:
        raise MediaError(TaskError("DECODE_FAILED", "媒体时长非法")) from exc
    width = height = None
    for stream in info.get("streams") or []:
        if stream.get("codec_type") == "video":
            width = stream.get("width") or None
            height = stream.get("height") or None
            break
    if width is None:
        raise MediaError(TaskError("UNSUPPORTED_MEDIA", "该媒体没有视频流，无法做抬头观测"))
    return MediaProbe(duration_ms=duration_ms, width=int(width), height=int(height) if height else None)


def extract_frame(tools: FfmpegTools, path: Path, at_ms: int, out_path: Path, *,
                  max_width: int, cfg: Config) -> Path:
    """抽取 at_ms 处的一帧。**调用方负责把 at_ms 映射回原媒体轴**。"""
    if not tools.available:
        raise MediaError(TaskError("FFMPEG_MISSING", tools.detail))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        tools.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
        "-ss", f"{max(0, at_ms) / 1000.0:.3f}",
        "-i", str(path),
        "-frames:v", "1", "-q:v", "3",
        "-vf", f"scale='min({max_width},iw)':-2:flags=lanczos",
        "-y", str(out_path),
    ]
    try:
        completed = subprocess.run(cmd, capture_output=True, text=True,
                                   timeout=cfg.media_probe_timeout_s, check=False)
    except (subprocess.TimeoutExpired, OSError) as exc:
        raise MediaError(TaskError("DECODE_FAILED", f"抽帧执行失败: {type(exc).__name__}")) from exc
    if completed.returncode != 0 or not out_path.is_file() or out_path.stat().st_size == 0:
        raise MediaError(TaskError("DECODE_FAILED", "抽帧失败（该时间点无可用画面）"))
    return out_path
