"""窗口计数模型与自校验（v0.2 Round 1）。

程序侧只接受**自洽**的计数：

```
head_up_count + head_down_count + unknown_count = visible_count
head_up_count + head_down_count                 = evaluable_count
```

不满足就**不得进入正式统计**（抛 `CountInconsistency`，由调用方触发现有重试或标为无效结果）。

比率一律由程序计算，模型只提供计数：

```
head_up_rate = head_up_count / evaluable_count      （evaluable_count = 0 时必须是 null，不是 0）
coverage     = evaluable_count / visible_count      （visible_count = 0 时 null）
```

`UNKNOWN` 既不计入分子也不计入分母（即不计入 UP，也不计入 DOWN）。
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any

METRIC_VERSION = "head-up-rate-v0.2"
SAMPLING_POLICY_VERSION = "10s-single-frame-v0.2"


class CountInconsistency(ValueError):
    """模型给出的计数不自洽（等式不成立或出现负数）。"""


@dataclass(frozen=True)
class WindowCounts:
    """一帧（或一次复核）的计数观测。"""

    visible_count: int
    head_up_count: int
    head_down_count: int
    unknown_count: int
    confidence: float = 0.0
    note: str = ""
    # 该计数是否来自"严格口径"提示词（要求模型给 visible/evaluable 自洽计数）
    strict: bool = True

    # -- 派生量（全部由程序计算，不由模型提供）------------------------------
    @property
    def evaluable_count(self) -> int:
        return self.head_up_count + self.head_down_count

    @property
    def head_up_rate(self) -> float | None:
        denominator = self.evaluable_count
        return (self.head_up_count / denominator) if denominator > 0 else None

    @property
    def coverage(self) -> float | None:
        return (self.evaluable_count / self.visible_count) if self.visible_count > 0 else None

    def to_dict(self) -> dict[str, Any]:
        return {
            "visible_count": self.visible_count,
            "evaluable_count": self.evaluable_count,
            "head_up_count": self.head_up_count,
            "head_down_count": self.head_down_count,
            "unknown_count": self.unknown_count,
            "head_up_rate": self.head_up_rate,
            "coverage": self.coverage,
            "confidence": self.confidence,
            "strict": self.strict,
        }


def validate_counts(*, visible_count: int, head_up_count: int, head_down_count: int,
                    unknown_count: int) -> None:
    """校验两条等式与取值范围；不合法抛 `CountInconsistency`。"""
    values = {"visible_count": visible_count, "head_up_count": head_up_count,
              "head_down_count": head_down_count, "unknown_count": unknown_count}
    for name, value in values.items():
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise CountInconsistency(f"{name} 必须是非负整数，实际为 {value!r}")
    parts = head_up_count + head_down_count + unknown_count
    if parts != visible_count:
        raise CountInconsistency(
            f"head_up_count + head_down_count + unknown_count = {parts}，"
            f"与 visible_count = {visible_count} 不一致")
    if parts == 0 and visible_count != 0:  # 已被上一行覆盖，保留语义说明
        raise CountInconsistency("计数全为 0 但 visible_count 不为 0")


def make_counts(*, visible_count: int, head_up_count: int, head_down_count: int,
                unknown_count: int, confidence: float = 0.0, note: str = "",
                strict: bool = True) -> WindowCounts:
    validate_counts(visible_count=visible_count, head_up_count=head_up_count,
                    head_down_count=head_down_count, unknown_count=unknown_count)
    if isinstance(confidence, bool) or not isinstance(confidence, (int, float)):
        confidence = 0.0
    return WindowCounts(visible_count=visible_count, head_up_count=head_up_count,
                        head_down_count=head_down_count, unknown_count=unknown_count,
                        confidence=min(1.0, max(0.0, float(confidence))),
                        note=str(note or "")[:200], strict=strict)


def from_legacy(*, head_up: int, head_down: int, unknown: int, confidence: float = 0.0,
                note: str = "") -> WindowCounts:
    """旧版三字段（UP/DOWN/UNKNOWN）→ 统一计数结构。

    旧版没有 `visible_count`，但三者之和**本就等于**画面中所有可见人物，因此语义等价；
    标记 `strict=False` 以便区分"没有要求自洽计数的那版提示词"的输出。
    """
    return make_counts(visible_count=int(head_up) + int(head_down) + int(unknown),
                       head_up_count=int(head_up), head_down_count=int(head_down),
                       unknown_count=int(unknown), confidence=confidence, note=note,
                       strict=False)


def aggregate_counts(readings: list[WindowCounts]) -> WindowCounts:
    """把多帧复核的计数相加成一个窗口估计（Round 3 用；本文件先提供确定性实现）。

    语义：**同一时间点附近窗口的多个观测帧求和**，不是"连续行为测量"。
    任何一帧不可信就整体不可信——因此调用方应先过滤掉失败帧。
    """
    if not readings:
        raise CountInconsistency("没有可聚合的观测帧")
    visible = sum(item.visible_count for item in readings)
    head_up = sum(item.head_up_count for item in readings)
    head_down = sum(item.head_down_count for item in readings)
    unknown = sum(item.unknown_count for item in readings)
    confidence = min((item.confidence for item in readings), default=0.0)
    notes = [item.note for item in readings if item.note]
    return make_counts(visible_count=visible, head_up_count=head_up, head_down_count=head_down,
                       unknown_count=unknown, confidence=confidence,
                       note=" | ".join(notes)[:200],
                       strict=all(item.strict for item in readings))


def unknown_ratio(counts: WindowCounts) -> float | None:
    """UNKNOWN 占比（用于 Round 3 的"UNKNOWN 过高"复核条件）。"""
    if counts.visible_count <= 0:
        return None
    return counts.unknown_count / counts.visible_count


def summarize(readings: dict[str, list[WindowCounts]]) -> dict[str, dict[str, float | None]]:
    """多帧复核的诊断汇总：每帧的计数、均值与离散度（Round 4 实验也用同一函数）。"""
    summary: dict[str, dict[str, float | None]] = {}
    for label, items in readings.items():
        if not items:
            continue
        for field in ("visible_count", "evaluable_count", "head_up_count"):
            values = [float(getattr(item, field)) for item in items]
            summary.setdefault(field, {})[label] = _mean(values)
            summary.setdefault(field + "_std", {})[label] = _std(values)
        rates = [item.head_up_rate for item in items]
        known = [value for value in rates if value is not None]
        summary.setdefault("head_up_rate", {})[label] = _mean(known) if known else None
        summary.setdefault("head_up_rate_std", {})[label] = _std(known) if known else None
    return summary


def _mean(values: list[float]) -> float | None:
    return (sum(values) / len(values)) if values else None


def _std(values: list[float]) -> float | None:
    if len(values) < 2:
        return 0.0 if values else None
    mean = sum(values) / len(values)
    return math.sqrt(sum((value - mean) ** 2 for value in values) / (len(values) - 1))
