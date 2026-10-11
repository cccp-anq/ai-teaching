"""流水线与窗口逻辑测试：抽帧计划、时间映射、质量分类、统计、分块、图像预处理。

全部离线（不下载、不调用模型），ffmpeg 只用于可选的真实抽帧用例。
"""

from __future__ import annotations

import unittest
from pathlib import Path

from headup import wire
from headup.analyze import (FrameReading, HeuristicAnalyzer, UnavailableAnalyzer, prepare_image,
                            tiled_boxes)
from headup.config import Config
from headup.frames import plan_frames, time_mapper
from headup.pipeline import classify_reading, compute_stats

from support import SYNTHETIC_MEDIA, find_tools, temp_root


class FramePlanTest(unittest.TestCase):
    def test_windows_are_left_closed_right_open(self) -> None:
        plans = plan_frames(start_ms=0, end_ms=60_000, window_ms=10_000)
        self.assertEqual([(p.window_start_ms, p.window_end_ms) for p in plans],
                         [(0, 10_000), (10_000, 20_000), (20_000, 30_000),
                          (30_000, 40_000), (40_000, 50_000), (50_000, 60_000)])
        self.assertEqual([p.window_id for p in plans],
                         ["win-001", "win-002", "win-003", "win-004", "win-005", "win-006"])

    def test_short_last_window(self) -> None:
        plans = plan_frames(start_ms=0, end_ms=25_000, window_ms=10_000)
        self.assertEqual((plans[-1].window_start_ms, plans[-1].window_end_ms), (20_000, 25_000))
        self.assertTrue(plans[-1].window_start_ms <= plans[-1].sample_at_ms < plans[-1].window_end_ms)

    def test_sample_position_is_configurable(self) -> None:
        middle = plan_frames(start_ms=0, end_ms=10_000, window_ms=10_000, position="middle")[0]
        start = plan_frames(start_ms=0, end_ms=10_000, window_ms=10_000, position="start")[0]
        end = plan_frames(start_ms=0, end_ms=10_000, window_ms=10_000, position="end")[0]
        self.assertEqual(middle.sample_at_ms, 5_000)
        self.assertEqual(start.sample_at_ms, 0)
        self.assertEqual(end.sample_at_ms, 9_999)

    def test_sample_never_leaves_the_window(self) -> None:
        for start, end in ((0, 60_000), (3_000, 47_000), (1_000, 2_000)):
            for position in ("start", "middle", "end"):
                for plan in plan_frames(start_ms=start, end_ms=end, window_ms=7_000,
                                        position=position):
                    self.assertTrue(plan.window_start_ms <= plan.sample_at_ms < plan.window_end_ms,
                                    f"{position} {plan}")

    def test_time_mapper_is_the_only_offset_point(self) -> None:
        """整条媒体处理时 offset=0；将来支持裁剪只改这里。"""
        self.assertEqual(time_mapper(0)(12_345), 12_345)
        self.assertEqual(time_mapper(10_000)(2_000), 12_000)
        plans = plan_frames(start_ms=10_000, end_ms=20_000, window_ms=10_000, offset_ms=0)
        self.assertEqual(plans[0].sample_at_ms, 15_000)

    def test_rejects_unknown_sample_position(self) -> None:
        with self.assertRaises(ValueError):
            plan_frames(start_ms=0, end_ms=1_000, window_ms=1_000, position="random")


class QualityClassificationTest(unittest.TestCase):
    def test_valid_reading(self) -> None:
        quality, reason, head_up, observed = classify_reading(
            FrameReading(head_up=30, head_down=10, unknown=4, legible_ratio=0.9, confidence=0.8),
            min_legible_ratio=0.6)
        self.assertEqual((quality, reason, head_up, observed), ("VALID", None, 30, 40))

    def test_true_zero_is_valid_not_missing(self) -> None:
        quality, reason, head_up, observed = classify_reading(
            FrameReading(head_up=0, head_down=40, unknown=0, legible_ratio=1.0, confidence=0.9),
            min_legible_ratio=0.6)
        self.assertEqual((quality, head_up, observed), ("VALID", 0, 40))

    def test_empty_room_is_no_subjects(self) -> None:
        quality, reason, head_up, observed = classify_reading(
            FrameReading(head_up=0, head_down=0, unknown=0, legible_ratio=0.0, confidence=0.9),
            min_legible_ratio=0.6)
        self.assertEqual((quality, reason, head_up, observed),
                         ("NO_SUBJECTS", "NO_OBSERVABLE_SUBJECTS", 0, 0))

    def test_everyone_occluded_is_low_quality(self) -> None:
        quality, reason, head_up, observed = classify_reading(
            FrameReading(head_up=0, head_down=0, unknown=12, legible_ratio=0.0, confidence=0.4),
            min_legible_ratio=0.6)
        self.assertEqual((quality, reason, head_up, observed),
                         ("LOW_QUALITY", "NO_LEGIBLE_SUBJECTS", None, None))

    def test_low_legible_ratio_is_low_quality(self) -> None:
        quality, reason, _head_up, _observed = classify_reading(
            FrameReading(head_up=3, head_down=1, unknown=30, legible_ratio=0.12, confidence=0.5),
            min_legible_ratio=0.6)
        self.assertEqual((quality, reason), ("LOW_QUALITY", "LOW_LEGIBLE_RATIO"))

    def test_numerator_is_clamped_to_denominator(self) -> None:
        _quality, _reason, head_up, observed = classify_reading(
            FrameReading(head_up=50, head_down=2, unknown=0, legible_ratio=1.0, confidence=0.9),
            min_legible_ratio=0.6)
        self.assertLessEqual(head_up, observed)


class AnalyzerBehaviourTest(unittest.TestCase):
    def test_unavailable_analyzer_never_invents_numbers(self) -> None:
        reading = UnavailableAnalyzer("没有凭证").analyze(Path("nope.jpg"))
        self.assertEqual((reading.head_up, reading.head_down, reading.unknown), (0, 0, 0))
        quality, reason, head_up, observed = classify_reading(reading, min_legible_ratio=0.6)
        self.assertEqual((quality, reason, head_up, observed),
                         ("NO_SUBJECTS", "NO_OBSERVABLE_SUBJECTS", 0, 0))

    def test_heuristic_analyzer_degrades_without_counts(self) -> None:
        reading = HeuristicAnalyzer().analyze(Path("win-001.jpg"))
        self.assertEqual((reading.head_up, reading.head_down), (0, 0))
        self.assertTrue(reading.analysis_notes)

    def test_tiled_boxes_cover_the_frame(self) -> None:
        boxes = tiled_boxes(1000, 600, "3x2", 0.1)
        self.assertEqual(len(boxes), 6)
        self.assertEqual(boxes[0][:2], (0, 0))
        self.assertEqual(boxes[-1][2:], (1000, 600))
        self.assertEqual(tiled_boxes(1000, 600, "1x1", 0.1), [(0, 0, 1000, 600)])

    def test_prepare_image_downscales_and_bounds_size(self) -> None:
        if not SYNTHETIC_MEDIA.is_file():
            self.skipTest("缺少合成媒体")
        tools = find_tools()
        if tools is None:
            self.skipTest("缺少 ffmpeg")
        from headup.media import FfmpegTools, extract_frame

        root = temp_root("headup-prep-")
        out = root / "frame.jpg"
        cfg = Config(ffmpeg_path=tools[0], ffprobe_path=tools[1], media_probe_timeout_s=60)
        extract_frame(FfmpegTools.locate(cfg), SYNTHETIC_MEDIA, 5_000, out, max_width=1600, cfg=cfg)
        data_url, meta = prepare_image(out, cfg=Config(vision_max_long_side=320,
                                                      vision_max_image_bytes=200_000))
        self.assertTrue(data_url.startswith("data:image/jpeg;base64,"))
        self.assertLessEqual(max(meta["width"], meta["height"]), 320)
        self.assertLessEqual(meta["bytes"], 200_000)


class StatsTest(unittest.TestCase):
    def _payload(self, windows: list[dict], value_mode: str = "COUNTS") -> dict:
        return {"valueMode": value_mode, "windows": windows,
                "processedRanges": [{"startMs": 0, "endMs": windows[-1]["endMs"]}]}

    def test_no_valid_window_yields_null_mean(self) -> None:
        payload = self._payload([
            {"id": "win-001", "startMs": 0, "endMs": 10_000, "quality": "LOW_QUALITY",
             "headUpCount": None, "validObservedCount": None, "headUpRatio": None},
            {"id": "win-002", "startMs": 10_000, "endMs": 20_000, "quality": "NO_SUBJECTS",
             "headUpCount": 0, "validObservedCount": 0, "headUpRatio": None},
        ])
        stats = compute_stats(payload, 20_000)
        self.assertIsNone(stats["headUp"]["meanRate"])
        self.assertEqual(stats["headUp"]["validDurationMs"], 0)
        self.assertEqual(stats["headUp"]["processedCoverage"], 1.0)
        self.assertEqual(stats["headUp"]["windowRates"], [None, None])
        self.assertEqual(stats["headUp"]["changes"], [])

    def test_unequal_window_lengths_are_count_aggregated(self) -> None:
        """v0.2 Round 2：课程均值 = SUM(up)/SUM(evaluable)，且不再按时长加权。

        v1（时长加权）的值仍作为对照字段保留（`v1DurationWeightedMeanRate`），
        两者在窗口等长时相同，不等长时分叉——本用例把它钉住。
        """
        payload = self._payload([
            {"id": "win-001", "startMs": 0, "endMs": 30_000, "quality": "VALID",
             "headUpCount": 30, "validObservedCount": 30, "headUpRatio": None},
            {"id": "win-002", "startMs": 30_000, "endMs": 40_000, "quality": "VALID",
             "headUpCount": 0, "validObservedCount": 30, "headUpRatio": None},
        ])
        stats = compute_stats(payload, 40_000)
        self.assertEqual(stats["statisticsVersion"], "stats-v2")
        self.assertEqual(stats["headUp"]["aggregation"], "sum_ratio")
        # SUM(30+0) / SUM(30+30) = 0.5
        self.assertAlmostEqual(stats["headUp"]["meanRate"], 0.5, places=12)
        self.assertEqual(stats["headUp"]["sumHeadUpCount"], 30)
        self.assertEqual(stats["headUp"]["sumEvaluableCount"], 60)
        # v1 时长加权对照值 = (1.0*30000 + 0.0*10000)/40000 = 0.75
        self.assertAlmostEqual(stats["headUp"]["v1DurationWeightedMeanRate"], 0.75, places=12)

    def test_count_aggregation_differs_from_duration_weighting(self) -> None:
        """不等长 + 比例不同时，v2 与 v1 必须分叉（否则说明实现退化了）。"""
        payload = self._payload([
            {"id": "win-001", "startMs": 0, "endMs": 50_000, "quality": "VALID",
             "headUpCount": 40, "validObservedCount": 40, "headUpRatio": None},
            {"id": "win-002", "startMs": 50_000, "endMs": 60_000, "quality": "VALID",
             "headUpCount": 1, "validObservedCount": 20, "headUpRatio": None},
        ])
        stats = compute_stats(payload, 60_000)["headUp"]
        self.assertAlmostEqual(stats["meanRate"], 41 / 60, places=12)          # v2
        self.assertAlmostEqual(stats["v1DurationWeightedMeanRate"], 50.5 / 60, places=12)
        self.assertNotAlmostEqual(stats["meanRate"], stats["v1DurationWeightedMeanRate"], places=6)

    def test_review_windows_do_not_enter_course_mean(self) -> None:
        payload = self._payload([
            {"id": "win-001", "startMs": 0, "endMs": 10_000, "quality": "VALID",
             "headUpCount": 8, "validObservedCount": 10, "headUpRatio": None},
            {"id": "win-002", "startMs": 10_000, "endMs": 20_000, "quality": "REVIEW",
             "headUpCount": 2, "validObservedCount": 10, "headUpRatio": None},
        ])
        stats = compute_stats(payload, 20_000)["headUp"]
        self.assertAlmostEqual(stats["meanRate"], 0.8, places=12)   # 只用 win-001
        self.assertEqual(stats["sumEvaluableCount"], 10)
        self.assertEqual(stats["aggregatedWindowCount"], 1)
        self.assertEqual(stats["windowRates"], [0.8, None])
        # 诊断序列保留 REVIEW 的实测值，便于页面判断"数据少"还是"不可测"
        self.assertEqual(stats["windowRatesAll"], [0.8, 0.2])
        self.assertEqual(stats["qualityCounts"], {"VALID": 1, "REVIEW": 1})

    def test_low_quality_and_no_subjects_do_not_enter_course_mean(self) -> None:
        payload = self._payload([
            {"id": "win-001", "startMs": 0, "endMs": 10_000, "quality": "VALID",
             "headUpCount": 5, "validObservedCount": 10, "headUpRatio": None},
            {"id": "win-002", "startMs": 10_000, "endMs": 20_000, "quality": "LOW_QUALITY",
             "headUpCount": None, "validObservedCount": None, "headUpRatio": None},
            {"id": "win-003", "startMs": 20_000, "endMs": 30_000, "quality": "NO_SUBJECTS",
             "headUpCount": 0, "validObservedCount": 0, "headUpRatio": None},
        ])
        stats = compute_stats(payload, 30_000)["headUp"]
        self.assertAlmostEqual(stats["meanRate"], 0.5, places=12)
        self.assertEqual(stats["aggregatedWindowCount"], 1)
        self.assertEqual(stats["qualityCounts"],
                         {"VALID": 1, "LOW_QUALITY": 1, "NO_SUBJECTS": 1})
        self.assertEqual(stats["windowRates"], [0.5, None, None])

    def test_no_valid_window_means_null_course_mean(self) -> None:
        payload = self._payload([
            {"id": "win-001", "startMs": 0, "endMs": 10_000, "quality": "REVIEW",
             "headUpCount": 3, "validObservedCount": 10, "headUpRatio": None},
        ])
        stats = compute_stats(payload, 10_000)["headUp"]
        self.assertIsNone(stats["meanRate"])
        self.assertEqual(stats["aggregatedWindowCount"], 0)

    def test_all_formulas_agree_on_equal_length_windows(self) -> None:
        """等长窗口下 SUM 比 与 时长加权 恒等（解释为何演示 fixture 数值不变）。"""
        payload = self._payload([
            {"id": "win-001", "startMs": 0, "endMs": 10_000, "quality": "VALID",
             "headUpCount": 3, "validObservedCount": 4, "headUpRatio": None},
            {"id": "win-002", "startMs": 10_000, "endMs": 20_000, "quality": "VALID",
             "headUpCount": 1, "validObservedCount": 4, "headUpRatio": None},
        ])
        stats = compute_stats(payload, 20_000)["headUp"]
        self.assertAlmostEqual(stats["meanRate"], stats["v1DurationWeightedMeanRate"], places=12)
        self.assertAlmostEqual(stats["meanRate"], 0.5, places=12)

    def test_zero_denominator_ratio_is_null_not_zero(self) -> None:
        payload = self._payload([
            {"id": "win-001", "startMs": 0, "endMs": 10_000, "quality": "VALID",
             "headUpCount": 0, "validObservedCount": 0, "headUpRatio": None},
        ])
        stats = compute_stats(payload, 10_000)
        self.assertIsNone(stats["headUp"]["windowRates"][0])


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
