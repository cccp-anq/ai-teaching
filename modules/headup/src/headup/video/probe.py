"""视频探测（FRAME-0）。

职责边界（plan §15）：本模块只回答"这个视频能不能处理、元数据是什么"，
**不抽帧、不调用模型**；失败必须在任何模型调用之前发生。

对外错误码**沿用契约 §5 的现有码**（决策⑤），阶段信息放在 `ProbeError.stage`，
便于定位是"环境问题"还是"媒体问题"：

| 场景 | 契约错误码 | 阶段 |
| --- | --- | --- |
| ffmpeg/ffprobe 不可用 | `FFMPEG_MISSING` | `PROBING` |
| 文件不存在 / 不是文件 | `DECODE_FAILED` | `PROBING` |
| 容器损坏、读不出元数据、读不出时长 | `DECODE_FAILED` | `PROBING` |
| 没有视频流（纯音频等） | `UNSUPPORTED_MEDIA` | `PROBING` |
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from ..config import Config
from ..media import FfmpegTools, MediaError
from ..wire import TaskError

STAGE_PROBING = "PROBING_VIDEO"


@dataclass(frozen=True)
class VideoMetadata:
    """ffprobe 结果（plan §5.1）。`fps`/`codec` 可能是 None（容器未声明时）。"""

    duration_ms: int
    width: int
    height: int
    fps: float | None = None
    codec: str | None = None
    container: str | None = None
    bit_rate: int | None = None
    nb_frames: int | None = None
    has_audio: bool = False
    source_path: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "durationMs": self.duration_ms,
            "width": self.width,
            "height": self.height,
            "fps": self.fps,
            "codec": self.codec,
            "container": self.container,
            "bitRate": self.bit_rate,
            "nbFrames": self.nb_frames,
            "hasAudio": self.has_audio,
            "sourcePath": self.source_path,
        }


class ProbeError(MediaError):
    """探测失败（附带阶段信息）。仍是 `TaskError` 子类，可直接被任务层捕获。"""

    def __init__(self, task_error: TaskError, stage: str = STAGE_PROBING):
        super().__init__(task_error)
        self.stage = stage
        self.error_stage = stage


class VideoProbeService:
    """探测服务：可注入工具定位函数，便于测试"环境缺失"分支。"""

    def __init__(self, cfg: Config, *, tool_locator: Callable[[Config], FfmpegTools] | None = None):
        self.cfg = cfg
        self._locate = tool_locator or FfmpegTools.locate

    # -- 对外 -------------------------------------------------------------
    def probe(self, path: str | Path) -> VideoMetadata:
        target = Path(path)
        self._require_file(target)
        tools = self._require_tools()
        info = self._run_ffprobe(tools, target)
        return self._parse(info, target)

    # -- 步骤 -------------------------------------------------------------
    def _require_file(self, target: Path) -> None:
        if not target.exists():
            raise ProbeError(TaskError("DECODE_FAILED", "媒体文件不存在"))
        if not target.is_file():
            raise ProbeError(TaskError("DECODE_FAILED", "媒体路径不是一个文件"))
        if target.stat().st_size == 0:
            raise ProbeError(TaskError("DECODE_FAILED", "媒体文件为空"))

    def _require_tools(self) -> FfmpegTools:
        tools = self._locate(self.cfg)
        if not tools.available:
            raise ProbeError(TaskError("FFMPEG_MISSING", tools.detail))
        return tools

    def _run_ffprobe(self, tools: FfmpegTools, target: Path) -> dict[str, Any]:
        import json as _json
        import subprocess

        cmd = [tools.ffprobe, "-v", "error", "-print_format", "json",
               "-show_format", "-show_streams", str(target)]
        try:
            completed = subprocess.run(cmd, capture_output=True, text=True,
                                       timeout=self.cfg.media_probe_timeout_s, check=False)
        except (subprocess.TimeoutExpired, OSError) as exc:
            raise ProbeError(TaskError("DECODE_FAILED",
                                       f"ffprobe 执行失败: {type(exc).__name__}")) from exc
        if completed.returncode != 0:
            raise ProbeError(TaskError(
                "DECODE_FAILED", "ffprobe 无法解析该媒体（容器损坏或格式不支持）"))
        try:
            info = _json.loads(completed.stdout or "{}")
        except ValueError as exc:
            raise ProbeError(TaskError("DECODE_FAILED", "ffprobe 输出无法解析")) from exc
        if not isinstance(info, dict):
            raise ProbeError(TaskError("DECODE_FAILED", "ffprobe 输出不是 JSON 对象"))
        return info

    def _parse(self, info: dict[str, Any], target: Path) -> VideoMetadata:
        fmt = info.get("format") or {}
        duration_ms = _duration_ms(fmt.get("duration"))
        if duration_ms is None or duration_ms <= 0:
            raise ProbeError(TaskError("DECODE_FAILED", "读不出有效的媒体时长"))

        video_stream = None
        has_audio = False
        for stream in info.get("streams") or []:
            if stream.get("codec_type") == "video" and video_stream is None:
                video_stream = stream
            elif stream.get("codec_type") == "audio":
                has_audio = True
        if video_stream is None:
            raise ProbeError(TaskError("UNSUPPORTED_MEDIA", "该媒体没有视频流，无法做抬头观测"))

        width = _positive_int(video_stream.get("width"))
        height = _positive_int(video_stream.get("height"))
        if width is None or height is None:
            raise ProbeError(TaskError("DECODE_FAILED", "视频流缺少分辨率信息"))

        return VideoMetadata(
            duration_ms=duration_ms,
            width=width,
            height=height,
            fps=_fps(video_stream),
            codec=video_stream.get("codec_name") or None,
            container=fmt.get("format_name") or None,
            bit_rate=_positive_int(fmt.get("bit_rate")),
            nb_frames=_positive_int(video_stream.get("nb_frames")),
            has_audio=has_audio,
            source_path=str(target),
        )


def probe_video(path: str | Path, cfg: Config, *,
                tool_locator: Callable[[Config], FfmpegTools] | None = None) -> VideoMetadata:
    """便捷入口：探测一个本地媒体。失败抛 `ProbeError`（含 error_stage）。"""
    return VideoProbeService(cfg, tool_locator=tool_locator).probe(path)


# --------------------------------------------------------------------------
def _positive_int(value: Any) -> int | None:
    try:
        number = int(str(value).strip())
    except (TypeError, ValueError):
        return None
    return number if number > 0 else None


def _duration_ms(value: Any) -> int | None:
    try:
        return int(round(float(str(value).strip()) * 1000))
    except (TypeError, ValueError):
        return None


def _fps(stream: dict[str, Any]) -> float | None:
    """解析帧率：优先 avg_frame_rate，其次 r_frame_rate；'0/0' 视为未知。"""
    for key in ("avg_frame_rate", "r_frame_rate"):
        raw = stream.get(key)
        if not raw:
            continue
        text = str(raw)
        try:
            if "/" in text:
                numerator, denominator = text.split("/", 1)
                num, den = float(numerator), float(denominator)
                if den == 0 or num == 0:
                    continue
                return round(num / den, 6)
            value = float(text)
            if value > 0:
                return round(value, 6)
        except (TypeError, ValueError):
            continue
    return None
