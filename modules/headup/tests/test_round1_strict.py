"""Round 1（v0.2）严格观察口径测试。

验收对照（用户 v0.2 Round 1）：
- 后脑场景 → UP = 0
- 完全不可判断 → head_up_rate = null（不是 0）
- UNKNOWN 不计入 UP / DOWN
- 计数不一致 → 拒绝结果（触发现有重试），绝不进入统计
- head_up_rate 与 coverage 由程序计算（模型只给计数）
- 旧字段仍有兼容层（供未升级的调用方/历史结果复现）
"""

from __future__ import annotations

import unittest
from pathlib import Path

from headup import counts as counts_mod
from headup import prompts
from headup.analyze import QwenVlAnalyzer
from headup.config import Config
from headup.counts import CountInconsistency, from_legacy, make_counts
from headup.pipeline import classify_reading
from headup.wire import TaskError

from support import find_tools, temp_root
from test_qwen_client import (FakeDashScope, API_KEY, inconsistent_answer, make_frame,
                              qwen_config, valid_answer)


class ProgramComputesRatesTest(unittest.TestCase):
    """比率一律由程序算；模型给的数字不参与比率。"""

    def test_rate_and_coverage_from_counts(self) -> None:
        item = make_counts(visible_count=20, head_up_count=10, head_down_count=5,
                           unknown_count=5)
        self.assertEqual(item.evaluable_count, 15)
        self.assertAlmostEqual(item.head_up_rate, 10 / 15)
        self.assertAlmostEqual(item.coverage, 15 / 20)

    def test_zero_evaluable_gives_null_rate_not_zero(self) -> None:
        item = make_counts(visible_count=8, head_up_count=0, head_down_count=0, unknown_count=8)
        self.assertEqual(item.evaluable_count, 0)
        self.assertIsNone(item.head_up_rate)
        self.assertEqual(item.coverage, 0.0)

    def test_empty_frame_gives_null_coverage(self) -> None:
        item = make_counts(visible_count=0, head_up_count=0, head_down_count=0, unknown_count=0)
        self.assertIsNone(item.head_up_rate)
        self.assertIsNone(item.coverage)

    def test_unknown_never_counted_as_up_or_down(self) -> None:
        item = make_counts(visible_count=30, head_up_count=4, head_down_count=6,
                           unknown_count=20)
        self.assertEqual(item.head_up_count, 4)          # UNKNOWN 不进 UP
        self.assertEqual(item.head_down_count, 6)        # UNKNOWN 不进 DOWN
        self.assertEqual(item.evaluable_count, 10)       # 也不进分母
        self.assertAlmostEqual(item.head_up_rate, 0.4)
        self.assertAlmostEqual(item.coverage, 10 / 30)


class CountValidationTest(unittest.TestCase):
    def test_sum_mismatch_is_rejected(self) -> None:
        with self.assertRaises(CountInconsistency):
            make_counts(visible_count=21, head_up_count=10, head_down_count=5, unknown_count=5)

    def test_evaluable_is_derived_not_trusted(self) -> None:
        """模型若给出矛盾的 evaluable_count，以 up+down 为准（程序计算）。"""
        data = {"visible_count": 20, "evaluable_count": 999, "head_up_count": 10,
                "head_down_count": 5, "unknown_count": 5, "confidence": 0.8}
        item = QwenVlAnalyzer.to_counts(data, prompt_version=prompts.PROMPT_VERSION_STRICT)
        self.assertEqual(item.evaluable_count, 15)
        self.assertAlmostEqual(item.head_up_rate, 10 / 15)

    def test_negative_or_non_integer_is_rejected(self) -> None:
        for bad in ({"visible_count": -1, "head_up_count": 0, "head_down_count": 0,
                     "unknown_count": 0},
                    {"visible_count": 1.5, "head_up_count": 0, "head_down_count": 0,
                     "unknown_count": 1}):
            with self.assertRaises(CountInconsistency, msg=str(bad)):
                QwenVlAnalyzer.to_counts({**bad, "confidence": 0.5},
                                         prompt_version=prompts.PROMPT_VERSION_STRICT)

    def test_legacy_shape_is_accepted_as_compatibility_layer(self) -> None:
        """旧版三字段：visible 由三者之和补齐，且标记 strict=False 以便追溯。"""
        item = QwenVlAnalyzer.to_counts({"head_up": 3, "head_down": 2, "unknown": 5,
                                         "legible_ratio": 0.5, "confidence": 0.7},
                                        prompt_version=prompts.PROMPT_VERSION_V1)
        self.assertEqual(item.visible_count, 10)
        self.assertEqual(item.evaluable_count, 5)
        self.assertFalse(item.strict)
        self.assertFalse(from_legacy(head_up=1, head_down=1, unknown=1).strict)

    def test_legacy_shape_rejected_under_strict_prompt(self) -> None:
        """严格口径下模型退回旧字段 => 视为计数不自洽，必须重试。"""
        with self.assertRaises(CountInconsistency):
            QwenVlAnalyzer.to_counts({"head_up": 3, "head_down": 2, "unknown": 5},
                                     prompt_version=prompts.PROMPT_VERSION_STRICT)


@unittest.skipUnless(find_tools() is not None, "缺少 ffmpeg/ffprobe")
class StrictEndToEndTest(unittest.TestCase):
    """通过假模型服务验证：严格口径的完整解析 → 计数 → 程序比率 → 窗口质量。"""

    def setUp(self) -> None:
        self.root = temp_root("strict")
        self.frame = make_frame(self.root)

    def test_back_of_head_frame_yields_zero_up(self) -> None:
        """后脑场景：模型如实上报 up=0/down=0/unknown=12 ⇒ 抬头率 null，而不是 0。"""
        fake = FakeDashScope({"qwen3-vl-plus": [
            (200, valid_answer(head_up=0, head_down=0, unknown=12))]})
        try:
            reading = QwenVlAnalyzer(qwen_config(fake)).analyze(self.frame)
            self.assertEqual(reading.head_up, 0)
            self.assertEqual(reading.counts.evaluable_count, 0)
            self.assertIsNone(reading.head_up_rate)
            self.assertEqual(reading.coverage, 0.0)
            quality, reason, head_up, observed = classify_reading(reading, min_legible_ratio=0.6)
            self.assertEqual(quality, "LOW_QUALITY")
            self.assertEqual(reason, "NO_LEGIBLE_SUBJECTS")
            self.assertIsNone(head_up)
            self.assertIsNone(observed)
        finally:
            fake.close()

    def test_partial_visibility_gives_rate_and_coverage(self) -> None:
        fake = FakeDashScope({"qwen3-vl-plus": [
            (200, valid_answer(head_up=6, head_down=2, unknown=5))]})
        try:
            reading = QwenVlAnalyzer(qwen_config(fake)).analyze(self.frame)
            self.assertAlmostEqual(reading.head_up_rate, 6 / 8)
            self.assertAlmostEqual(reading.coverage, 8 / 13)
            quality, _reason, head_up, observed = classify_reading(reading, min_legible_ratio=0.6)
            self.assertEqual(quality, "VALID")
            self.assertEqual((head_up, observed), (6, 8))
        finally:
            fake.close()

    def test_inconsistent_counts_trigger_retry_then_fail_explicitly(self) -> None:
        """计数不自洽：重试仍不自洽 ⇒ 显式失败，绝不进入统计。"""
        fake = FakeDashScope({"qwen3-vl-plus": [(200, inconsistent_answer())],
                              "qwen3-vl-flash": [(200, inconsistent_answer())]})
        try:
            with self.assertRaises(TaskError) as ctx:
                QwenVlAnalyzer(qwen_config(fake)).analyze(self.frame)
            self.assertEqual(ctx.exception.code, "VISION_PROVIDER_ERROR")
            self.assertTrue(ctx.exception.retryable)
            self.assertGreaterEqual(fake.calls_by_model.get("qwen3-vl-plus", 0), 3)
            self.assertGreaterEqual(fake.calls_by_model.get("qwen3-vl-flash", 0), 1)
        finally:
            fake.close()

    def test_inconsistent_then_consistent_recovers(self) -> None:
        """第一次不自洽、重试后自洽 ⇒ 正常产出（证明重试是有效的而不是摆设）。"""
        fake = FakeDashScope({"qwen3-vl-plus": [
            (200, inconsistent_answer()),
            (200, valid_answer(head_up=6, head_down=2, unknown=5)),
        ]})
        try:
            reading = QwenVlAnalyzer(qwen_config(fake)).analyze(self.frame)
            self.assertEqual(reading.head_up, 6)
            self.assertEqual(fake.calls_by_model.get("qwen3-vl-plus"), 2)
        finally:
            fake.close()

    def test_counts_retry_extra_zero_means_no_extra_attempt(self) -> None:
        fake = FakeDashScope({"qwen3-vl-plus": [(200, inconsistent_answer())],
                              "qwen3-vl-flash": [(200, inconsistent_answer())]})
        try:
            cfg = qwen_config(fake, counts_retry_extra=0, vision_max_retries=0)
            with self.assertRaises(TaskError):
                QwenVlAnalyzer(cfg).analyze(self.frame)
            # 每个模型只尝试 1 次
            self.assertEqual(fake.calls_by_model.get("qwen3-vl-plus"), 1)
            self.assertEqual(fake.calls_by_model.get("qwen3-vl-flash"), 1)
        finally:
            fake.close()

    def test_analyzer_reports_prompt_version(self) -> None:
        fake = FakeDashScope({"qwen3-vl-plus": [(200, valid_answer())]})
        try:
            analyzer = QwenVlAnalyzer(qwen_config(fake))
            self.assertEqual(analyzer.prompt_version, prompts.PROMPT_VERSION_STRICT)
            self.assertEqual(analyzer.version, prompts.PROMPT_VERSION_STRICT)
        finally:
            fake.close()

    def test_unknown_prompt_version_is_rejected_at_construction(self) -> None:
        fake = FakeDashScope({"qwen3-vl-plus": [(200, valid_answer())]})
        try:
            with self.assertRaises(ValueError):
                QwenVlAnalyzer(qwen_config(fake, prompt_version="headup-does-not-exist"))
        finally:
            fake.close()


class PromptContentTest(unittest.TestCase):
    """严格口径提示词必须包含四条硬约束（防止被无意改回宽松版）。"""

    def test_strict_prompt_contains_required_phrases(self) -> None:
        text = prompts.USER_PROMPT_ATTENDANCE_V03
        for phrase in ("匿名统计可见学生", "排除讲台教师", "不做人脸或身份识别",
                       "宁可增加 UNKNOWN", "visible_count", "evaluable_count"):
            self.assertIn(phrase, text, phrase)

    def test_legacy_prompt_is_preserved(self) -> None:
        self.assertIn("legible_ratio", prompts.USER_PROMPT_V1)
        self.assertNotIn("visible_count", prompts.USER_PROMPT_V1)
        self.assertFalse(prompts.is_strict(prompts.PROMPT_VERSION_V1))
        self.assertTrue(prompts.is_strict(prompts.PROMPT_VERSION_STRICT))

    def test_default_prompt_is_strict(self) -> None:
        self.assertEqual(prompts.DEFAULT_PROMPT_VERSION, prompts.PROMPT_VERSION_STRICT)
        self.assertEqual(Config().prompt_version, prompts.PROMPT_VERSION_STRICT)
        self.assertTrue(Config().strict_counts)


class AggregationTest(unittest.TestCase):
    """多帧聚合（Round 3 复用）：求和语义 + 任一帧缺失即不可用。"""

    def test_aggregate_sums_counts_and_recomputes_rates(self) -> None:
        frames = [make_counts(visible_count=10, head_up_count=4, head_down_count=4,
                              unknown_count=2),
                  make_counts(visible_count=8, head_up_count=6, head_down_count=2,
                              unknown_count=0)]
        merged = counts_mod.aggregate_counts(frames)
        self.assertEqual((merged.visible_count, merged.head_up_count, merged.head_down_count,
                          merged.unknown_count), (18, 10, 6, 2))
        self.assertAlmostEqual(merged.head_up_rate, 10 / 16)
        self.assertAlmostEqual(merged.coverage, 16 / 18)
        self.assertTrue(merged.strict)

    def test_legacy_and_strict_mix_is_not_marked_strict(self) -> None:
        strict = make_counts(visible_count=4, head_up_count=2, head_down_count=2,
                             unknown_count=0)
        legacy = from_legacy(head_up=1, head_down=1, unknown=0)
        self.assertFalse(counts_mod.aggregate_counts([strict, legacy]).strict)

    def test_empty_aggregation_is_rejected(self) -> None:
        with self.assertRaises(CountInconsistency):
            counts_mod.aggregate_counts([])

    def test_unknown_ratio(self) -> None:
        item = make_counts(visible_count=10, head_up_count=2, head_down_count=3, unknown_count=5)
        self.assertAlmostEqual(counts_mod.unknown_ratio(item), 0.5)
        empty = make_counts(visible_count=0, head_up_count=0, head_down_count=0, unknown_count=0)
        self.assertIsNone(counts_mod.unknown_ratio(empty))


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
