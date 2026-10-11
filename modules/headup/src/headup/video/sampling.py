"""采样策略（FRAME-2 的网格来源）。

用户决策（必须遵守）：

1. **采样网格只由 `SamplingPolicy` 生成**，FFmpeg 不得自行定义网格；
2. 网格沿用现有窗口中点语义：`windowMs = 10000` ⇒ `5s / 15s / 25s / …`；
3. `startOffsetMs` / `endOffsetMs` **只过滤已有采样点，不得平移或重新生成网格**；
4. v0.2 默认 `startOffset = endOffset = 0`；未来启用非零 offset 必须提升
   `sampling_policy_version`。

因此本模块是纯函数式的时间计算，不接触 ffmpeg；抽帧层只负责"按给定时间点取帧"。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable

#: 采样策略版本：非零 offset 或改变采样位置时必须升版
SAMPLING_POLICY_VERSION_V02 = "10s-window-midpoint-v0.2"

SAMPLE_POSITION_START = "start"
SAMPLE_POSITION_MIDDLE = "middle"
SAMPLE_POSITION_END = "end"


@dataclass(frozen=True)
class SamplingPoint:
    """一个采样点：属于哪个窗口、在什么时间点取帧。"""

    index: int
    window_id: str
    window_start_ms: int
    window_end_ms: int
    sample_at_ms: int

    def to_dict(self) -> dict[str, int | str]:
        return {
            "index": self.index,
            "windowId": self.window_id,
            "windowStartMs": self.window_start_ms,
            "windowEndMs": self.window_end_ms,
            "sampleAtMs": self.sample_at_ms,
        }


@dataclass(frozen=True)
class SamplingPolicy:
    """采样策略：唯一的网格来源。"""

    window_ms: int = 10_000
    position: str = SAMPLE_POSITION_MIDDLE
    start_offset_ms: int = 0
    end_offset_ms: int = 0
    policy_version: str = SAMPLING_POLICY_VERSION_V02

    # -- 网格 -------------------------------------------------------------
    def grid(self, analysis_start_ms: int, analysis_end_ms: int) -> list[SamplingPoint]:
        """生成完整网格（未应用 offset）。

        窗口左闭右开、末窗可短；采样点仍落在窗口内（与冻结样例 `sampleAtMs` 语义一致）。
        """
        if self.window_ms <= 0:
            raise ValueError("windowMs 必须为正整数")
        if analysis_end_ms <= analysis_start_ms:
            raise ValueError("analysisRange 必须满足 startMs < endMs")
        if self.position not in (SAMPLE_POSITION_START, SAMPLE_POSITION_MIDDLE,
                                 SAMPLE_POSITION_END):
            raise ValueError(f"未知的采样位置: {self.position!r}")
        points: list[SamplingPoint] = []
        cursor = int(analysis_start_ms)
        index = 0
        while cursor < int(analysis_end_ms):
            stop = min(cursor + int(self.window_ms), int(analysis_end_ms))
            length = stop - cursor
            if self.position == SAMPLE_POSITION_START:
                sample = cursor
            elif self.position == SAMPLE_POSITION_END:
                sample = stop - 1
            else:
                sample = cursor + length // 2
            points.append(SamplingPoint(index=index, window_id=f"win-{index + 1:03d}",
                                        window_start_ms=cursor, window_end_ms=stop,
                                        sample_at_ms=sample))
            cursor = stop
            index += 1
        return points

    # -- offset 过滤（不平移）---------------------------------------------
    def apply_offsets(self, points: Iterable[SamplingPoint], *,
                      duration_ms: int) -> tuple[list[SamplingPoint], dict[str, object]]:
        """按 offset **过滤**采样点；保留的点时间戳不变，窗口编号保持原样。

        返回 (保留的点, 过滤说明)。窗口 id 不重排——重排会让"第 N 窗"在两次运行间漂移。
        """
        all_points = list(points)
        if self.start_offset_ms <= 0 and self.end_offset_ms <= 0:
            return all_points, {"filteredOut": [], "startOffsetMs": self.start_offset_ms,
                                "endOffsetMs": self.end_offset_ms, "droppedCount": 0}
        lower = max(0, int(self.start_offset_ms))
        upper = int(duration_ms) - max(0, int(self.end_offset_ms))
        kept: list[SamplingPoint] = []
        dropped: list[str] = []
        for point in all_points:
            if point.sample_at_ms < lower or point.sample_at_ms > upper:
                dropped.append(point.window_id)
            else:
                kept.append(point)
        return kept, {
            "filteredOut": dropped,
            "droppedCount": len(dropped),
            "startOffsetMs": self.start_offset_ms,
            "endOffsetMs": self.end_offset_ms,
            "keptCount": len(kept),
        }

    # -- 便捷 -------------------------------------------------------------
    def plan(self, analysis_start_ms: int, analysis_end_ms: int, *,
             duration_ms: int | None = None) -> tuple[list[SamplingPoint], dict[str, object]]:
        points = self.grid(analysis_start_ms, analysis_end_ms)
        return self.apply_offsets(points, duration_ms=duration_ms
                                  if duration_ms is not None else analysis_end_ms)

    def effective_policy_version(self) -> str:
        """启用非零 offset 时必须在版本里体现（用户决策②）。"""
        if self.start_offset_ms or self.end_offset_ms:
            return (f"{self.policy_version}+offset-{self.start_offset_ms}-{self.end_offset_ms}")
        return self.policy_version


@dataclass
class SamplingPlan:
    """一次分析的采样计划（可 JSON 化，进任务记录）。"""

    points: list[SamplingPoint] = field(default_factory=list)
    filter_note: dict[str, object] = field(default_factory=dict)
    policy_version: str = SAMPLING_POLICY_VERSION_V02
    window_ms: int = 10_000
    position: str = SAMPLE_POSITION_MIDDLE

    def timestamps(self) -> list[int]:
        return [point.sample_at_ms for point in self.points]

    def to_dict(self) -> dict[str, object]:
        return {
            "policyVersion": self.policy_version,
            "windowMs": self.window_ms,
            "position": self.position,
            "sampleCount": len(self.points),
            "filter": self.filter_note,
            "points": [point.to_dict() for point in self.points],
        }


def build_sampling_plan(policy: SamplingPolicy, *, analysis_start_ms: int,
                        analysis_end_ms: int, duration_ms: int | None = None) -> SamplingPlan:
    points, note = policy.plan(analysis_start_ms, analysis_end_ms, duration_ms=duration_ms)
    return SamplingPlan(points=points, filter_note=note,
                        policy_version=policy.effective_policy_version(),
                        window_ms=policy.window_ms, position=policy.position)


def plan_frame_plans(policy: SamplingPolicy, *, start_ms: int, end_ms: int):
    """生成与旧 `frames.plan_frames()` **逐字段等价**的窗口计划。

    契约要求每个窗口带 `sampleAtMs`；本函数把 SamplingPolicy 的网格翻译成窗口计划，
    从而让"网格唯一来源"这条约束落到实际流水线里（而不是只存在于测试）。
    """
    from ..frames import FramePlan

    return [
        FramePlan(window_index=point.index, window_id=point.window_id,
                  window_start_ms=point.window_start_ms, window_end_ms=point.window_end_ms,
                  sample_at_ms=point.sample_at_ms)
        for point in policy.grid(start_ms, end_ms)
    ]
