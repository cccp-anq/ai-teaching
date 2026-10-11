"""质量判定与复核决策（FRAME-4 从 pipeline 迁出）。

职责（plan §12/§21）：把**观测**转成工程决策——
- 三档质量：`VALID` / `REVIEW` / `LOW_QUALITY`（外加"画面无人"的 `NO_SUBJECTS`）
- 是否需要三帧复核、复核触发原因
- 复核结论：确认 `VALID` 或诚实降级 `LOW_QUALITY`

不做：视觉判定（`vision/`）、抽帧与采样（`video/`）、课程聚合（`pipeline.compute_stats`）。

阈值全部来自 `Config`（`quality_valid_threshold` / `quality_review_threshold` /
`review_*`），本模块不含硬编码阈值。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .. import counts, wire
from ..config import Config
from ..counts import WindowCounts


@dataclass(frozen=True)
class QualityDecision:
    """三档质量判定结果（v0.2 Round 2）。"""

    quality: str                 # 契约枚举：VALID / REVIEW / LOW_QUALITY / NO_SUBJECTS
    reason: str | None           # 非 VALID 必填
    counts: WindowCounts | None  # 该窗口最终计数（LOW_QUALITY 时为 None）
    coverage: float | None
    head_up_rate: float | None
    status: str                  # 稳定状态名（V0-B 说明用）：VALID/REVIEW/NO_DATA/UNREADABLE
    model_quality: str           # 模型观测质量（不含阈值判定）：VALID/NO_SUBJECTS/UNREADABLE

    def to_dict(self) -> dict[str, Any]:
        return {
            "quality": self.quality,
            "qualityReason": self.reason,
            "status": self.status,
            "modelQuality": self.model_quality,
            "coverage": self.coverage,
            "headUpRate": self.head_up_rate,
            "counts": self.counts.to_dict() if self.counts else None,
        }


# 质量原因码（配置化阈值的判定结果，语义化便于主项目展示）
REASON_NO_VISIBLE_PERSON = "NO_VISIBLE_PERSON"          # 画面里没有人 ⇒ NO_SUBJECTS
REASON_UNREADABLE = "UNREADABLE_ALL_SUBJECTS"           # 有人但一个都判断不了 ⇒ 严重不可读
REASON_COVERAGE_BELOW_REVIEW = "COVERAGE_BELOW_REVIEW"  # coverage < review 阈值
REASON_COVERAGE_REVIEW_BAND = "COVERAGE_IN_REVIEW_BAND"  # review 阈值 <= coverage < valid 阈值
REASON_FRAME_EXTRACTION_FAILED = "FRAME_EXTRACTION_FAILED"
REASON_ANALYSIS_FAILED = "ANALYSIS_FAILED"
REASON_COUNTS_INCONSISTENT = "COUNTS_INCONSISTENT"

# ---- Round 3：复核触发原因与结论原因 -------------------------------------
REVIEW_REASON_QUALITY = "QUALITY_REVIEW"
REVIEW_REASON_UNKNOWN_HIGH = "UNKNOWN_RATIO_HIGH"
REVIEW_REASON_SUDDEN_CHANGE = "SUDDEN_CHANGE_VS_PREVIOUS_VALID"
REVIEW_REASON_CONFIG = "LOW_QUALITY_CONFIGURED"
REASON_REVIEW_CONFIRMED_VALID = "REVIEW_CONFIRMED_VALID"
REASON_REVIEW_INSUFFICIENT = "REVIEW_INSUFFICIENT_EVIDENCE"

REVIEW_OUTCOME_CONFIRMED_VALID = "CONFIRMED_VALID"
REVIEW_OUTCOME_INSUFFICIENT = "INSUFFICIENT_EVIDENCE"
REVIEW_OUTCOME_NOT_REVIEWED = "NOT_REVIEWED"


@dataclass(frozen=True)
class ReviewDecision:
    """是否需要复核 + 为什么（Round 3 §1）。"""

    needed: bool
    reasons: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return {"needed": self.needed, "reasons": list(self.reasons)}


def needs_review(*, counts: WindowCounts | None, coverage: float | None,
                 unknown_ratio: float | None, window_rate: float | None,
                 previous_valid_rate: float | None, cfg: Config,
                 status: str = "") -> ReviewDecision:
    """判定窗口是否需要三帧复核。

    触发条件（任一命中）：
    1. 三档结果为 REVIEW（`status == "REVIEW"`）；
    2. `unknown_ratio` 过高（默认 >= 0.5）；
    3. 与**前一有效窗口**的抬头率突变 >= 阈值（默认 0.20，配置化）；
    4. 显式开启 `review_on_low_quality` 时，LOW_QUALITY 也复核（默认关闭，避免扩大调用量）。
    """
    if not cfg.review_enabled or counts is None:
        return ReviewDecision(False, ())
    reasons: list[str] = []
    if status == "REVIEW":
        reasons.append(REVIEW_REASON_QUALITY)
    if unknown_ratio is not None and unknown_ratio >= cfg.review_unknown_ratio_threshold:
        reasons.append(REVIEW_REASON_UNKNOWN_HIGH)
    if (window_rate is not None and previous_valid_rate is not None
            and abs(window_rate - previous_valid_rate) >= cfg.review_sudden_change_threshold):
        reasons.append(REVIEW_REASON_SUDDEN_CHANGE)
    if cfg.review_on_low_quality and status == "UNREADABLE":
        reasons.append(REVIEW_REASON_CONFIG)
    return ReviewDecision(bool(reasons), tuple(reasons))


def _median(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / 2


def select_estimate(readings: list[WindowCounts]) -> WindowCounts:
    """从多帧观测中选**代表观测**（中位数帧），保持"单帧语义"。

    为什么不是求和：求和会把分母变成"人·帧"，语义从"学生里多少人抬头"漂移成
    "学生观测里多少人次抬头"，且与 sum_ratio 聚合口径不同源。中位数帧既稳健又不改语义。
    """
    if not readings:
        raise ValueError("没有可用的观测帧")
    if len(readings) == 1:
        return readings[0]
    rates = [item.head_up_rate for item in readings if item.head_up_rate is not None]
    target = _median(rates)
    candidates = readings
    if target is not None:
        candidates = [item for item in readings if item.head_up_rate is not None]
        if not candidates:
            candidates = readings
    return min(candidates,
               key=lambda item: abs((item.head_up_rate if item.head_up_rate is not None else -1)
                                    - (target if target is not None else -1)))


def finalize_review(primary: WindowCounts, extra: list[WindowCounts], *,
                    valid_threshold: float, review_threshold: float) -> tuple[QualityDecision, dict[str, Any]]:
    """三帧复核结论：中位数聚合 → VALID 或 LOW_QUALITY（不再停留在 REVIEW）。

    返回 (最终质量判定, 复核证据)。**不会**用时间连续性去推断姿态：三帧都不行就是不可读。
    """
    readings = [primary, *extra]
    rates = [item.head_up_rate for item in readings]
    coverages = [item.coverage for item in readings if item.coverage is not None]
    unknowns = [counts.unknown_ratio(item) for item in readings]
    median_rate = _median([value for value in rates if value is not None])
    median_coverage = _median([float(value) for value in coverages])
    selected = select_estimate(readings)
    evidence = {
        "samplingPolicyVersion": counts.SAMPLING_POLICY_VERSION,
        "frameCount": len(readings),
        "perFrame": [item.to_dict() for item in readings],
        "medianHeadUpRate": median_rate,
        "medianCoverage": median_coverage,
        "medianUnknownRatio": _median([value for value in unknowns if value is not None]),
        "selectedFrameIndex": readings.index(selected),
        "selectedHeadUpRate": selected.head_up_rate,
        "selectedCoverage": selected.coverage,
    }
    if selected.evaluable_count == 0 or median_coverage is None:
        decision = QualityDecision(wire.QUALITY_LOW, REASON_REVIEW_INSUFFICIENT, None,
                                   median_coverage, None, "UNREADABLE", "UNREADABLE")
        evidence["outcome"] = REVIEW_OUTCOME_INSUFFICIENT
        return decision, evidence
    if median_coverage >= float(valid_threshold):
        decision = QualityDecision(wire.QUALITY_VALID, None, selected, selected.coverage,
                                   selected.head_up_rate, "VALID", "VALID")
        evidence["outcome"] = REVIEW_OUTCOME_CONFIRMED_VALID
        return decision, evidence
    # 复核之后仍达不到 VALID：三帧都没能补足证据 ⇒ 诚实降级，不留在 REVIEW
    decision = QualityDecision(wire.QUALITY_LOW, REASON_REVIEW_INSUFFICIENT, None,
                               selected.coverage, None, "UNREADABLE", "VALID")
    evidence["outcome"] = REVIEW_OUTCOME_INSUFFICIENT
    return decision, evidence


def finalize_review_observation(observation: WindowCounts, *, valid_threshold: float,
                                review_threshold: float,
                                frame_count: int = 1) -> tuple[QualityDecision, dict[str, Any]]:
    """多图复核结论（FRAME-4 / 用户决策③）。

    三张图在**一次请求**里已经产出一个窗口观测，因此这里直接对该观测判级，
    不再做"多帧中位数"聚合（那是逐帧单图路径的处理方式）：

    - `coverage >= valid 阈值` ⇒ `VALID`（`REVIEW_CONFIRMED_VALID`）
    - 否则 ⇒ `LOW_QUALITY`（`REVIEW_INSUFFICIENT_EVIDENCE`，两数 null），绝不留在 REVIEW
    """
    decision = classify_window(observation, valid_threshold=valid_threshold,
                               review_threshold=review_threshold)
    evidence: dict[str, Any] = {
        "frameCount": frame_count,
        "observation": observation.to_dict(),
        "medianCoverage": observation.coverage,
        "medianHeadUpRate": observation.head_up_rate,
        "samplingPolicyVersion": counts.SAMPLING_POLICY_VERSION,
    }
    if decision.quality == wire.QUALITY_VALID:
        evidence["outcome"] = REVIEW_OUTCOME_CONFIRMED_VALID
        return decision, evidence
    evidence["outcome"] = REVIEW_OUTCOME_INSUFFICIENT
    return (QualityDecision(wire.QUALITY_LOW, REASON_REVIEW_INSUFFICIENT, None,
                            observation.coverage, None, "UNREADABLE", "VALID"),
            evidence)


def _classify_by_coverage(coverage: float | None, counts: WindowCounts, *,
                          valid_threshold: float, review_threshold: float) -> QualityDecision:
    if coverage is None:
        return QualityDecision(wire.QUALITY_LOW, REASON_UNREADABLE, None, None, None,
                              "UNREADABLE", "UNREADABLE")
    if coverage >= float(valid_threshold):
        return QualityDecision(wire.QUALITY_VALID, None, counts, coverage, counts.head_up_rate,
                               "VALID", "VALID")
    if coverage >= float(review_threshold):
        return QualityDecision(wire.QUALITY_REVIEW, REASON_COVERAGE_REVIEW_BAND, counts, coverage,
                               counts.head_up_rate, "REVIEW", "VALID")
    return QualityDecision(wire.QUALITY_LOW, REASON_COVERAGE_BELOW_REVIEW, None, coverage, None,
                           "UNREADABLE", "VALID")


def classify_window(counts: WindowCounts | None, *, valid_threshold: float,
                    review_threshold: float, coverage_override: float | None = None) -> QualityDecision:
    """按 **coverage**（= evaluable/visible）做三档判定。

    | 条件 | quality | status | 谁进课程均值 |
    | --- | --- | --- | --- |
    | 画面无人（visible=0） | `NO_SUBJECTS` | NO_DATA | 否 |
    | 有人但 evaluable=0 | `LOW_QUALITY` | UNREADABLE | 否 |
    | coverage >= valid 阈值 | `VALID` | VALID | **是** |
    | review 阈值 <= coverage < valid 阈值 | `REVIEW` | REVIEW | 否（待 Round 3 复核） |
    | coverage < review 阈值 | `LOW_QUALITY` | UNREADABLE | 否 |
    """
    if counts is None:
        return QualityDecision(wire.QUALITY_LOW, REASON_ANALYSIS_FAILED, None, None, None,
                               "UNREADABLE", "UNREADABLE")
    coverage = counts.coverage if coverage_override is None else coverage_override
    if counts.visible_count == 0:
        return QualityDecision(wire.QUALITY_NO_SUBJECTS, REASON_NO_VISIBLE_PERSON, counts,
                               counts.coverage, None, "NO_DATA", "NO_SUBJECTS")
    if counts.evaluable_count == 0:
        return QualityDecision(wire.QUALITY_LOW, REASON_UNREADABLE, None, counts.coverage, None,
                               "UNREADABLE", "UNREADABLE")
    return _classify_by_coverage(coverage, counts, valid_threshold=valid_threshold,
                                 review_threshold=review_threshold)
