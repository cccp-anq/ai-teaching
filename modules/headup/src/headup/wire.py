"""契约层：JobRequest / JobEnvelope 的解析与校验。

严格对齐《三项目公共接口契约 v0.1》§3、§4、§5 与《抬头率项目接口与统计口径 v0.1》§2、§3，
并保持与主项目 fixture（`docs/integration/fixtures/classroom-analysis-v0.1/`）逐字段一致：

- 请求被拒绝 => HTTP 错误体（4xx + code/message/retryable/requestId/details），不创建任务；
- 任务处理失败 => 终态 FAILED 信封（payload=null, error={code,message,retryable}）。

本文件只做“形状与语义”判断，不接触网络、磁盘或模型。
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Iterable

SCHEMA_VERSION = "1.0"
MODULE_TYPE = "HEAD_UP"
TIME_BASE = "MEDIA_START_MS"
SAMPLE_MODE = "REPRESENTATIVE_FRAME"

STATUS_QUEUED = "QUEUED"
STATUS_RUNNING = "RUNNING"
STATUS_SUCCEEDED = "SUCCEEDED"
STATUS_FAILED = "FAILED"
TERMINAL_STATUSES = (STATUS_SUCCEEDED, STATUS_FAILED)

VALUE_MODE_COUNTS = "COUNTS"
VALUE_MODE_RATIO_ONLY = "RATIO_ONLY"
VALUE_MODES = (VALUE_MODE_COUNTS, VALUE_MODE_RATIO_ONLY)

QUALITY_VALID = "VALID"
QUALITY_NO_SUBJECTS = "NO_SUBJECTS"
QUALITY_LOW = "LOW_QUALITY"
# v0.2 Round 2 增量状态：可计算但可观测性不足（疑似），不得进入课程均值。
# 契约 v0.1 未定义该值 ⇒ 主项目适配器需识别；冻结样例不含该值，逐字段一致不受影响。
QUALITY_REVIEW = "REVIEW"
QUALITIES = (QUALITY_VALID, QUALITY_NO_SUBJECTS, QUALITY_LOW, QUALITY_REVIEW)
# 只有 VALID 进入课程均值（Round 2 §3）
QUALITIES_AGGREGATED = (QUALITY_VALID,)

# 契约 §5 任务级错误码 + 本模块声明的补充码（见 docs/交接表-head-up-v0.1.md）。
ERROR_CODES = {
    "MEDIA_URL_EXPIRED": True,
    "MEDIA_HASH_MISMATCH": False,
    "UNSUPPORTED_MEDIA": False,
    "DECODE_FAILED": False,
    "PROCESSING_FAILED": True,
    # 补充码（契约 §5 未列举，联调需评审）：
    "PAYLOAD_TOO_LARGE": False,
    "VISION_PROVIDER_ERROR": True,
    "VISION_CREDENTIAL_MISSING": False,
    "FFMPEG_MISSING": False,
}

# 任务级 error.message 不得出现敏感值；此处仅用于自检。
_SECRET_PATTERNS = (
    re.compile(r"(?i)authorization"),
    re.compile(r"(?i)bearer\s+[A-Za-z0-9._\-]{8,}"),
    re.compile(r"(?i)sk-[A-Za-z0-9]{8,}"),
    re.compile(r"(?i)https?://\S+"),
)


class ApiError(Exception):
    """HTTP 层拒绝：对应契约 §5 的 {code, message, retryable, requestId, details}。"""

    def __init__(self, http_status: int, code: str, message: str,
                 retryable: bool = False, details: Iterable[dict[str, Any]] | None = None):
        super().__init__(message)
        self.http_status = http_status
        self.code = code
        self.message = message
        self.retryable = retryable
        self.details = list(details or [])

    def body(self, request_id: str) -> dict[str, Any]:
        return {
            "code": self.code,
            "message": self.message,
            "retryable": self.retryable,
            "requestId": request_id,
            "details": self.details,
        }


class TaskError(Exception):
    """任务级失败：写入终态 FAILED 信封的 error 字段。"""

    def __init__(self, code: str, message: str, retryable: bool | None = None):
        if code not in ERROR_CODES:
            raise ValueError(f"未声明的任务级错误码: {code}")
        super().__init__(message)
        self.code = code
        self.message = _sanitize(message)
        self.retryable = ERROR_CODES[code] if retryable is None else retryable

    def to_dict(self) -> dict[str, Any]:
        return {"code": self.code, "message": self.message, "retryable": self.retryable}


def _sanitize(message: str) -> str:
    """去掉可能的 URL 与凭证形态，保证 error.message 不泄漏媒体地址/密钥。"""
    text = " ".join(str(message).split())
    if any(pattern.search(text) for pattern in _SECRET_PATTERNS):
        return "处理失败（详情已省略，避免输出凭证或媒体地址）"
    return text[:500]


# --------------------------------------------------------------------------
# 基础类型判断
# --------------------------------------------------------------------------

def is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_utc(text: Any, path: str) -> datetime:
    if not isinstance(text, str) or not text.endswith("Z"):
        raise ApiError(422, "VALIDATION_FAILED", f"{path} 必须是带 Z 的 UTC 时间串",
                       details=[{"path": path, "code": "INVALID", "message": "需要 UTC Z 时间"}])
    try:
        return datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError as exc:
        raise ApiError(422, "VALIDATION_FAILED", f"{path} 不是合法 UTC 时间串",
                       details=[{"path": path, "code": "INVALID", "message": str(exc)}]) from exc


def _reject(path: str, message: str, code: str = "INVALID") -> None:
    raise ApiError(422, "VALIDATION_FAILED", f"{path}: {message}",
                   details=[{"path": path, "code": code, "message": message}])


def _require_str(obj: dict[str, Any], name: str, path: str, *, max_len: int = 128) -> str:
    if name not in obj:
        _reject(f"{path}.{name}", "缺少必填字段", "REQUIRED")
    value = obj[name]
    if not isinstance(value, str) or not value or len(value) > max_len:
        _reject(f"{path}.{name}", f"必须是 1~{max_len} 字符的非空字符串")
    return value


def _require_int(obj: dict[str, Any], name: str, path: str, *,
                 minimum: int | None = None, maximum: int | None = None) -> int:
    if name not in obj:
        _reject(f"{path}.{name}", "缺少必填字段", "REQUIRED")
    value = obj[name]
    if not is_int(value):
        _reject(f"{path}.{name}", "必须是整数")
    if minimum is not None and value < minimum:
        _reject(f"{path}.{name}", f"必须 >= {minimum}")
    if maximum is not None and value > maximum:
        _reject(f"{path}.{name}", f"必须 <= {maximum}")
    return value


# --------------------------------------------------------------------------
# 请求
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class MediaRef:
    sha256: str
    duration_ms: int
    mime_type: str
    source_url: str
    url_expires_at: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "sha256": self.sha256,
            "durationMs": self.duration_ms,
            "mimeType": self.mime_type,
            "sourceUrl": self.source_url,
            "urlExpiresAt": self.url_expires_at,
        }


@dataclass(frozen=True)
class HeadUpParameters:
    window_ms: int
    output_mode: str
    definition_id: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "windowMs": self.window_ms,
            "outputMode": self.output_mode,
            "definitionId": self.definition_id,
        }


@dataclass(frozen=True)
class JobRequest:
    schema_version: str
    client_request_id: str
    module_type: str
    class_session_id: str
    media_id: str
    media: MediaRef
    analysis_range_start_ms: int
    analysis_range_end_ms: int
    parameters: HeadUpParameters

    @property
    def analysis_range(self) -> dict[str, int]:
        return {"startMs": self.analysis_range_start_ms, "endMs": self.analysis_range_end_ms}

    @property
    def duration_ms(self) -> int:
        return self.media.duration_ms

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "clientRequestId": self.client_request_id,
            "moduleType": self.module_type,
            "classSessionId": self.class_session_id,
            "mediaId": self.media_id,
            "media": self.media.to_dict(),
            "analysisRange": self.analysis_range,
            "parameters": self.parameters.to_dict(),
        }

    def for_storage(self) -> dict[str, Any]:
        """落库版本：去掉短期下载凭据（契约 §3：不给类 URL 做长期留存）。"""
        body = self.to_dict()
        body["media"].pop("sourceUrl", None)
        body["media"].pop("urlExpiresAt", None)
        return body

    def idempotency_fingerprint(self) -> str:
        """契约 §3：身份、hash、duration、range、parameters；
        **不含** sourceUrl / urlExpiresAt（可续期的下载凭据）。"""
        payload = {
            "schemaVersion": self.schema_version,
            "clientRequestId": self.client_request_id,
            "moduleType": self.module_type,
            "classSessionId": self.class_session_id,
            "mediaId": self.media_id,
            "media": {
                "sha256": self.media.sha256,
                "durationMs": self.media.duration_ms,
                "mimeType": self.media.mime_type,
            },
            "analysisRange": self.analysis_range,
            "parameters": self.parameters.to_dict(),
        }
        return canonical_hash(payload)


def canonical_json(value: Any) -> str:
    """规范化 JSON：key 递归排序、数组保序、无多余空白。"""
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def canonical_hash(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def parse_job_request(raw: Any, *, idempotency_key: str | None,
                      allowed_definition_ids: Iterable[str], max_duration_ms: int,
                      max_windows: int) -> JobRequest:
    """解析并校验 JobRequest。

    `allowed_definition_ids` 是本部署登记过的观测量口径集合（真实口径 + 合成口径），
    请求带未登记口径一律 422——改变口径必须新建 job（抬头专用文档 §2）。
    """
    allowed = {str(item) for item in allowed_definition_ids}
    if not isinstance(raw, dict):
        raise ApiError(400, "MALFORMED_JSON", "请求体必须是 JSON 对象")

    schema_version = raw.get("schemaVersion")
    if schema_version != SCHEMA_VERSION:
        raise ApiError(422, "UNSUPPORTED_SCHEMA",
                       f"不支持 schemaVersion={schema_version!r}，本部署只接受 {SCHEMA_VERSION}")

    client_request_id = _require_str(raw, "clientRequestId", "request")
    if idempotency_key is not None and idempotency_key != client_request_id:
        _reject("Idempotency-Key",
                "请求头 Idempotency-Key 必须与 body.clientRequestId 完全一致")

    module_type = _require_str(raw, "moduleType", "request", max_len=32)
    if module_type != MODULE_TYPE:
        raise ApiError(403, "FORBIDDEN", f"本部署只接受 moduleType={MODULE_TYPE}")

    class_session_id = _require_str(raw, "classSessionId", "request")
    media_id = _require_str(raw, "mediaId", "request")

    media_raw = raw.get("media")
    if not isinstance(media_raw, dict):
        _reject("media", "缺少 media 对象", "REQUIRED")
    sha = _require_str(media_raw, "sha256", "media", max_len=64)
    if len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha):
        _reject("media.sha256", "必须是 64 位小写十六进制 SHA-256")
    duration_ms = _require_int(media_raw, "durationMs", "media", minimum=1)
    if duration_ms > max_duration_ms:
        _reject("media.durationMs", f"超过本部署上限 {max_duration_ms}ms")
    mime_type = _require_str(media_raw, "mimeType", "media", max_len=128)
    source_url = _require_str(media_raw, "sourceUrl", "media", max_len=2048)
    url_expires_at = _require_str(media_raw, "urlExpiresAt", "media", max_len=64)
    parse_utc(url_expires_at, "media.urlExpiresAt")

    range_raw = raw.get("analysisRange")
    if not isinstance(range_raw, dict):
        _reject("analysisRange", "缺少 analysisRange 对象", "REQUIRED")
    start_ms = _require_int(range_raw, "startMs", "analysisRange", minimum=0)
    end_ms = _require_int(range_raw, "endMs", "analysisRange", minimum=0)
    if end_ms > duration_ms:
        _reject("analysisRange.endMs", "不得超过 media.durationMs")
    if start_ms >= end_ms:
        _reject("analysisRange", "必须满足 startMs < endMs（左闭右开）")

    params_raw = raw.get("parameters")
    if not isinstance(params_raw, dict):
        _reject("parameters", "缺少 parameters 对象", "REQUIRED")
    window_ms = _require_int(params_raw, "windowMs", "parameters", minimum=1)
    if (end_ms - start_ms) // window_ms > max_windows:
        _reject("parameters.windowMs",
                f"窗口数超过本部署上限 {max_windows}，请增大 windowMs 或缩小 analysisRange")
    output_mode = _require_str(params_raw, "outputMode", "parameters", max_len=32)
    if output_mode not in VALUE_MODES:
        _reject("parameters.outputMode", f"必须是 {' 或 '.join(VALUE_MODES)}，不支持的模式不能静默切换")
    request_definition = _require_str(params_raw, "definitionId", "parameters", max_len=128)
    if request_definition not in allowed:
        _reject("parameters.definitionId",
                f"未登记的观测口径 {request_definition!r}；本部署登记: {', '.join(sorted(allowed))}")

    return JobRequest(
        schema_version=SCHEMA_VERSION,
        client_request_id=client_request_id,
        module_type=MODULE_TYPE,
        class_session_id=class_session_id,
        media_id=media_id,
        media=MediaRef(sha256=sha, duration_ms=duration_ms, mime_type=mime_type,
                       source_url=source_url, url_expires_at=url_expires_at),
        analysis_range_start_ms=start_ms,
        analysis_range_end_ms=end_ms,
        parameters=HeadUpParameters(window_ms=window_ms, output_mode=output_mode,
                                    definition_id=request_definition),
    )


# --------------------------------------------------------------------------
# 结果信封
# --------------------------------------------------------------------------

def split_windows(start_ms: int, end_ms: int, window_ms: int) -> list[tuple[int, int]]:
    """按 windowMs 切窗；末窗可短；左闭右开。"""
    spans: list[tuple[int, int]] = []
    cursor = start_ms
    while cursor < end_ms:
        stop = min(cursor + window_ms, end_ms)
        spans.append((cursor, stop))
        cursor = stop
    return spans


def coerce_ranges(ranges: Iterable[dict[str, Any]]) -> list[tuple[int, int]]:
    return [(int(r["startMs"]), int(r["endMs"])) for r in ranges]


def union_size(spans: Iterable[tuple[int, int]]) -> int:
    total, end = 0, -1
    for a, b in sorted(spans):
        total += max(0, b - max(a, end))
        end = max(end, b)
    return total


def overlap_size(a: tuple[int, int], b: tuple[int, int]) -> int:
    return max(0, min(a[1], b[1]) - max(a[0], b[0]))


def merge_ranges(spans: Iterable[tuple[int, int]]) -> list[tuple[int, int]]:
    merged: list[list[int]] = []
    for a, b in sorted(spans):
        if merged and a <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], b)
        else:
            merged.append([a, b])
    return [(a, b) for a, b in merged]


def validate_windows(windows: Any, *, bounds: tuple[int, int], window_ms: int,
                     processed: list[tuple[int, int]], value_mode: str) -> None:
    """逐条校验 payload.windows；任何违规都抛 ValueError（供自检与测试使用）。"""
    if not isinstance(windows, list):
        raise ValueError("windows 必须是数组")
    if not windows:
        raise ValueError("windows 不能为空（没有可计算数据时应整体 FAILED）")
    seen: set[str] = set()
    spans: list[tuple[int, int]] = []
    for index, row in enumerate(windows):
        if not isinstance(row, dict):
            raise ValueError(f"windows[{index}] 必须是对象")
        for field in ("id", "startMs", "endMs", "sampleAtMs", "quality", "qualityReason",
                      "headUpCount", "validObservedCount", "headUpRatio"):
            if field not in row:
                raise ValueError(f"windows[{index}] 缺少 {field}")
        wid = row["id"]
        if not isinstance(wid, str) or not wid or wid in seen:
            raise ValueError(f"windows[{index}].id 为空或重复")
        seen.add(wid)
        a, b = row["startMs"], row["endMs"]
        if not is_int(a) or not is_int(b) or not (bounds[0] <= a < b <= bounds[1]):
            raise ValueError(f"windows[{index}] 区间非法")
        if b - a > window_ms:
            raise ValueError(f"windows[{index}] 超过 windowMs")
        if sum(overlap_size((a, b), p) for p in processed) != b - a:
            raise ValueError(f"windows[{index}] 未被 processedRanges 覆盖")
        spans.append((a, b))

        quality = row["quality"]
        if quality not in QUALITIES:
            raise ValueError(f"windows[{index}].quality 非法")
        reason = row["qualityReason"]
        if quality != QUALITY_VALID and (not isinstance(reason, str) or not reason):
            raise ValueError(f"windows[{index}].qualityReason 非 VALID 时必填")
        sample = row["sampleAtMs"]
        if sample is None:
            if quality != QUALITY_LOW:
                raise ValueError(f"windows[{index}].sampleAtMs 只能为 LOW_QUALITY 的 null")
        elif not (is_int(sample) and a <= sample < b):
            raise ValueError(f"windows[{index}].sampleAtMs 必须落在窗口内")

        up, denominator, rate = row["headUpCount"], row["validObservedCount"], row["headUpRatio"]
        visible_students = row.get("visibleStudentCount")
        if visible_students is not None:
            if not is_int(visible_students) or not (0 <= visible_students <= 1000):
                raise ValueError(f"windows[{index}].visibleStudentCount 必须是 0..1000 的整数或 null")
            if is_int(denominator) and visible_students < denominator:
                raise ValueError(f"windows[{index}].visibleStudentCount 不得小于 validObservedCount")
        if value_mode == VALUE_MODE_COUNTS:
            if rate is not None:
                raise ValueError(f"windows[{index}].headUpRatio 在 COUNTS 下必须为 null")
            if quality in (QUALITY_VALID, QUALITY_REVIEW):
                # REVIEW 也带人数（只是可观测性不足），计数约束与 VALID 相同
                if not (is_int(up) and is_int(denominator) and denominator > 0
                        and 0 <= up <= denominator):
                    raise ValueError(f"windows[{index}] {quality} 需要 "
                                     "0<=headUpCount<=validObservedCount 且分母>0")
            elif quality == QUALITY_NO_SUBJECTS:
                if not (is_int(up) and is_int(denominator) and up == 0 and denominator == 0):
                    raise ValueError(f"windows[{index}] NO_SUBJECTS 必须是 0/0")
            else:
                if up is not None or denominator is not None:
                    raise ValueError(f"windows[{index}] LOW_QUALITY 两个计数必须为 null")
        else:
            if up is not None or denominator is not None:
                raise ValueError(f"windows[{index}] RATIO_ONLY 人数必须为 null")
            if quality in (QUALITY_VALID, QUALITY_REVIEW):
                if not (is_number(rate) and 0.0 <= float(rate) <= 1.0):
                    raise ValueError(f"windows[{index}].headUpRatio 必须是 [0,1]")
            elif rate is not None:
                raise ValueError(f"windows[{index}].headUpRatio 非 VALID/REVIEW 必须为 null")

    if spans != sorted(spans):
        raise ValueError("windows 必须按 startMs 升序")
    if any(a[1] > b[0] for a, b in zip(spans, spans[1:])):
        raise ValueError("windows 不得重叠")
    if union_size(spans) != union_size(processed):
        raise ValueError("windows 必须恰好覆盖 processedRanges，不得有隐式缺口")


def validate_envelope(env: Any, *, expect_client_request_id: str | None = None,
                      window_ms: int | None = None, value_mode: str | None = None) -> None:
    """按契约 §4 + 抬头专用文档 §3 自检一个完整 JobEnvelope。"""
    if not isinstance(env, dict):
        raise ValueError("envelope 必须是对象")
    for field in ("schemaVersion", "clientRequestId", "jobId", "moduleType", "providerId",
                  "moduleVersion", "classSessionId", "mediaId", "mediaSha256", "durationMs",
                  "timeBase", "analysisRange", "status", "revision", "createdAt", "updatedAt",
                  "payload", "error"):
        if field not in env:
            raise ValueError(f"envelope 缺少 {field}")
    if env["schemaVersion"] != SCHEMA_VERSION:
        raise ValueError("schemaVersion 必须是 1.0")
    if env["moduleType"] != MODULE_TYPE:
        raise ValueError("moduleType 必须是 HEAD_UP")
    if env["timeBase"] != TIME_BASE:
        raise ValueError("timeBase 必须是 MEDIA_START_MS")
    for field in ("clientRequestId", "jobId", "providerId", "moduleVersion",
                  "classSessionId", "mediaId"):
        value = env[field]
        if not isinstance(value, str) or not value or len(value) > 128:
            raise ValueError(f"{field} 非法")
    sha = env["mediaSha256"]
    if not isinstance(sha, str) or len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha):
        raise ValueError("mediaSha256 非法")
    duration = env["durationMs"]
    if not (is_int(duration) and duration > 0):
        raise ValueError("durationMs 必须是正整数")
    for field in ("createdAt", "updatedAt"):
        if not isinstance(env[field], str) or not env[field].endswith("Z"):
            raise ValueError(f"{field} 必须是 UTC Z 时间")
    rng = env["analysisRange"]
    if not isinstance(rng, dict):
        raise ValueError("analysisRange 非法")
    a, b = rng.get("startMs"), rng.get("endMs")
    if not (is_int(a) and is_int(b) and 0 <= a < b <= duration):
        raise ValueError("analysisRange 非法")
    status = env["status"]
    if status not in (STATUS_QUEUED, STATUS_RUNNING, STATUS_SUCCEEDED, STATUS_FAILED):
        raise ValueError("status 非法")
    revision = env["revision"]
    if not is_int(revision) or revision != (1 if status in TERMINAL_STATUSES else 0):
        raise ValueError("revision 与 status 不匹配")
    if expect_client_request_id is not None and env["clientRequestId"] != expect_client_request_id:
        raise ValueError("clientRequestId 不匹配")

    if status != STATUS_SUCCEEDED:
        if env["payload"] is not None:
            raise ValueError("非 SUCCEEDED 的 payload 必须是 null")
        if status == STATUS_FAILED:
            err = env["error"]
            if not isinstance(err, dict):
                raise ValueError("FAILED 必须有 error")
            for field in ("code", "message", "retryable"):
                if field not in err:
                    raise ValueError(f"error 缺少 {field}")
            if err["code"] not in ERROR_CODES:
                raise ValueError(f"未声明的任务级错误码: {err['code']}")
            if not isinstance(err["retryable"], bool) or not err["message"]:
                raise ValueError("error 字段非法")
        elif env["error"] is not None:
            raise ValueError("非 FAILED 的 error 必须是 null")
        return

    if env["error"] is not None:
        raise ValueError("SUCCEEDED 的 error 必须是 null")
    payload = env["payload"]
    if not isinstance(payload, dict):
        raise ValueError("SUCCEEDED 必须有 payload")
    for field in ("definitionId", "denominatorDefinition", "sampleMode", "valueMode",
                  "windowMs", "processedRanges", "windows", "warnings"):
        if field not in payload:
            raise ValueError(f"payload 缺少 {field}")
    if not payload["definitionId"] or not payload["denominatorDefinition"]:
        raise ValueError("definitionId/denominatorDefinition 不能为空")
    if payload["sampleMode"] != SAMPLE_MODE:
        raise ValueError("sampleMode 必须是 REPRESENTATIVE_FRAME")
    if payload["valueMode"] not in VALUE_MODES:
        raise ValueError("valueMode 非法")
    if not (is_int(payload["windowMs"]) and payload["windowMs"] > 0):
        raise ValueError("windowMs 非法")
    if not isinstance(payload["warnings"], list) or not all(
            isinstance(w, str) for w in payload["warnings"]):
        raise ValueError("warnings 必须是字符串数组")
    processed = []
    for item in payload["processedRanges"]:
        if not isinstance(item, dict):
            raise ValueError("processedRanges 元素必须是对象")
        pa, pb = item.get("startMs"), item.get("endMs")
        if not (is_int(pa) and is_int(pb) and a <= pa < pb <= b):
            raise ValueError("processedRanges 区间非法或在 analysisRange 外")
        processed.append((pa, pb))
    if not processed:
        raise ValueError("processedRanges 不能为空")
    if processed != sorted(processed) or any(x[1] > y[0] for x, y in zip(processed, processed[1:])):
        raise ValueError("processedRanges 必须有序且不重叠")
    if union_size(processed) < (b - a) and "PARTIAL_PROCESSING" not in payload["warnings"]:
        raise ValueError("部分处理必须带 PARTIAL_PROCESSING 告警")
    if window_ms is not None and payload["windowMs"] != window_ms:
        raise ValueError("windowMs 与请求不一致")
    if value_mode is not None and payload["valueMode"] != value_mode:
        raise ValueError("valueMode 与请求 outputMode 不一致")
    validate_windows(payload["windows"], bounds=(a, b), window_ms=payload["windowMs"],
                     processed=processed, value_mode=payload["valueMode"])


def build_envelope(*, job_id: str, client_request_id: str, provider_id: str,
                   module_version: str, class_session_id: str, media_id: str,
                   media_sha256: str, duration_ms: int, analysis_range: dict[str, int],
                   status: str, revision: int, created_at: str, updated_at: str,
                   payload: dict[str, Any] | None = None,
                   error: dict[str, Any] | None = None) -> dict[str, Any]:
    envelope = {
        "schemaVersion": SCHEMA_VERSION,
        "clientRequestId": client_request_id,
        "jobId": job_id,
        "moduleType": MODULE_TYPE,
        "providerId": provider_id,
        "moduleVersion": module_version,
        "classSessionId": class_session_id,
        "mediaId": media_id,
        "mediaSha256": media_sha256,
        "durationMs": duration_ms,
        "timeBase": TIME_BASE,
        "analysisRange": dict(analysis_range),
        "status": status,
        "revision": revision,
        "createdAt": created_at,
        "updatedAt": updated_at,
        "payload": payload,
        "error": error,
    }
    validate_envelope(envelope)
    return envelope
