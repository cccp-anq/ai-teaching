"""FRAME-3 测试：采样策略接入 pipeline + 视觉层独立 + 观测校验。

验收（plan §12/§15 + 用户三条决策）：
- 采样网格唯一来源是 SamplingPolicy；`plan_frame_plans()` 与旧 `plan_frames()` **逐字段等价**
- offset 只过滤不平移，窗口 id 不重排
- 视觉层可独立使用（`QwenVisionClient` 只处理图片，不知道"视频"）
- 观测校验层区分"画面无人"与"有人但不可判读"，不合法观测不进入统计
- 兼容层：`headup.analyze` 的旧导入名全部保留
"""

from __future__ import annotations

import unittest
from pathlib import Path

from headup import definitions, wire
from headup.analyze import (FrameReading, HeuristicAnalyzer, QwenVlAnalyzer,  # noqa: F401
                            UnavailableAnalyzer, build_analyzer, prepare_image)
from headup.config import Config
from headup.counts import make_counts
from headup.frames import plan_frames
from headup.pipeline import run_job
from headup.video.sampling import (SamplingPolicy, build_sampling_plan, plan_frame_plans)
from headup.vision import FrameReading as VisionFrameReading
from headup.vision import QwenVisionClient
from headup.vision.validator import (OUTCOME_NO_VISIBLE_PERSON, OUTCOME_OK,
                                     OUTCOME_UNREADABLE, ObservationInvalid,
                                     is_statistically_usable, validate_observation)
from headup.wire import HeadUpParameters, JobRequest, MediaRef

from support import SYNTHETIC_MEDIA, find_tools, temp_root


def cfg(**overrides) -> Config:
    tools = find_tools()
    base = dict(media_probe_timeout_s=120, frame_max_width=1600)
    if tools:
        base["ffmpeg_path"], base["ffprobe_path"] = tools
    base.update(overrides)
    return Config(**base)


class SamplingPolicyIsTheOnlyGridSourceTest(unittest.TestCase):
    """决策①：网格只由 SamplingPolicy 生成；pipeline 用的是同一来源。"""

    def test_plan_frame_plans_equals_legacy_plan_frames(self) -> None:
        """与旧实现逐字段等价——这是"不改变行为"的机械证明。"""
        cases = [
            dict(start_ms=0, end_ms=60_000, window_ms=10_000, position="middle"),
            dict(start_ms=0, end_ms=25_000, window_ms=10_000, position="middle"),
            dict(start_ms=3_000, end_ms=20_000, window_ms=7_000, position="middle"),
            dict(start_ms=0, end_ms=20_000, window_ms=10_000, position="start"),
            dict(start_ms=0, end_ms=20_000, window_ms=10_000, position="end"),
        ]
        for case in cases:
            legacy = plan_frames(**case)
            policy = SamplingPolicy(window_ms=case["window_ms"], position=case["position"])
            new = plan_frame_plans(policy, start_ms=case["start_ms"], end_ms=case["end_ms"])
            self.assertEqual(len(legacy), len(new), case)
            for old, fresh in zip(legacy, new):
                self.assertEqual(old.window_index, fresh.window_index, case)
                self.assertEqual(old.window_id, fresh.window_id, case)
                self.assertEqual(old.window_start_ms, fresh.window_start_ms, case)
                self.assertEqual(old.window_end_ms, fresh.window_end_ms, case)
                self.assertEqual(old.sample_at_ms, fresh.sample_at_ms, case)

    def test_offsets_filter_plans_without_renumbering(self) -> None:
        policy = SamplingPolicy(window_ms=10_000, start_offset_ms=10_000, end_offset_ms=10_000)
        plan = plan_frame_plans(policy, start_ms=0, end_ms=60_000)
        sampling = build_sampling_plan(policy, analysis_start_ms=0, analysis_end_ms=60_000,
                                       duration_ms=60_000)
        kept = {p.window_id for p in sampling.points}
        filtered = [p for p in plan if p.window_id in kept]
        self.assertEqual([p.window_id for p in filtered],
                         ["win-002", "win-003", "win-004", "win-005"])
        self.assertEqual([p.sample_at_ms for p in filtered], [15_000, 25_000, 35_000, 45_000])

    def test_no_offsets_by_default(self) -> None:
        config = Config()
        self.assertEqual(config.sampling_start_offset_ms, 0)
        self.assertEqual(config.sampling_end_offset_ms, 0)


class VisionLayerIndependenceTest(unittest.TestCase):
    """plan §15：视觉客户端不接触视频；旧入口保留兼容。"""

    def test_frame_reading_is_the_same_class(self) -> None:
        self.assertIs(FrameReading, VisionFrameReading)

    def test_qwen_vision_client_is_the_client(self) -> None:
        self.assertIs(QwenVisionClient, QwenVlAnalyzer)

    def test_vision_client_only_accepts_image_paths(self) -> None:
        """接口签名层面确认它不需要视频：analyze(image_path)。"""
        import inspect

        signature = inspect.signature(QwenVisionClient.analyze)
        self.assertEqual(list(signature.parameters), ["self", "image_path"])

    def test_vision_module_does_not_import_video_layer(self) -> None:
        """视觉层不得 import 视频层（否则职责边界就被打破了）。"""
        source = (Path(__file__).resolve().parents[1] / "src" / "headup" / "vision"
                  / "qwen_client.py").read_text(encoding="utf-8")
        self.assertNotIn("headup.video", source)
        self.assertNotIn("from ..video", source)
        self.assertNotIn("ffmpeg", source.lower().replace("不做视频解码", ""))

    def test_legacy_analyze_entry_points_still_work(self) -> None:
        self.assertEqual(build_analyzer(Config(vision_provider="heuristic")).name, "heuristic")
        self.assertEqual(build_analyzer(Config(vision_provider="qwen-vl",
                                              vision_api_key="")).name, "unavailable")
        self.assertTrue(hasattr(prepare_image, "__call__"))


class ObservationValidatorTest(unittest.TestCase):
    """plan §12：观测校验器（不做法质分档，只判断"能不能用"）。"""

    def _reading(self, visible: int, up: int, down: int, *, strict: bool = True) -> FrameReading:
        counts = make_counts(visible_count=visible, head_up_count=up,
                             head_down_count=down, unknown_count=visible - up - down,
                             strict=strict)
        return FrameReading(head_up=up, head_down=down, unknown=visible - up - down,
                            legible_ratio=counts.coverage or 0.0, confidence=0.9, counts=counts)

    def test_readable_observation(self) -> None:
        result = validate_observation(self._reading(10, 6, 2))
        self.assertEqual(result["outcome"], OUTCOME_OK)
        self.assertTrue(result["readable"])
        self.assertAlmostEqual(result["unknownRatio"], 0.2)

    def test_no_visible_person_is_not_unreadable(self) -> None:
        result = validate_observation(self._reading(0, 0, 0))
        self.assertEqual(result["outcome"], OUTCOME_NO_VISIBLE_PERSON)
        self.assertFalse(result["readable"])

    def test_all_unknown_is_unreadable(self) -> None:
        result = validate_observation(self._reading(8, 0, 0))
        self.assertEqual(result["outcome"], OUTCOME_UNREADABLE)
        self.assertFalse(result["readable"])

    def test_legacy_counts_rejected_under_strict_rule(self) -> None:
        legacy = self._reading(10, 6, 2, strict=False)
        with self.assertRaises(ObservationInvalid):
            validate_observation(legacy)
        # 允许放宽时可以通过（用于历史结果复现）
        self.assertEqual(validate_observation(legacy, require_strict_counts=False)["outcome"],
                         OUTCOME_OK)

    def test_strict_counts_can_be_required_by_config(self) -> None:
        from headup.vision.validator import ObservationValidator
        validator = ObservationValidator(require_strict_counts=False)
        self.assertTrue(validator.validate(self._reading(10, 6, 2, strict=False))["readable"])

    def test_statistical_usability(self) -> None:
        self.assertTrue(is_statistically_usable(self._reading(10, 6, 2)))
        self.assertFalse(is_statistically_usable(self._reading(8, 0, 0)))
        self.assertFalse(is_statistically_usable(self._reading(0, 0, 0)))
        self.assertFalse(is_statistically_usable(self._reading(10, 6, 2, strict=False)))


@unittest.skipUnless(find_tools() is not None and SYNTHETIC_MEDIA.is_file(),
                     "缺少 ffmpeg 或测试媒体")
class PipelineSamplingIntegrationTest(unittest.TestCase):
    """pipeline 确实通过 SamplingPolicy 取网格，并把采样策略写进结果。"""

    class _Analyzer:
        name = "scripted"
        version = "test-1"
        prompt_version = "headup-strict-v0.2"

        def __init__(self) -> None:
            self.calls: list[str] = []

        def analyze(self, image_path: Path) -> FrameReading:
            self.calls.append(Path(image_path).name)
            counts = make_counts(visible_count=10, head_up_count=8, head_down_count=2,
                                 unknown_count=0, confidence=0.9)
            return FrameReading(head_up=8, head_down=2, unknown=0, legible_ratio=1.0,
                                confidence=0.9, counts=counts)

    def _request(self, window_ms: int = 10_000, duration_ms: int = 20_000) -> JobRequest:
        return JobRequest(schema_version="1.0", client_request_id="req-frame3-001",
                          module_type="HEAD_UP", class_session_id="s1", media_id="m1",
                          media=MediaRef(sha256="0" * 64, duration_ms=duration_ms,
                                         mime_type="video/mp4",
                                         source_url="https://media.example.invalid/x.mp4",
                                         url_expires_at="2099-01-01T00:00:00Z"),
                          analysis_range_start_ms=0, analysis_range_end_ms=duration_ms,
                          parameters=HeadUpParameters(window_ms=window_ms, output_mode="COUNTS",
                                                      definition_id=definitions.DEFINITION_ID_REAL))

    def test_payload_carries_sampling_plan(self) -> None:
        analyzer = self._Analyzer()
        result = run_job(request=self._request(), cfg=cfg(review_enabled=False), analyzer=analyzer,
                         work_root=temp_root("f3") / "jobs", local_media=SYNTHETIC_MEDIA)
        generation = result.payload["generation"]
        self.assertIn("samplingPlan", generation)
        self.assertEqual(generation["samplingPolicyVersion"], "10s-window-midpoint-v0.2")
        plan = generation["samplingPlan"]
        self.assertEqual(plan["sampleCount"], 2)
        self.assertEqual([p["sampleAtMs"] for p in plan["points"]], [5_000, 15_000])
        for row in result.payload["windows"]:
            self.assertEqual(row["quality"], "VALID")

    def test_offsets_reduce_windows_and_keep_ids(self) -> None:
        analyzer = self._Analyzer()
        config = cfg(review_enabled=False, sampling_start_offset_ms=10_000,
                     sampling_end_offset_ms=4_000)
        result = run_job(request=self._request(window_ms=5_000, duration_ms=20_000), cfg=config,
                         analyzer=analyzer, work_root=temp_root("f3b") / "jobs",
                         local_media=SYNTHETIC_MEDIA)
        # 采样点 2.5/7.5/12.5/17.5s；offset 保留 [10s, 16s] 内的点
        # ⇒ 12.5s(win-003) 与 17.5s？17.5 > 16 被滤掉，因此只剩 win-003
        ids = [row["id"] for row in result.payload["windows"]]
        self.assertEqual(ids, ["win-003"])
        self.assertIn("offset-10000-4000", result.payload["generation"]["samplingPolicyVersion"])

    def test_offsets_can_keep_multiple_windows(self) -> None:
        analyzer = self._Analyzer()
        config = cfg(review_enabled=False, sampling_start_offset_ms=5_000,
                     sampling_end_offset_ms=3_000)
        result = run_job(request=self._request(window_ms=5_000, duration_ms=20_000), cfg=config,
                         analyzer=analyzer, work_root=temp_root("f3d") / "jobs",
                         local_media=SYNTHETIC_MEDIA)
        # 保留 [5s, 17s] 内的采样点：7.5s(win-002) 与 12.5s(win-003)；17.5s 被滤掉，id 不重排
        self.assertEqual([row["id"] for row in result.payload["windows"]],
                         ["win-002", "win-003"])

    def test_empty_plan_after_offsets_is_explicit_error(self) -> None:
        analyzer = self._Analyzer()
        config = cfg(review_enabled=False, sampling_start_offset_ms=19_000,
                     sampling_end_offset_ms=19_000)
        with self.assertRaises(wire.TaskError) as ctx:
            run_job(request=self._request(), cfg=config, analyzer=analyzer,
                    work_root=temp_root("f3c") / "jobs", local_media=SYNTHETIC_MEDIA)
        self.assertEqual(ctx.exception.code, "PROCESSING_FAILED")


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
