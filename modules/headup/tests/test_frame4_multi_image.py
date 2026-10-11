"""FRAME-4 测试：一次多图复核请求 + 质量层独立。

验收（用户决策③ + plan §13/§14）：
- REVIEW 窗口用 `analyzeWindow([t-0.5s, t, t+0.5s])` **一次请求带三张图**
- 禁止"三张图分别调用三次再平均"作为默认路径
- 三张图共同产生**一个** centerTimestamp 的观测；三帧仍无证据 ⇒ LOW_QUALITY
- 分析器不支持多图时回退逐帧单图，并在证据里注明策略
- 质量判定已独立到 `headup.analysis.quality`，旧导入名保留
"""

from __future__ import annotations

import inspect
import unittest
from pathlib import Path

from headup import definitions, prompts, wire
from headup.analysis import quality as quality_mod
from headup.analysis.quality import (REASON_REVIEW_INSUFFICIENT, REVIEW_OUTCOME_CONFIRMED_VALID,
                                     REVIEW_OUTCOME_INSUFFICIENT, finalize_review_observation)
from headup.analyze import FrameReading, QwenVlAnalyzer
from headup.config import Config
from headup.counts import make_counts
from headup.pipeline import (REVIEW_OUTCOME_CONFIRMED_VALID, classify_window,  # noqa: F401
                             finalize_review_observation as pipeline_finalize_observation,
                             run_job)
from headup.vision import QwenVisionClient
from headup.wire import HeadUpParameters, JobRequest, MediaRef

from support import SYNTHETIC_MEDIA, find_tools, temp_root
from test_round3_review import ScriptedAnalyzer, cfg_for_review


class ReviewPromptAndApiTest(unittest.TestCase):
    """提示词与接口形状。"""

    def test_review_prompt_requires_single_group_of_counts(self) -> None:
        text = prompts.USER_PROMPT_REVIEW_MULTI
        self.assertIn("同一个观察窗口", text)
        self.assertIn("输出**一组**计数", text)
        self.assertIn("不是每帧各出一组", text)
        self.assertIn("仅看到后脑", text)
        self.assertIn("宁可增加 UNKNOWN", text)

    def test_review_prompt_is_registered(self) -> None:
        self.assertIn(prompts.PROMPT_VERSION_REVIEW, prompts.PROMPTS)
        self.assertTrue(prompts.is_strict(prompts.PROMPT_VERSION_REVIEW))
        self.assertEqual(prompts.user_prompt(prompts.PROMPT_VERSION_REVIEW),
                         prompts.USER_PROMPT_REVIEW_MULTI)

    def test_analyze_window_accepts_a_frame_list(self) -> None:
        signature = inspect.signature(QwenVisionClient.analyze_window)
        self.assertEqual(list(signature.parameters), ["self", "image_paths"])
        # 语义别名（plan §14：analyzeFrame / analyzeWindow 两种调用语义）
        self.assertTrue(callable(QwenVisionClient.analyze_frame))

    def test_analyze_window_rejects_empty_list(self) -> None:
        cfg = Config(vision_api_key="k")
        client = QwenVlAnalyzer(cfg)
        with self.assertRaises(wire.TaskError):
            client.analyze_window([])


class FinalizeObservationTest(unittest.TestCase):
    """多图观测直接判级：不再做多帧中位数聚合。"""

    def _counts(self, visible: int, up: int, down: int):
        return make_counts(visible_count=visible, head_up_count=up, head_down_count=down,
                           unknown_count=visible - up - down, confidence=0.9)

    def test_confirms_valid_when_coverage_is_enough(self) -> None:
        decision, evidence = finalize_review_observation(self._counts(10, 9, 1),
                                                         valid_threshold=0.70,
                                                         review_threshold=0.50, frame_count=3)
        self.assertEqual(decision.quality, "VALID")
        self.assertEqual(evidence["outcome"], REVIEW_OUTCOME_CONFIRMED_VALID)
        self.assertEqual(evidence["frameCount"], 3)
        self.assertAlmostEqual(evidence["medianCoverage"], 1.0)

    def test_degrades_when_still_insufficient(self) -> None:
        decision, evidence = finalize_review_observation(self._counts(10, 4, 2),
                                                         valid_threshold=0.70,
                                                         review_threshold=0.50, frame_count=3)
        self.assertEqual(decision.quality, "LOW_QUALITY")
        self.assertEqual(decision.reason, REASON_REVIEW_INSUFFICIENT)
        self.assertEqual(evidence["outcome"], REVIEW_OUTCOME_INSUFFICIENT)
        self.assertIsNone(decision.head_up_rate, "不可读时不得给出比率")

    def test_multi_frame_can_lift_coverage_above_threshold(self) -> None:
        """多图能看到更多人的朝向 ⇒ coverage 上升并确认 VALID（这正是复核的意义）。"""
        single = self._counts(20, 8, 2)          # coverage 0.5 ⇒ 单帧只能进 REVIEW
        multi = self._counts(20, 14, 4)          # 三帧合并后 coverage 0.9 ⇒ VALID
        self.assertEqual(classify_window(single, valid_threshold=0.70,
                                         review_threshold=0.50).quality, "REVIEW")
        decision, _ = finalize_review_observation(multi, valid_threshold=0.70,
                                                  review_threshold=0.50, frame_count=3)
        self.assertEqual(decision.quality, "VALID")

    def test_pipeline_reexports_quality_module(self) -> None:
        self.assertIs(pipeline_finalize_observation, finalize_review_observation)
        self.assertIs(classify_window, quality_mod.classify_window)


@unittest.skipUnless(find_tools() is not None and SYNTHETIC_MEDIA.is_file(),
                     "缺少 ffmpeg 或测试媒体")
class MultiImagePathTest(unittest.TestCase):
    """端到端：多图路径与逐帧回退路径。"""

    class _SingleFrameOnlyAnalyzer:
        """故意不实现 analyze_window，用于验证回退路径。"""

        name = "single-frame-only"
        version = "test-1"
        prompt_version = prompts.PROMPT_VERSION_STRICT

        def __init__(self) -> None:
            self.calls: list[str] = []

        def analyze(self, image_path: Path) -> FrameReading:
            name = Path(image_path).name
            self.calls.append(name)
            # win-002 相对 win-001 突变（0.9 → 0.5）且自身覆盖不足 ⇒ 触发复核
            counts = make_counts(visible_count=10, head_up_count=2, head_down_count=2,
                                 unknown_count=6, confidence=0.9)
            if "win-001" in name:
                counts = make_counts(visible_count=10, head_up_count=9, head_down_count=1,
                                     unknown_count=0, confidence=0.9)
            return FrameReading(head_up=counts.head_up_count, head_down=counts.head_down_count,
                                unknown=counts.unknown_count,
                                legible_ratio=counts.coverage or 0.0, confidence=0.9,
                                counts=counts)

    def _change_script(self) -> dict[str, tuple[int, int, int]]:
        """win-002 相对 win-001 有明显突变（0.9 → 0.5）⇒ 触发复核。

        FRAME-5 起代表帧按时间戳命名，因此 key 用采样点时间戳片段。
        """
        return {"000005000": (10, 9, 1),     # win-001：rate 0.9，coverage 1.0 ⇒ VALID
                "000015000": (10, 2, 2)}     # win-002：rate 0.5，coverage 0.4 ⇒ 突变且覆盖不足

    def _request(self, duration_ms: int = 20_000) -> JobRequest:
        return JobRequest(schema_version="1.0", client_request_id="req-frame4-001",
                          module_type="HEAD_UP", class_session_id="s1", media_id="m1",
                          media=MediaRef(sha256="0" * 64, duration_ms=duration_ms,
                                         mime_type="video/mp4",
                                         source_url="https://media.example.invalid/x.mp4",
                                         url_expires_at="2099-01-01T00:00:00Z"),
                          analysis_range_start_ms=0, analysis_range_end_ms=duration_ms,
                          parameters=HeadUpParameters(window_ms=10_000, output_mode="COUNTS",
                                                      definition_id=definitions.DEFINITION_ID_REAL))

    def test_multi_image_is_the_default_review_path(self) -> None:
        # win-002 触发复核（突变 + UNKNOWN 偏高）；三图复核给出 coverage 1.0 ⇒ VALID
        analyzer = ScriptedAnalyzer(self._change_script(), window_script={"win-002": (10, 9, 1)})
        result = run_job(request=self._request(), cfg=cfg_for_review(), analyzer=analyzer,
                         work_root=temp_root("f4") / "jobs", local_media=SYNTHETIC_MEDIA)
        self.assertTrue(analyzer.window_calls, "应发生多图请求")
        self.assertEqual(len(analyzer.window_calls[0]), 3, "复核必须一次带三张图")
        row = {w["id"]: w for w in result.payload["windows"]}["win-002"]
        self.assertEqual(row["review"]["strategy"], "MULTI_IMAGE_SINGLE_REQUEST")
        self.assertEqual(row["review"]["frameCount"], 3)
        self.assertEqual(row["quality"], "VALID")

    def test_falls_back_to_single_frame_when_analyzer_lacks_window_api(self) -> None:
        """分析器没有 analyze_window ⇒ 回退逐帧单图，并在证据里注明策略。"""
        analyzer = self._SingleFrameOnlyAnalyzer()
        result = run_job(request=self._request(), cfg=cfg_for_review(), analyzer=analyzer,
                         work_root=temp_root("f4b") / "jobs", local_media=SYNTHETIC_MEDIA)
        row = {w["id"]: w for w in result.payload["windows"]}["win-002"]
        self.assertIn("review", row, "突变窗口应触发复核")
        self.assertEqual(row["review"]["strategy"], "THREE_SINGLE_FRAME")
        self.assertTrue(row["review"]["perFrameCounts"], "回退路径要给出逐帧证据")
        self.assertEqual(row["review"]["frameCount"], len(row["review"]["perFrameCounts"]))

    def test_multi_image_can_be_disabled(self) -> None:
        analyzer = ScriptedAnalyzer(self._change_script(), window_script={"win-002": (10, 8, 2)})
        cfg = cfg_for_review(review_multi_image=False)
        result = run_job(request=self._request(), cfg=cfg, analyzer=analyzer,
                         work_root=temp_root("f4c") / "jobs", local_media=SYNTHETIC_MEDIA)
        self.assertFalse(analyzer.window_calls, "关闭多图后不应有多图请求")
        row = {w["id"]: w for w in result.payload["windows"]}["win-002"]
        self.assertEqual(row["review"]["strategy"], "THREE_SINGLE_FRAME")

    def test_review_calls_are_fewer_than_three_single_calls(self) -> None:
        """一次多图请求 ⇒ 复核的**请求数**从 3 降到 1（帧数相同，请求数更少）。"""
        multi = ScriptedAnalyzer(self._change_script(), window_script={"win-002": (10, 8, 2)})
        run_job(request=self._request(), cfg=cfg_for_review(), analyzer=multi,
                work_root=temp_root("f4d") / "jobs", local_media=SYNTHETIC_MEDIA)
        single = ScriptedAnalyzer(self._change_script(), window_script={"win-002": (10, 8, 2)})
        run_job(request=self._request(), cfg=cfg_for_review(review_multi_image=False),
                analyzer=single, work_root=temp_root("f4e") / "jobs", local_media=SYNTHETIC_MEDIA)

        # 复核请求数：多图 = 1 次（带 3 张图）；逐帧 = 3 次（同样的三帧，分三次请求）
        multi_review_requests = len(multi.window_calls)
        single_review_requests = len(single.review_frame_reads)
        self.assertEqual(multi_review_requests, 1)
        self.assertEqual(single_review_requests, 3)
        self.assertLess(multi_review_requests, single_review_requests)
        # 两条路径看到的帧数相同（都是 3 帧），差别只在"几次请求"
        self.assertEqual(len(multi.calls), len(single.calls))
        # 多图请求确实一次带了三张不同时间点的图
        self.assertEqual(len(multi.window_calls), 1)
        self.assertEqual(len(multi.window_calls[0]), 3)
        self.assertEqual(len(set(multi.window_calls[0])), 3)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
