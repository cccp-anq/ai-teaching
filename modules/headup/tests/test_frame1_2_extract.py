"""FRAME-1 / FRAME-2 测试：单点精确抽帧、批量抽帧等价性、采样策略（网格 + offset）。

验收（plan §10/§11/§23）：
- 单点抽帧：文件名带 timestampMs，含 10s / 30s / 中间 / 结束前
- 批量抽帧：数量、时间戳递增、每个文件可追溯到时间点
- **批量只有在与逐点 seek 等价时才启用**（等价性校验可复算）
- 采样网格只由 SamplingPolicy 生成；offset 只过滤、不平移
"""

from __future__ import annotations

import unittest
from pathlib import Path

from headup.config import Config
from headup.media import FfmpegTools
from headup.video import probe_video
from headup.video.frame_extractor import (FRAME_TYPE_PRIMARY, FRAME_TYPE_REVIEW_BEFORE,
                                          FrameExtractionFailure, FrameExtractor,
                                          frame_filename, verify_batch_equivalence)
from headup.video.sampling import (SAMPLING_POLICY_VERSION_V02, SamplingPolicy,
                                   build_sampling_plan)

from support import MODULE_FIXTURES, SYNTHETIC_MEDIA, find_tools, temp_root

SCENES_MEDIA = MODULE_FIXTURES / "media" / "headup-realclassroom-scenes-50s.mp4"


def cfg(**overrides) -> Config:
    tools = find_tools()
    base = dict(media_probe_timeout_s=120, frame_max_width=1600)
    if tools:
        base["ffmpeg_path"], base["ffprobe_path"] = tools
    base.update(overrides)
    return Config(**base)


def make_extractor(media: Path, **overrides) -> FrameExtractor:
    config = cfg(**overrides)
    tools = FfmpegTools.locate(config)
    meta = probe_video(media, config)
    return FrameExtractor(config, tools, media, duration_ms=meta.duration_ms)


# --------------------------------------------------------------------------
class SamplingGridTest(unittest.TestCase):
    """网格由 SamplingPolicy 生成（用户决策①）。"""

    def test_window_midpoints_match_frozen_sample_semantics(self) -> None:
        policy = SamplingPolicy(window_ms=10_000, position="middle")
        points = policy.grid(0, 60_000)
        self.assertEqual([p.sample_at_ms for p in points], [5_000, 15_000, 25_000,
                                                            35_000, 45_000, 55_000])
        self.assertEqual([p.window_id for p in points],
                         ["win-001", "win-002", "win-003", "win-004", "win-005", "win-006"])
        self.assertEqual((points[0].window_start_ms, points[0].window_end_ms), (0, 10_000))

    def test_short_last_window_keeps_sample_inside(self) -> None:
        points = SamplingPolicy(window_ms=10_000).grid(0, 25_000)
        last = points[-1]
        self.assertEqual((last.window_start_ms, last.window_end_ms), (20_000, 25_000))
        self.assertTrue(last.window_start_ms <= last.sample_at_ms < last.window_end_ms)

    def test_positions_are_configurable(self) -> None:
        start = SamplingPolicy(window_ms=10_000, position="start").grid(0, 20_000)
        end = SamplingPolicy(window_ms=10_000, position="end").grid(0, 20_000)
        self.assertEqual([p.sample_at_ms for p in start], [0, 10_000])
        self.assertEqual([p.sample_at_ms for p in end], [9_999, 19_999])

    def test_invalid_inputs_are_rejected(self) -> None:
        with self.assertRaises(ValueError):
            SamplingPolicy(window_ms=0).grid(0, 10_000)
        with self.assertRaises(ValueError):
            SamplingPolicy().grid(10_000, 10_000)
        with self.assertRaises(ValueError):
            SamplingPolicy(position="random").grid(0, 10_000)

    def test_grid_does_not_depend_on_ffmpeg(self) -> None:
        """纯计算：不同 windowMs 直接改变网格，不需要任何外部工具。"""
        points = SamplingPolicy(window_ms=7_000).grid(3_000, 20_000)
        # 窗口 [3000,10000) → 中点 6500；[10000,17000) → 13500；[17000,20000) → 18500
        self.assertEqual([p.sample_at_ms for p in points], [6_500, 13_500, 18_500])
        self.assertEqual([(p.window_start_ms, p.window_end_ms) for p in points],
                         [(3_000, 10_000), (10_000, 17_000), (17_000, 20_000)])


class SamplingOffsetTest(unittest.TestCase):
    """offset 只过滤、不平移网格（用户决策②）。"""

    def test_default_offsets_are_zero_and_do_not_change_grid(self) -> None:
        plan = build_sampling_plan(SamplingPolicy(), analysis_start_ms=0, analysis_end_ms=60_000)
        self.assertEqual(plan.policy_version, SAMPLING_POLICY_VERSION_V02)
        self.assertEqual(plan.filter_note["droppedCount"], 0)
        self.assertEqual([p.sample_at_ms for p in plan.points][:3], [5_000, 15_000, 25_000])

    def test_offsets_filter_without_shifting(self) -> None:
        policy = SamplingPolicy(start_offset_ms=10_000, end_offset_ms=10_000)
        plan = build_sampling_plan(policy, analysis_start_ms=0, analysis_end_ms=60_000,
                                   duration_ms=60_000)
        # 保留 15s/25s/35s/45s；5s 与 55s 被过滤掉，但保留点的时间戳不变
        self.assertEqual([p.sample_at_ms for p in plan.points], [15_000, 25_000, 35_000, 45_000])
        self.assertEqual(plan.filter_note["filteredOut"], ["win-001", "win-006"])
        self.assertEqual(plan.filter_note["droppedCount"], 2)
        # 窗口编号不重排（避免"第 N 窗"在两次运行间漂移）
        self.assertEqual([p.window_id for p in plan.points],
                         ["win-002", "win-003", "win-004", "win-005"])

    def test_non_zero_offset_bumps_policy_version(self) -> None:
        policy = SamplingPolicy(start_offset_ms=10_000)
        self.assertEqual(policy.effective_policy_version(),
                         f"{SAMPLING_POLICY_VERSION_V02}+offset-10000-0")
        self.assertEqual(SamplingPolicy().effective_policy_version(),
                         SAMPLING_POLICY_VERSION_V02)

    def test_plan_is_json_serializable(self) -> None:
        import json

        plan = build_sampling_plan(SamplingPolicy(), analysis_start_ms=0, analysis_end_ms=30_000)
        payload = plan.to_dict()
        self.assertEqual(payload["sampleCount"], 3)
        self.assertEqual(payload["policyVersion"], SAMPLING_POLICY_VERSION_V02)
        json.dumps(payload)


@unittest.skipUnless(find_tools() is not None and SYNTHETIC_MEDIA.is_file(),
                     "缺少 ffmpeg 或测试媒体")
class SingleFrameExtractionTest(unittest.TestCase):
    """FRAME-1：单点精确抽帧。"""

    def setUp(self) -> None:
        self.root = temp_root("f1")
        self.extractor = make_extractor(SYNTHETIC_MEDIA)   # 20s

    def test_filename_carries_timestamp(self) -> None:
        self.assertEqual(frame_filename(120_000), "frame-000120000.jpg")
        self.assertEqual(frame_filename(1_234), "frame-000001234.jpg")

    def test_extracts_required_time_points(self) -> None:
        for at in (10_000, 15_000, 19_000, 0):
            sample = self.extractor.extract_at(at, self.root / "primary")
            self.assertEqual(sample.timestamp_ms, at)
            self.assertTrue(sample.image_path.is_file(), at)
            self.assertGreater(sample.image_path.stat().st_size, 1000, at)
            self.assertIn(f"{at:09d}", sample.image_path.name)
            self.assertEqual(sample.type, FRAME_TYPE_PRIMARY)

    def test_review_frame_types_and_naming(self) -> None:
        sample = self.extractor.extract_at(15_500, self.root / "review", suffix="review-1",
                                           frame_type=FRAME_TYPE_REVIEW_BEFORE,
                                           window_id="win-002")
        self.assertEqual(sample.type, FRAME_TYPE_REVIEW_BEFORE)
        self.assertEqual(sample.window_id, "win-002")
        self.assertIn("review-1", sample.image_path.name)

    def test_timestamp_is_clamped_to_media_range(self) -> None:
        beyond = self.extractor.extract_at(999_999, self.root / "primary")
        self.assertEqual(beyond.timestamp_ms, 18_999)      # 记录真实回退点，不冒充片尾时刻
        self.assertTrue(beyond.image_path.is_file())       # 但一定产出一张有效帧
        negative = self.extractor.extract_at(-500, self.root / "primary")
        self.assertEqual(negative.timestamp_ms, 0)

    def test_different_timestamps_produce_different_frames(self) -> None:
        first = self.extractor.extract_at(0, self.root / "cmp")
        second = self.extractor.extract_at(10_000, self.root / "cmp")
        self.assertNotEqual(first.image_path.stat().st_size, 0)
        self.assertNotEqual(first.image_path.read_bytes(), second.image_path.read_bytes())

    def test_missing_tools_fail_explicitly(self) -> None:
        broken = FrameExtractor(cfg(), FfmpegTools("nope-ffmpeg", "nope-ffprobe", False, "缺失"),
                                SYNTHETIC_MEDIA, duration_ms=20_000)
        with self.assertRaises(FrameExtractionFailure) as ctx:
            broken.extract_at(1_000, self.root / "x")
        self.assertEqual(ctx.exception.code, "FFMPEG_MISSING")
        self.assertEqual(ctx.exception.stage, "EXTRACTING_FRAMES")


@unittest.skipUnless(find_tools() is not None and SYNTHETIC_MEDIA.is_file(),
                     "缺少 ffmpeg 或测试媒体")
class BatchExtractionTest(unittest.TestCase):
    """FRAME-2：批量抽帧必须证明与逐点 seek 等价。"""

    def setUp(self) -> None:
        self.root = temp_root("f2")
        self.extractor = make_extractor(SYNTHETIC_MEDIA)
        self.points = SamplingPolicy(window_ms=5_000).grid(0, 20_000)

    def test_batch_produces_one_frame_per_point_with_timestamps(self) -> None:
        samples = self.extractor.extract_batch([p.sample_at_ms for p in self.points],
                                              self.root / "batch")
        self.assertEqual([s.timestamp_ms for s in samples], [2_500, 7_500, 12_500, 17_500])
        for sample in samples:
            self.assertTrue(sample.image_path.is_file())
            self.assertIn(f"{sample.timestamp_ms:09d}", sample.image_path.name)

    def test_batch_matches_per_point_seek(self) -> None:
        """批量路径与逐点 seek 必须给出同样的采样点（等价性验收）。"""
        batch = self.extractor.extract_batch([p.sample_at_ms for p in self.points],
                                             self.root / "batch2")
        one_by_one = [self.extractor.extract_at(p.sample_at_ms, self.root / "seek")
                      for p in self.points]
        self.assertEqual([s.timestamp_ms for s in batch], [s.timestamp_ms for s in one_by_one])
        proof = verify_batch_equivalence(self.extractor, self.points[0].sample_at_ms,
                                         self.root / "proof")
        self.assertLessEqual(proof["meanPixelDiff"], 2.0,
                             f"批量首帧与逐点首帧不等价: {proof}")
        self.assertTrue(proof["equivalent"])

    def test_non_uniform_grid_is_rejected(self) -> None:
        """不等间隔必须被拒绝——网格只能来自 SamplingPolicy，不能由 ffmpeg 猜。"""
        with self.assertRaises(FrameExtractionFailure) as ctx:
            # 间隔依次为 4000 / 5000，不是等差数列
            self.extractor.extract_batch([1_000, 5_000, 10_000], self.root / "bad")
        self.assertEqual(ctx.exception.message, "批量抽帧要求时间点等间隔；请改用逐点抽帧")

    def test_duplicate_timestamps_are_rejected(self) -> None:
        with self.assertRaises(FrameExtractionFailure):
            self.extractor.extract_batch([5_000, 5_000, 5_000], self.root / "dup")

    def test_uniform_grid_is_accepted(self) -> None:
        """等间隔（间距 4000）必须通过——避免把正常网格误判成非法。"""
        samples = self.extractor.extract_batch([1_000, 5_000, 9_000], self.root / "ok")
        self.assertEqual([s.timestamp_ms for s in samples], [1_000, 5_000, 9_000])

    def test_empty_input_returns_empty(self) -> None:
        self.assertEqual(self.extractor.extract_batch([], self.root / "none"), [])

    def test_sample_count_matches_policy_for_long_video(self) -> None:
        """45 分钟课的采样点数量应为 270（10s 网格、无 offset）。"""
        points = SamplingPolicy(window_ms=10_000).grid(0, 2_700_000)
        self.assertEqual(len(points), 270)
        self.assertEqual(points[0].sample_at_ms, 5_000)
        self.assertEqual(points[-1].sample_at_ms, 2_695_000)


@unittest.skipUnless(find_tools() is not None and SCENES_MEDIA.is_file(),
                     "缺少 50 秒场景素材")
class BatchOnRealScenesTest(unittest.TestCase):
    def test_batch_equivalence_on_real_scene_media(self) -> None:
        extractor = make_extractor(SCENES_MEDIA)
        proof = verify_batch_equivalence(extractor, 5_000, temp_root("f2r"))
        self.assertTrue(proof["equivalent"], f"真实素材上批量首帧不等价: {proof}")


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
