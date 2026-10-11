"""视觉观测的数据结构（FRAME-3）。

`FrameReading` = 一次（或一组）图片的**原始观测**：
- 自洽计数（`WindowCounts`）
- 程序计算的 `head_up_rate` / `coverage`
- 追溯信息（用了哪个模型、哪次请求）

它**不含**质量分档（`VALID/REVIEW/LOW_QUALITY`）——那是 `analysis/quality` 的职责，
这样"观测"与"判级"可以分别演进、分别测试。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from ..counts import WindowCounts, from_legacy




@dataclass
class FrameReading:
    head_up: int
    head_down: int
    unknown: int
    legible_ratio: float
    confidence: float
    analysis_notes: list[str] = field(default_factory=list)
    raw: str = ""
    # v0.2：程序侧的统一计数模型（含 visible/evaluable 与程序计算的 rate/coverage）。
    # 旧构造点不传该字段也能工作，此处按旧三字段补齐，保证向后兼容。
    counts: WindowCounts | None = None
    # Round 4：追溯用——本帧实际由哪个模型判定、该次请求的 id 与生效参数
    model_used: str | None = None
    trace: dict[str, Any] = field(default_factory=dict)
    # FRAME-4：本观测由几帧组成（1 = 单帧；>1 = 多帧窗口复核）
    frame_count: int = 1
    # FRAME-4：多帧观测时的逐帧证据（每帧的计数），便于审计
    per_frame: list[dict[str, Any]] = field(default_factory=list)

    def __post_init__(self) -> None:
        if self.counts is None:
            self.counts = from_legacy(head_up=int(self.head_up), head_down=int(self.head_down),
                                      unknown=int(self.unknown),
                                      confidence=float(self.confidence))

    @property
    def visible_count(self) -> int:
        return self.counts.visible_count if self.counts else 0

    @property
    def evaluable_count(self) -> int:
        return self.counts.evaluable_count if self.counts else 0

    @property
    def head_up_rate(self) -> float | None:
        return self.counts.head_up_rate if self.counts else None

    @property
    def coverage(self) -> float | None:
        return self.counts.coverage if self.counts else None

    @property
    def counts_strict(self) -> bool:
        return bool(self.counts.strict) if self.counts else False

    @property
    def is_multi_frame(self) -> bool:
        return self.frame_count > 1


class FrameAnalyzer(Protocol):
    """视觉判定接口（可替换：Qwen-VL-Flash / Plus / 本地 Qwen / 其他 VLM）。"""

    name: str
    version: str

    def analyze(self, image_path: Path) -> FrameReading:  # pragma: no cover - 协议
        ...
