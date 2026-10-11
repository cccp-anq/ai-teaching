"""Round 2（v0.2）三档质量与课程均值口径测试。

验收对照（用户 v0.2 Round 2）：
- coverage = 0.71 / 0.70 → VALID；0.60 / 0.50 → REVIEW；0.49 → LOW_QUALITY
- 不存在单一 0.6 阈值导致的大幅状态翻转（阈值全部配置化）
- REVIEW 与 LOW_QUALITY 都不参与课程均值
- 课程均值 = SUM(head_up_count)/SUM(evaluable_count)（不是窗口率平均、也不是人数当权重以外的东西）
- 窗口结果至少包含 head_up_rate / coverage / quality
"""

from __future__ import annotations

import unittest

from headup import wire
from headup.config import Config
from headup.counts import make_counts
from headup.pipeline import QualityDecision, classify_window, compute_stats

from test_contract import DEMO_CFG, _demo_payload
from test_qwen_client import valid_answer  # noqa: F401  （保持与本轮无关的用例可被发现）


def decide(visible: int, up: int, down: int, unknown: int, *, valid: float = 0.70,
           review: float = 0.50) -> QualityDecision:
    counts = make_counts(visible_count=visible, head_up_count=up, head_down_count=down,
                         unknown_count=unknown)
    return classify_window(counts, valid_threshold=valid, review_threshold=review)


class ThresholdBoundaryTest(unittest.TestCase):
    """阈值边界必须精确（左闭右开区间），且阈值来自配置。"""

    def test_coverage_0_71_is_valid(self) -> None:
        # visible=100, evaluable=71 ⇒ coverage=0.71
        decision = decide(100, 40, 31, 29)
        self.assertAlmostEqual(decision.coverage, 0.71)
        self.assertEqual(decision.quality, "VALID")
        self.assertEqual(decision.status, "VALID")
        self.assertIsNone(decision.reason)

    def test_coverage_0_70_is_valid(self) -> None:
        decision = decide(100, 40, 30, 30)
        self.assertAlmostEqual(decision.coverage, 0.70)
        self.assertEqual(decision.quality, "VALID")

    def test_coverage_0_60_is_review(self) -> None:
        decision = decide(100, 30, 30, 40)
        self.assertAlmostEqual(decision.coverage, 0.60)
        self.assertEqual(decision.quality, "REVIEW")
        self.assertEqual(decision.status, "REVIEW")
        self.assertEqual(decision.reason, "COVERAGE_IN_REVIEW_BAND")
        # REVIEW 仍然携带人数与比率（只是可观测性不足）
        self.assertEqual(decision.counts.head_up_count, 30)
        self.assertAlmostEqual(decision.head_up_rate, 0.5)

    def test_coverage_0_50_is_review(self) -> None:
        decision = decide(100, 25, 25, 50)
        self.assertAlmostEqual(decision.coverage, 0.50)
        self.assertEqual(decision.quality, "REVIEW")

    def test_coverage_0_49_is_low_quality(self) -> None:
        decision = decide(100, 25, 24, 51)
        self.assertAlmostEqual(decision.coverage, 0.49)
        self.assertEqual(decision.quality, "LOW_QUALITY")
        self.assertEqual(decision.status, "UNREADABLE")
        self.assertEqual(decision.reason, "COVERAGE_BELOW_REVIEW")
        # 不可读时不得输出人数
        self.assertIsNone(decision.counts)
        self.assertIsNone(decision.head_up_rate)

    def test_old_0_6_cliff_no_longer_flips_state(self) -> None:
        """v0.1 的 0.60 单阈值会让 0.599/0.600 在 VALID/LOW_QUALITY 间翻转。

        v0.2 下这两个值都落在 REVIEW 带内 ⇒ 状态相同，且都不进课程均值。
        """
        low = decide(1000, 300, 299, 401)     # coverage = 0.599
        high = decide(1000, 300, 300, 400)    # coverage = 0.600
        self.assertAlmostEqual(low.coverage, 0.599)
        self.assertAlmostEqual(high.coverage, 0.600)
        self.assertEqual(low.quality, high.quality, "0.599 与 0.600 不应翻转状态")
        self.assertEqual(low.quality, "REVIEW")

    def test_thresholds_come_from_config(self) -> None:
        """阈值必须可配置：改配置就能改变档位，且默认值为 0.70/0.50。"""
        cfg = Config()
        self.assertEqual(cfg.quality_valid_threshold, 0.70)
        self.assertEqual(cfg.quality_review_threshold, 0.50)
        counts = make_counts(visible_count=100, head_up_count=30, head_down_count=30,
                             unknown_count=40)   # coverage=0.60
        self.assertEqual(classify_window(counts, valid_threshold=0.70,
                                         review_threshold=0.50).quality, "REVIEW")
        # 把 valid 阈值下调到 0.55 ⇒ 同一个窗口变 VALID（证明没有硬编码）
        self.assertEqual(classify_window(counts, valid_threshold=0.55,
                                         review_threshold=0.50).quality, "VALID")
        # 把 review 阈值上调到 0.65 ⇒ 同一个窗口变 LOW_QUALITY
        self.assertEqual(classify_window(counts, valid_threshold=0.70,
                                         review_threshold=0.65).quality, "LOW_QUALITY")

    def test_review_band_is_configurable_and_validates_order(self) -> None:
        cfg = Config(quality_valid_threshold=0.8, quality_review_threshold=0.4)
        self.assertEqual(cfg.quality_valid_threshold, 0.8)
        counts = make_counts(visible_count=10, head_up_count=3, head_down_count=3,
                             unknown_count=4)   # coverage=0.6
        self.assertEqual(classify_window(counts, valid_threshold=0.8,
                                         review_threshold=0.4).quality, "REVIEW")


class NoSubjectsAndUnreadableTest(unittest.TestCase):
    def test_empty_room_is_no_subjects(self) -> None:
        decision = decide(0, 0, 0, 0)
        self.assertEqual(decision.quality, "NO_SUBJECTS")
        self.assertEqual(decision.status, "NO_DATA")
        self.assertEqual(decision.reason, "NO_VISIBLE_PERSON")
        self.assertIsNone(decision.head_up_rate)

    def test_all_back_of_head_is_low_quality_not_valid(self) -> None:
        """有人但一个都判断不了：绝不能算 VALID，也不能给 0% 抬头率。"""
        decision = decide(12, 0, 0, 12)
        self.assertEqual(decision.quality, "LOW_QUALITY")
        self.assertEqual(decision.status, "UNREADABLE")
        self.assertEqual(decision.reason, "UNREADABLE_ALL_SUBJECTS")
        self.assertIsNone(decision.head_up_rate)

    def test_analysis_failure_is_low_quality(self) -> None:
        decision = classify_window(None, valid_threshold=0.70, review_threshold=0.50)
        self.assertEqual(decision.quality, "LOW_QUALITY")
        self.assertEqual(decision.reason, "ANALYSIS_FAILED")


class PayloadAndWireCompatibilityTest(unittest.TestCase):
    """三档状态必须能进契约 payload，且旧样例仍然合法。"""

    def _payload(self, windows: list[dict]) -> dict:
        return {"valueMode": "COUNTS", "windows": windows,
                "processedRanges": [{"startMs": 0, "endMs": windows[-1]["endMs"]}]}

    def test_wire_accepts_review_with_counts(self) -> None:
        rows = [
            {"id": "win-001", "startMs": 0, "endMs": 10_000, "sampleAtMs": 5_000,
             "quality": "VALID", "qualityReason": None, "headUpCount": 8,
             "validObservedCount": 10, "headUpRatio": None},
            {"id": "win-002", "startMs": 10_000, "endMs": 20_000, "sampleAtMs": 15_000,
             "quality": wire.QUALITY_REVIEW, "qualityReason": "COVERAGE_IN_REVIEW_BAND",
             "headUpCount": 2, "validObservedCount": 10, "headUpRatio": None},
        ]
        wire.validate_windows(rows, bounds=(0, 20_000), window_ms=10_000,
                              processed=[(0, 20_000)], value_mode="COUNTS")

    def test_wire_still_rejects_review_without_reason(self) -> None:
        rows = [
            {"id": "win-001", "startMs": 0, "endMs": 10_000, "sampleAtMs": 5_000,
             "quality": "REVIEW", "qualityReason": None, "headUpCount": 2,
             "validObservedCount": 10, "headUpRatio": None},
        ]
        with self.assertRaises(ValueError):
            wire.validate_windows(rows, bounds=(0, 10_000), window_ms=10_000,
                                  processed=[(0, 10_000)], value_mode="COUNTS")

    def test_wire_still_rejects_review_with_null_counts(self) -> None:
        rows = [
            {"id": "win-001", "startMs": 0, "endMs": 10_000, "sampleAtMs": 5_000,
             "quality": "REVIEW", "qualityReason": "COVERAGE_IN_REVIEW_BAND",
             "headUpCount": None, "validObservedCount": None, "headUpRatio": None},
        ]
        with self.assertRaises(ValueError):
            wire.validate_windows(rows, bounds=(0, 10_000), window_ms=10_000,
                                  processed=[(0, 10_000)], value_mode="COUNTS")

    def test_frozen_demo_payload_still_all_valid_under_new_thresholds(self) -> None:
        """演示 fixture 的 30/40、24/40、20/40 覆盖率都是 0.75 > 0.70 ⇒ 仍全为 VALID。"""
        payload = _demo_payload("COUNTS")
        qualities = [w["quality"] for w in payload["windows"]]
        self.assertEqual(qualities,
                         ["VALID", "VALID", "VALID", "NO_SUBJECTS", "LOW_QUALITY", "VALID"])
        self.assertEqual(DEMO_CFG.quality_valid_threshold, 0.70)


class CourseMeanAggregationTest(unittest.TestCase):
    """课程均值：只含 VALID，且 = SUM(up)/SUM(evaluable)。"""

    def _payload(self, windows: list[dict]) -> dict:
        return {"valueMode": "COUNTS", "windows": windows,
                "processedRanges": [{"startMs": 0, "endMs": windows[-1]["endMs"]}]}

    def test_review_excluded_from_mean(self) -> None:
        payload = self._payload([
            {"id": "win-001", "startMs": 0, "endMs": 10_000, "quality": "VALID",
             "headUpCount": 8, "validObservedCount": 10, "headUpRatio": None},
            {"id": "win-002", "startMs": 10_000, "endMs": 20_000, "quality": "REVIEW",
             "headUpCount": 9, "validObservedCount": 10, "headUpRatio": None},
        ])
        stats = compute_stats(payload, 20_000)["headUp"]
        self.assertAlmostEqual(stats["meanRate"], 0.8)          # 只用 8/10
        self.assertEqual(stats["sumHeadUpCount"], 8)
        self.assertEqual(stats["sumEvaluableCount"], 10)
        self.assertEqual(stats["aggregatedWindowCount"], 1)

    def test_low_quality_excluded_from_mean(self) -> None:
        payload = self._payload([
            {"id": "win-001", "startMs": 0, "endMs": 10_000, "quality": "LOW_QUALITY",
             "headUpCount": None, "validObservedCount": None, "headUpRatio": None},
            {"id": "win-002", "startMs": 10_000, "endMs": 20_000, "quality": "VALID",
             "headUpCount": 3, "validObservedCount": 4, "headUpRatio": None},
        ])
        stats = compute_stats(payload, 20_000)["headUp"]
        self.assertAlmostEqual(stats["meanRate"], 0.75)
        self.assertEqual(stats["aggregatedWindowCount"], 1)

    def test_mean_is_sum_ratio_not_average_of_window_rates(self) -> None:
        """窗口分母不同时，SUM 比 与 窗口率算术平均 必须不同 —— 证明用的是 SUM 比。"""
        payload = self._payload([
            {"id": "win-001", "startMs": 0, "endMs": 10_000, "quality": "VALID",
             "headUpCount": 1, "validObservedCount": 2, "headUpRatio": None},     # 0.5
            {"id": "win-002", "startMs": 10_000, "endMs": 20_000, "quality": "VALID",
             "headUpCount": 9, "validObservedCount": 10, "headUpRatio": None},    # 0.9
        ])
        stats = compute_stats(payload, 20_000)["headUp"]
        self.assertAlmostEqual(stats["meanRate"], 10 / 12, places=12)     # SUM 比
        self.assertNotAlmostEqual(stats["meanRate"], (0.5 + 0.9) / 2, places=6)  # 不是率平均
        self.assertEqual(stats["sumHeadUpCount"], 10)
        self.assertEqual(stats["sumEvaluableCount"], 12)

    def test_valid_coverage_reported_alongside_mean(self) -> None:
        """避免"均值很精确但大部分课程不可测"：均值必须与覆盖率同时给出。"""
        payload = self._payload([
            {"id": "win-001", "startMs": 0, "endMs": 10_000, "quality": "VALID",
             "headUpCount": 5, "validObservedCount": 10, "headUpRatio": None},
            {"id": "win-002", "startMs": 10_000, "endMs": 20_000, "quality": "REVIEW",
             "headUpCount": 3, "validObservedCount": 10, "headUpRatio": None},
        ])
        stats = compute_stats(payload, 20_000)["headUp"]
        self.assertAlmostEqual(stats["validCoverage"], 0.5)      # 10s / 20s
        self.assertEqual(stats["aggregatedDurationMs"], 10_000)
        self.assertEqual(stats["qualityCounts"], {"VALID": 1, "REVIEW": 1})
        self.assertEqual(stats["windowRatesAll"], [0.5, 0.3])     # 诊断序列不丢信息

    def test_no_valid_window_gives_null_mean_with_reason(self) -> None:
        payload = self._payload([
            {"id": "win-001", "startMs": 0, "endMs": 10_000, "quality": "REVIEW",
             "headUpCount": 3, "validObservedCount": 10, "headUpRatio": None},
        ])
        stats = compute_stats(payload, 10_000)["headUp"]
        self.assertIsNone(stats["meanRate"])
        self.assertEqual(stats["aggregatedWindowCount"], 0)
        self.assertEqual(stats["validCoverage"], 0.0)


class RatioModeAggregationTest(unittest.TestCase):
    def test_ratio_mode_aggregates_valid_window_rates(self) -> None:
        payload = {"valueMode": "RATIO_ONLY", "processedRanges": [{"startMs": 0, "endMs": 20_000}],
                   "windows": [
                       {"id": "win-001", "startMs": 0, "endMs": 10_000, "quality": "VALID",
                        "headUpCount": None, "validObservedCount": None, "headUpRatio": 0.6},
                       {"id": "win-002", "startMs": 10_000, "endMs": 20_000, "quality": "REVIEW",
                        "headUpCount": None, "validObservedCount": None, "headUpRatio": 0.9},
                   ]}
        stats = compute_stats(payload, 20_000)["headUp"]
        self.assertAlmostEqual(stats["meanRate"], 0.6, places=12)
        self.assertIsNone(stats["sumHeadUpCount"])
        self.assertEqual(stats["aggregatedWindowCount"], 1)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
