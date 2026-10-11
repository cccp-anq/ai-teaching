"""抽帧与时间映射（抬头专用文档 §2、§3 的 sampleMode=REPRESENTATIVE_FRAME）。

窗口与代表帧：
- 请求给出 `windowMs`，把 `analysisRange` 切成左闭右开窗口，末窗可短；
- 每个窗口选**一帧**作为该窗口的估计值，`sampleAtMs` 必须落在窗口内；
- 帧位置可配置（`HEADUP_SAMPLE_POSITION`：start|middle|end），默认 middle（窗口中点）。

时间映射（契约 §1 的硬要求）：
    原媒体毫秒 = 处理片段毫秒 + offsetMs
当前整条媒体处理时 offsetMs ≡ 0（`time_mapper(0)` 即 identity），保留该函数作为唯一改动点，
将来支持单段裁剪只改这里，不让主项目猜偏移。变速、剪辑拼接、多镜头融合首版不支持。
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

from .media import FfmpegTools, MediaError, extract_frame
from .wire import TaskError


def time_mapper(offset_ms: int) -> Callable[[int], int]:
    """处理片段毫秒 → 原媒体毫秒。整条媒体处理时 offset_ms=0。"""
    def _map(clip_ms: int) -> int:
        return int(clip_ms) + int(offset_ms)
    return _map


@dataclass(frozen=True)
class FramePlan:
    window_index: int
    window_id: str
    window_start_ms: int
    window_end_ms: int
    sample_at_ms: int


@dataclass(frozen=True)
class ExtractedFrame:
    plan: FramePlan
    path: Path


def plan_frames(*, start_ms: int, end_ms: int, window_ms: int,
                position: str = "middle", offset_ms: int = 0) -> list[FramePlan]:
    """窗口计划（兼容入口）。

    FRAME-6 起**网格一律由 `video.sampling.SamplingPolicy` 生成**（用户决策①：
    "采样网格只由 SamplingPolicy 生成"），本函数只是它的薄包装，不再自己算网格——
    保留签名是因为 CLI、合成 provider 与既有测试都在用，但实现只有一份。

    `offset_ms` 语义不变（处理片段 → 原媒体时间映射，`time_mapper`），
    与 SamplingPolicy 的 `start_offset/end_offset`（只过滤不平移）是两回事。
    """
    from .video.sampling import SamplingPolicy, plan_frame_plans

    if position not in ("start", "middle", "end"):
        raise ValueError("sample position 只能是 start/middle/end")
    if end_ms <= start_ms:
        # 退化区间保持旧行为（返回空网格）；window_ms<=0 现在由 SamplingPolicy 显式拒绝
        # （旧实现会在这里死循环，显式报错是修 bug 而不是改口径）。
        return []
    plans = plan_frame_plans(SamplingPolicy(window_ms=window_ms, position=position),
                             start_ms=start_ms, end_ms=end_ms)
    if not offset_ms:
        return plans
    mapper = time_mapper(offset_ms)
    return [FramePlan(window_index=plan.window_index, window_id=plan.window_id,
                      window_start_ms=plan.window_start_ms, window_end_ms=plan.window_end_ms,
                      sample_at_ms=mapper(plan.sample_at_ms)) for plan in plans]


def extract_planned_frames(tools: FfmpegTools, media_path: Path, plans: Iterable[FramePlan], *,
                           out_dir: Path, max_width: int, cfg) -> list[ExtractedFrame]:
    frames: list[ExtractedFrame] = []
    for plan in plans:
        out_path = out_dir / f"{plan.window_id}.jpg"
        try:
            extract_frame(tools, media_path, plan.sample_at_ms, out_path,
                          max_width=max_width, cfg=cfg)
        except MediaError as exc:
            # 单窗抽帧失败 => 该窗 LOW_QUALITY（显式原因），不影响其它窗口。
            raise FrameExtractionError(plan, exc.task_error) from exc
        frames.append(ExtractedFrame(plan=plan, path=out_path))
    return frames


class FrameExtractionError(Exception):
    def __init__(self, plan: FramePlan, task_error: TaskError):
        super().__init__(task_error.message)
        self.plan = plan
        self.task_error = task_error
