"""观测自校验（FRAME-3）。

把"一次观测是否可进入统计"的判定集中到一处（plan §12 的 `HeadUpObservationValidator`）：

1. **计数自洽**：`up + down + unknown = visible`、`up + down = evaluable`（由 `counts` 保证）；
2. **严格口径**：严格提示词下不接受旧版三字段返回（必须给 `visible_count`）；
3. **可比性**：`visible_count = 0`（画面无人）与 `evaluable_count = 0`（有人但不可判读）
   是两种不同状态，校验层要能区分；
4. **不做质量分档**：判级属于 `analysis/quality`。

校验失败抛 `ObservationInvalid`，调用方按"可重试/标记无效"处理，**绝不进入统计**。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from ..counts import WindowCounts, unknown_ratio
from .schema import FrameReading

OUTCOME_OK = "OK"
OUTCOME_NO_VISIBLE_PERSON = "NO_VISIBLE_PERSON"
OUTCOME_UNREADABLE = "UNREADABLE_ALL_SUBJECTS"


class ObservationInvalid(ValueError):
    """观测不合法（缺字段、计数不自洽、口径不符）。"""


@dataclass(frozen=True)
class ObservationValidator:
    """校验策略（阈值可配，便于按部署调整）。"""

    require_strict_counts: bool = True
    min_visible_for_readable: int = 1

    def validate(self, reading: FrameReading) -> dict[str, Any]:
        counts = reading.counts
        if counts is None:
            raise ObservationInvalid("观测缺少计数字段")
        if self.require_strict_counts and not counts.strict:
            raise ObservationInvalid("严格口径下不接受旧版三字段计数（missing visible_count）")
        if counts.visible_count < 0 or counts.head_up_count < 0 or counts.head_down_count < 0 \
                or counts.unknown_count < 0:
            raise ObservationInvalid("计数出现负数")
        parts = counts.head_up_count + counts.head_down_count + counts.unknown_count
        if parts != counts.visible_count:
            raise ObservationInvalid(
                f"计数不自洽：{parts} != visible {counts.visible_count}")
        if counts.visible_count == 0:
            outcome = OUTCOME_NO_VISIBLE_PERSON
        elif counts.evaluable_count == 0:
            outcome = OUTCOME_UNREADABLE
        else:
            outcome = OUTCOME_OK
        return {
            "outcome": outcome,
            "counts": counts.to_dict(),
            "unknownRatio": unknown_ratio(counts),
            "readable": outcome == OUTCOME_OK,
        }


def validate_observation(reading: FrameReading, *,
                         require_strict_counts: bool = True) -> dict[str, Any]:
    """便捷入口：校验一次观测，返回校验结论（不合法则抛 `ObservationInvalid`）。"""
    return ObservationValidator(require_strict_counts=require_strict_counts).validate(reading)


def is_statistically_usable(reading: FrameReading) -> bool:
    """该观测能否进入统计：必须可读（evaluable > 0）且严格计数。"""
    counts: WindowCounts | None = reading.counts
    if counts is None or not counts.strict:
        return False
    return counts.evaluable_count > 0
