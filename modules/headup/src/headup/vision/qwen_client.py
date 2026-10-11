"""Qwen-VL 视觉客户端（FRAME-3）。

职责边界（plan §3/§15）：**本客户端不知道"视频"是什么**，只处理本地图片路径或路径列表。
它不做视频解码、不做抽帧、不做窗口划分、不做质量分档。

判定口径见 `headup.prompts`（默认 `headup-student-attendance-v0.3`：匿名统计学生，
排除教师与重复影像；只见后脑/背对镜头必须 UNKNOWN）；计数自校验见 `headup.counts`。
"""

from __future__ import annotations

import base64
import io
import json
import logging
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .. import prompts
from ..config import Config
from ..counts import (CountInconsistency, WindowCounts, aggregate_counts, from_legacy,
                      make_counts)
from ..wire import TaskError
from .schema import FrameAnalyzer, FrameReading

LOG = logging.getLogger("headup.vision.qwen")

PROMPT_VERSION = prompts.DEFAULT_PROMPT_VERSION
SYSTEM_PROMPT = prompts.SYSTEM_PROMPT
USER_PROMPT_V1 = prompts.USER_PROMPT_V1
USER_PROMPT_STRICT_V02 = prompts.USER_PROMPT_STRICT_V02
USER_PROMPT_ATTENDANCE_V03 = prompts.USER_PROMPT_ATTENDANCE_V03


# --------------------------------------------------------------------------
# 图像预处理
# --------------------------------------------------------------------------

def prepare_image(path: Path, *, cfg: Config) -> tuple[str, dict[str, Any]]:
    """缩放并编码为 data URL；返回 (data_url, 元信息)。分块由调用方决定。"""
    from PIL import Image

    with Image.open(path) as image:
        image = image.convert("RGB")
        width, height = image.size
        limit = max(256, int(cfg.vision_max_long_side))
        longest = max(width, height)
        if longest > limit:
            ratio = limit / longest
            image = image.resize((max(1, int(width * ratio)), max(1, int(height * ratio))),
                                 Image.LANCZOS)
        buffer = io.BytesIO()
        quality = 88
        image.save(buffer, format="JPEG", quality=quality, optimize=True)
        while buffer.tell() > cfg.vision_max_image_bytes and quality > 40:
            quality -= 12
            buffer = io.BytesIO()
            image.save(buffer, format="JPEG", quality=quality, optimize=True)
        data = buffer.getvalue()
        meta = {"width": image.size[0], "height": image.size[1], "bytes": len(data),
                "quality": quality}
    return "data:image/jpeg;base64," + base64.b64encode(data).decode("ascii"), meta


def tiled_boxes(width: int, height: int, grid: str, overlap: float) -> list[tuple[int, int, int, int]]:
    """把画面切成 grid（如 3x2）并带少量重叠，降低密集小目标的漏计。"""
    if not grid or "x" not in grid:
        return [(0, 0, width, height)]
    cols_text, rows_text = grid.lower().split("x", 1)
    try:
        cols, rows = max(1, int(cols_text)), max(1, int(rows_text))
    except ValueError:
        return [(0, 0, width, height)]
    if cols == 1 and rows == 1:
        return [(0, 0, width, height)]
    overlap = max(0.0, min(0.4, float(overlap)))
    tile_w = width / cols
    tile_h = height / rows
    pad_x = tile_w * overlap
    pad_y = tile_h * overlap
    boxes = []
    for row in range(rows):
        for col in range(cols):
            left = max(0, int(col * tile_w - pad_x))
            top = max(0, int(row * tile_h - pad_y))
            right = min(width, int((col + 1) * tile_w + pad_x))
            bottom = min(height, int((row + 1) * tile_h + pad_y))
            if right - left < 8 or bottom - top < 8:
                continue
            boxes.append((left, top, right, bottom))
    return boxes or [(0, 0, width, height)]


def crop_to_data_url(path: Path, box: tuple[int, int, int, int], *, cfg: Config) -> str:
    from PIL import Image

    with Image.open(path) as image:
        image = image.convert("RGB").crop(box)
        limit = max(256, int(cfg.vision_max_long_side))
        if max(image.size) > limit:
            ratio = limit / max(image.size)
            image = image.resize((max(1, int(image.size[0] * ratio)),
                                  max(1, int(image.size[1] * ratio))), Image.LANCZOS)
        buffer = io.BytesIO()
        image.save(buffer, format="JPEG", quality=88, optimize=True)
    return "data:image/jpeg;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")


# --------------------------------------------------------------------------
# Qwen-VL（DashScope OpenAI 兼容）
# --------------------------------------------------------------------------

class QwenVlAnalyzer:
    name = "qwen-vl"

    def __init__(self, cfg: Config, *, prompt_version: str | None = None):
        self.cfg = cfg
        self.prompt_version = prompt_version or cfg.prompt_version
        prompts.user_prompt(self.prompt_version)  # 未知口径立即报错，不留到运行时
        self.version = self.prompt_version
        # Round 4：追溯用——本次运行到底用了哪些模型、最后一次请求的追溯信息
        self.used_models: list[str] = []
        self.last_trace: dict[str, Any] = {}
        # FRAME-6：传输层计数（真正计费的 HTTP 请求数，含计数不自洽重试与备用模型重试）。
        # 与"逻辑调用数"（一次 analyze/analyze_window）不同，后者由 perf.CountingAnalyzer 统计。
        self.request_count = 0
        self.request_notes: list[str] = []
        self.token_totals: dict[str, int] = {"promptTokens": 0, "completionTokens": 0}
        if not cfg.vision_api_key:
            raise TaskError("VISION_CREDENTIAL_MISSING",
                            "未配置视觉模型凭证（DASHSCOPE_API_KEY），无法进行真实抬头判定")

    def fork(self):
        """Independent mutable state for a representative-frame worker."""
        return type(self)(self.cfg, prompt_version=self.prompt_version)

    # -- HTTP ------------------------------------------------------------
    def _chat(self, model: str, data_url: str | list[str], *,
              prompt: str | None = None) -> tuple[str, dict[str, Any]]:
        """返回 (文本, 追溯信息)。

        `data_url` 可以是单张图片，也可以是**多张图片**（FRAME-4 的三帧复核：
        一次请求带三张图，产出一个窗口观测）。
        """
        urls = [data_url] if isinstance(data_url, str) else list(data_url)
        content: list[dict[str, Any]] = [
            {"type": "text", "text": prompt or prompts.user_prompt(self.prompt_version)}]
        content += [{"type": "image_url", "image_url": {"url": url}} for url in urls]
        body: dict[str, Any] = {
            "model": model,
            "temperature": self.cfg.vision_temperature,
            "max_tokens": self.cfg.vision_max_tokens,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": content},
            ],
        }
        # Round 4：把生成参数显式化并可追溯（探测结论见
        # docs/rounds/Round4-稳定性与版本固化.md：seed 与 thinking 开关对本任务无稳定性收益）
        if self.cfg.vision_seed is not None:
            body["seed"] = int(self.cfg.vision_seed)
        if self.cfg.vision_disable_thinking:
            body["enable_thinking"] = False
        request = urllib.request.Request(
            self.cfg.vision_base_url.rstrip("/") + "/chat/completions",
            data=json.dumps(body).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.cfg.vision_api_key}",
            },
            method="POST",
        )
        try:
            hook = getattr(self,"before_request",None)
            cached = hook(body) if hook is not None else None
            if cached is not None: payload=cached
            else:
                self.request_count += 1
                with urllib.request.urlopen(request, timeout=self.cfg.vision_timeout_s) as response:
                    data=response.read(2097153)
                    if len(data)>2097152: raise TaskError("VISION_PROVIDER_ERROR","视觉响应过大")
                    payload=json.loads(data.decode("utf-8"))
                after=getattr(self,"after_request",None)
                if after is not None: after(payload)
        except urllib.error.HTTPError as exc:
            rejected = getattr(self, "request_rejected", None)
            if rejected is not None:
                rejected(exc.code, exc.headers.get("Retry-After"))
            detail = exc.read().decode("utf-8", "ignore")[:200]
            self.request_notes.append(f"HTTP {exc.code} model={model}: {detail[:80]}")
            raise TaskError("VISION_PROVIDER_ERROR",
                            f"视觉模型返回 HTTP {exc.code}", retryable=exc.code >= 500
                            or exc.code == 429) from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            self.request_notes.append(f"{type(exc).__name__} model={model}")
            if getattr(self, "before_request", None) is not None:
                from ..recovery import UnknownRequest
                unknown = getattr(self, "request_unknown", None)
                if unknown is not None: unknown()
                raise UnknownRequest() from exc
            raise TaskError("VISION_PROVIDER_ERROR",
                            f"视觉模型调用失败: {type(exc).__name__}", retryable=True) from exc
        finally:
            finished = getattr(self, "request_finished", None)
            if finished is not None:
                finished()
        try:
            text = payload["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise TaskError("VISION_PROVIDER_ERROR", "视觉模型响应结构异常",
                            retryable=True) from exc
        # 追溯信息：DashScope 只回请求 id 与请求用的模型名（无服务端快照号，
        # `system_fingerprint` 实测为 null），因此 model_version 记录请求名 + 首个请求 id。
        usage = payload.get("usage") or {}
        if isinstance(usage.get("prompt_tokens"), int):
            self.token_totals["promptTokens"] += int(usage["prompt_tokens"])
        if isinstance(usage.get("completion_tokens"), int):
            self.token_totals["completionTokens"] += int(usage["completion_tokens"])
        trace = {
            "requestedModel": model,
            "responseModel": payload.get("model") or model,
            "requestId": payload.get("id"),
            "systemFingerprint": payload.get("system_fingerprint"),
            "promptTokens": usage.get("prompt_tokens"),
            "completionTokens": usage.get("completion_tokens"),
            "seed": self.cfg.vision_seed,
            "enableThinking": (False if self.cfg.vision_disable_thinking else None),
            "temperature": self.cfg.vision_temperature,
        }
        return text, trace

    # -- 解析 ------------------------------------------------------------
    @staticmethod
    def parse(text: str) -> dict[str, Any]:
        """解析模型返回的 JSON 对象（形状校验；计数一致性由 `to_counts` 校验）。"""
        if not isinstance(text, str):
            raise TaskError("VISION_PROVIDER_ERROR", "视觉模型返回非文本内容", retryable=True)
        start, end = text.find("{"), text.rfind("}")
        if start < 0 or end <= start:
            raise TaskError("VISION_PROVIDER_ERROR", "视觉模型未返回 JSON 对象", retryable=True)
        try:
            data = json.loads(text[start:end + 1])
        except ValueError as exc:
            raise TaskError("VISION_PROVIDER_ERROR", "视觉模型 JSON 无法解析",
                            retryable=True) from exc
        if not isinstance(data, dict):
            raise TaskError("VISION_PROVIDER_ERROR", "视觉模型返回的不是 JSON 对象",
                            retryable=True)

        strict_shape = "visible_count" in data or "head_up_count" in data
        count_keys = (("visible_count", "head_up_count", "head_down_count", "unknown_count")
                      if strict_shape else ("head_up", "head_down"))
        for key in count_keys:
            value = data.get(key)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0:
                raise TaskError("VISION_PROVIDER_ERROR", f"视觉模型字段 {key} 非法",
                                retryable=True)
        if not strict_shape and "unknown" not in data:
            raise TaskError("VISION_PROVIDER_ERROR", "视觉模型字段 unknown 缺失", retryable=True)
        if not strict_shape and "legible_ratio" in data:
            value = data["legible_ratio"]
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise TaskError("VISION_PROVIDER_ERROR", "视觉模型字段 legible_ratio 非法",
                                retryable=True)
        value = data.get("confidence", 0.0)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise TaskError("VISION_PROVIDER_ERROR", "视觉模型字段 confidence 非法",
                            retryable=True)
        return data

    @staticmethod
    def to_counts(data: dict[str, Any], *, prompt_version: str) -> WindowCounts:
        """模型 JSON → 统一计数结构；计数不自洽时抛 `CountInconsistency`。

        严格口径要求模型给出 `visible_count`；旧版口径（或未给 visible 的返回）走兼容层，
        此时 `visible = up + down + unknown`，并标记 `strict=False` 以便追溯。
        """
        confidence = float(data.get("confidence", 0.0) or 0.0)
        note = str(data.get("note", "") or "")
        if "visible_count" in data and "head_up_count" in data:
            raw = {"visible_count": data["visible_count"], "head_up_count": data["head_up_count"],
                   "head_down_count": data.get("head_down_count", 0),
                   "unknown_count": data.get("unknown_count", 0)}
            for name, value in raw.items():
                if isinstance(value, bool) or not isinstance(value, int):
                    raise CountInconsistency(f"{name} 必须是整数，实际为 {value!r}")
            return make_counts(**raw, confidence=confidence, note=note, strict=True)
        # 兼容层：旧版三字段
        legacy_raw = {"head_up": data.get("head_up", 0), "head_down": data.get("head_down", 0),
                      "unknown": data.get("unknown", 0)}
        for name, value in legacy_raw.items():
            if isinstance(value, bool) or not isinstance(value, int):
                raise CountInconsistency(f"{name} 必须是整数，实际为 {value!r}")
        legacy = from_legacy(**legacy_raw, confidence=confidence, note=note)
        if prompts.is_strict(prompt_version):
            raise CountInconsistency(
                "严格口径下模型未返回 visible_count/evaluable 自洽计数（收到旧版三字段）")
        return legacy

    # -- 单图 / 多图 ------------------------------------------------------
    def _read_once(self, data_url: str | list[str], *,
                   prompt: str | None = None, prompt_version: str | None = None,
                   frame_count: int = 1) -> FrameReading:
        """一次视觉观测：主模型 → 备用模型，每个模型最多 `vision_max_retries + 1` 次。

        `data_url` 可以是单张图片或**多张图片**（同一请求内）；
        计数不自洽（`CountInconsistency`）时按"可重试"处理，额外重试
        `counts_retry_extra` 次；仍不自洽则抛出显式错误，绝不进入统计。
        """
        version = prompt_version or self.prompt_version
        last: TaskError | None = None
        last_inconsistency: CountInconsistency | None = None
        models: list[str] = [self.cfg.vision_model]
        if self.cfg.vision_fallback_model and self.cfg.vision_fallback_model != self.cfg.vision_model:
            models.append(self.cfg.vision_fallback_model)
        extra = max(0, int(self.cfg.counts_retry_extra)) if self.cfg.strict_counts else 0
        attempts_per_model = max(1, self.cfg.vision_max_retries + 1) + extra
        for model in models:
            for attempt in range(attempts_per_model):
                trace: dict[str, Any] = {}
                try:
                    text, trace = self._chat(model, data_url, prompt=prompt)
                    data = self.parse(text)
                    counts = self.to_counts(data, prompt_version=version)
                except CountInconsistency as exc:
                    last_inconsistency = exc
                    LOG.warning("计数不自洽 model=%s attempt=%d: %s", model, attempt + 1, exc)
                    continue
                except TaskError as exc:
                    from ..recovery import BudgetStop, UnknownRequest
                    if isinstance(exc,(BudgetStop, UnknownRequest)):raise
                    last = exc
                    LOG.warning("视觉判定失败 model=%s attempt=%d code=%s message=%s",
                                model, attempt + 1, exc.code, exc.message)
                    if not exc.retryable:
                        break  # 非临时错误不再重试同一模型，交给备用模型
                    continue
                notes = []
                if len(models) > 1 and model != self.cfg.vision_model:
                    notes.append(f"fallbackModel={model}")
                if counts.note:
                    notes.append(counts.note)
                if not counts.strict:
                    notes.append("legacyCounts=true（未使用严格自洽计数）")
                self.last_trace = dict(trace)
                self.used_models.append(model)
                if frame_count > 1:
                    notes.append(f"multiFrameRequest={frame_count}")
                return FrameReading(
                    head_up=counts.head_up_count,
                    head_down=counts.head_down_count,
                    unknown=counts.unknown_count,
                    legible_ratio=counts.coverage or 0.0,
                    confidence=counts.confidence,
                    analysis_notes=notes,
                    raw=text[:2000],
                    counts=counts,
                    model_used=model,
                    trace=dict(trace),
                    frame_count=frame_count,
                )
        if last is not None:
            raise last
        raise TaskError("VISION_PROVIDER_ERROR",
                        f"视觉模型计数连续不自洽: {last_inconsistency}", retryable=True)

    def analyze(self, image_path: Path) -> FrameReading:
        if self.cfg.frame_tile_grid and "x" in self.cfg.frame_tile_grid \
                and self.cfg.frame_tile_grid != "1x1":
            return self._analyze_tiled(image_path)
        data_url, _meta = prepare_image(image_path, cfg=self.cfg)
        return self._read_once(data_url)

    def analyze_window(self, image_paths: list[Path]) -> FrameReading:
        """多帧窗口复核（FRAME-4 / 用户决策③）：**一次请求带多张图**，产出一个观测。

        - 禁止"三张图分别调用三次再平均"；
        - 返回的 `FrameReading.frameCount` = 请求的图片数，供结果层标注
          `WINDOW_ESTIMATE_MULTI_FRAME`；
        - 单张图片时退化为普通单图调用，行为与 `analyze()` 一致。
        """
        paths = [Path(p) for p in image_paths]
        if not paths:
            raise TaskError("PROCESSING_FAILED", "复核窗口没有任何帧")
        if len(paths) == 1:
            return self.analyze(paths[0])
        data_urls = [prepare_image(path, cfg=self.cfg)[0] for path in paths]
        return self._read_once(data_urls, prompt=prompts.USER_PROMPT_REVIEW_MULTI,
                              prompt_version=prompts.PROMPT_VERSION_REVIEW,
                              frame_count=len(paths))

    #: 语义别名（plan §14：analyzeFrame / analyzeWindow 两种调用语义）
    analyze_frame = analyze

    def _analyze_tiled(self, image_path: Path) -> FrameReading:
        from PIL import Image

        with Image.open(image_path) as image:
            width, height = image.size
        boxes = tiled_boxes(width, height, self.cfg.frame_tile_grid, self.cfg.frame_tile_overlap)
        readings: list[FrameReading] = []
        notes: list[str] = []
        for index, box in enumerate(boxes):
            data_url = crop_to_data_url(image_path, box, cfg=self.cfg)
            reading = self._read_once(data_url)
            readings.append(reading)
            if reading.analysis_notes:
                notes.append(f"tile{index + 1}: {reading.analysis_notes[0]}")
        merged = aggregate_counts([item.counts for item in readings if item.counts])
        return FrameReading(
            head_up=merged.head_up_count,
            head_down=merged.head_down_count,
            unknown=merged.unknown_count,
            legible_ratio=merged.coverage or 0.0,
            confidence=min((item.confidence for item in readings), default=0.0),
            analysis_notes=notes,
            raw=f"tiles={len(boxes)}",
            counts=merged,
        )


#: FRAME-3 起的正式名称（plan §3：视觉客户端不知道"视频"是什么，只处理图片）
QwenVisionClient = QwenVlAnalyzer
