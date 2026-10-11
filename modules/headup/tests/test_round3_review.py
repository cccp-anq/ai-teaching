"""Round 3（v0.2）自适应三帧复核测试。

验收对照（用户 v0.2 Round 3）：
- VALID 不触发二次调用；REVIEW 触发三帧复核；突变窗口触发复核
- 二次调用使用**相邻帧**（t±offset），不是同一张图重复请求
- 三帧仍无证据 → LOW_QUALITY（禁止用时间连续性强行推断）
- 相邻帧提供了足够证据 → VALID
- 正常情况下 API 调用量不是 ×2
"""

from __future__ import annotations

import unittest
from pathlib import Path

from headup import counts as counts_mod
from headup import definitions, wire
from headup.config import Config
from headup.counts import make_counts
from headup.frames import plan_frames
from headup.media import FfmpegTools
from headup.pipeline import (REASON_REVIEW_CONFIRMED_VALID, REASON_REVIEW_INSUFFICIENT,
                             REVIEW_OUTCOME_CONFIRMED_VALID, REVIEW_OUTCOME_INSUFFICIENT,
                             REVIEW_REASON_QUALITY, REVIEW_REASON_SUDDEN_CHANGE,
                             REVIEW_REASON_UNKNOWN_HIGH, _review_frames, finalize_review,
                             needs_review, run_job)
from headup.wire import HeadUpParameters, JobRequest, MediaRef
from support import SYNTHETIC_MEDIA, find_tools, temp_root


def cfg_for_review(**overrides) -> Config:
    tools = find_tools()
    base = dict(
        review_enabled=True,
        review_unknown_ratio_threshold=0.50,
        review_sudden_change_threshold=0.20,
        review_adjacent_frame_offset_ms=500,
        review_total_max_attempts=3,
        review_on_low_quality=False,
        quality_valid_threshold=0.70,
        quality_review_threshold=0.50,
        work_dir=str(temp_root("rv-work")),
    )
    if tools:
        base["ffmpeg_path"] = tools[0]
        base["ffprobe_path"] = tools[1]
    base.update(overrides)
    return Config(**base)


def primary_frame_name(sample_at_ms: int) -> str:
    """FRAME-5 起代表帧按**原视频时间戳**命名（`frame-{timestampMs}.jpg`）。"""
    return f"frame-{int(sample_at_ms):09d}.jpg"


class ScriptedAnalyzer:
    """按帧文件名返回脚本化计数，并记录每次调用（用于断言调用量）。

    FRAME-5 起代表帧文件名为 `frame-{sampleAtMs}.jpg`（时间戳命名，证据链要求），
    因此 `script` 的 key 用 **时间戳片段**（如 `"000005000"`）或窗口 id（复核帧仍是
    `win-002-r1.jpg` 风格）。

    FRAME-4：本分析器实现 `analyze_window()`，因此复核走**一次多图请求**（用户决策③）。
    想验证"逐帧单图回退路径"时，把配置的 `review_multi_image` 设为 False 即可。
    """

    name = "scripted"
    version = "test-1"
    prompt_version = "headup-strict-v0.2"

    def __init__(self, script: dict[str, tuple[int, int, int]],
                 window_script: dict[str, tuple[int, int, int]] | None = None):
        self.script = script
        #: 多图复核的结果（按窗口 id 匹配参与复核的帧名）
        self.window_script = window_script or {}
        self.calls: list[str] = []
        self.window_calls: list[list[str]] = []
        #: 逐帧单图回退路径下复核帧的读取次数（用于按"请求数"对比）
        self.review_frame_reads: list[str] = []

    def _counts_for(self, name: str):
        # 最长标记优先：避免 "win-002" 抢先匹配 "win-002-r1"
        for marker in sorted(self.script, key=len, reverse=True):
            if marker in name:
                visible, up, down = self.script[marker]
                unknown = visible - up - down
                return make_counts(visible_count=visible, head_up_count=up,
                                   head_down_count=down, unknown_count=unknown, confidence=0.9)
        return make_counts(visible_count=10, head_up_count=5, head_down_count=5,
                           unknown_count=0, confidence=0.9)

    def analyze(self, image_path: Path):
        from headup.analyze import FrameReading

        name = Path(image_path).name
        self.calls.append(name)
        if "-r" in name:                      # 复核帧（win-002-r1 等）
            self.review_frame_reads.append(name)
        counts = self._counts_for(name)
        return FrameReading(head_up=counts.head_up_count, head_down=counts.head_down_count,
                            unknown=counts.unknown_count,
                            legible_ratio=counts.coverage or 0.0, confidence=0.9, counts=counts)

    def analyze_window(self, image_paths):
        """一次多图请求：多张图 → 一个窗口观测。"""
        from headup.analyze import FrameReading

        names = [Path(p).name for p in image_paths]
        self.window_calls.append(names)
        self.calls.extend(names)
        for window_id, (visible, up, down) in self.window_script.items():
            if any(window_id in name for name in names):
                unknown = visible - up - down
                counts = make_counts(visible_count=visible, head_up_count=up,
                                     head_down_count=down, unknown_count=unknown, confidence=0.9)
                return FrameReading(head_up=up, head_down=down, unknown=unknown,
                                    legible_ratio=counts.coverage or 0.0, confidence=0.9,
                                    counts=counts, frame_count=len(names))
        counts = self._counts_for(names[0] if names else "")
        return FrameReading(head_up=counts.head_up_count, head_down=counts.head_down_count,
                            unknown=counts.unknown_count,
                            legible_ratio=counts.coverage or 0.0, confidence=0.9,
                            counts=counts, frame_count=len(names))


class ReviewTriggerTest(unittest.TestCase):
    """触发条件（Round 3 §1）：不依赖抽帧与模型。"""

    def setUp(self) -> None:
        self.cfg = cfg_for_review()

    def test_review_quality_triggers(self) -> None:
        counts = make_counts(visible_count=10, head_up_count=4, head_down_count=2,
                             unknown_count=4)      # coverage 0.6
        decision = needs_review(counts=counts, coverage=0.6, unknown_ratio=0.4,
                                window_rate=0.667, previous_valid_rate=None, cfg=self.cfg,
                                status="REVIEW")
        self.assertTrue(decision.needed)
        self.assertIn(REVIEW_REASON_QUALITY, decision.reasons)

    def test_valid_does_not_trigger(self) -> None:
        counts = make_counts(visible_count=10, head_up_count=7, head_down_count=2,
                             unknown_count=1)      # coverage 0.9, unknown 0.1
        decision = needs_review(counts=counts, coverage=0.9, unknown_ratio=0.1,
                                window_rate=0.78, previous_valid_rate=0.75, cfg=self.cfg,
                                status="VALID")
        self.assertFalse(decision.needed)
        self.assertEqual(decision.reasons, ())

    def test_high_unknown_ratio_triggers(self) -> None:
        counts = make_counts(visible_count=10, head_up_count=5, head_down_count=2,
                             unknown_count=3)      # unknown 占比 0.3
        # coverage 0.7 ⇒ VALID，但 UNKNOWN 占比被显式设成 0.6 ⇒ 仍应复核
        decision = needs_review(counts=counts, coverage=0.7, unknown_ratio=0.6,
                                window_rate=0.71, previous_valid_rate=0.7, cfg=self.cfg,
                                status="VALID")
        self.assertTrue(decision.needed)
        self.assertIn(REVIEW_REASON_UNKNOWN_HIGH, decision.reasons)

    def test_sudden_change_triggers(self) -> None:
        counts = make_counts(visible_count=10, head_up_count=9, head_down_count=1,
                             unknown_count=0)      # coverage 1.0 ⇒ VALID
        decision = needs_review(counts=counts, coverage=1.0, unknown_ratio=0.0,
                                window_rate=0.9, previous_valid_rate=0.5, cfg=self.cfg,
                                status="VALID")
        self.assertTrue(decision.needed)
        self.assertIn(REVIEW_REASON_SUDDEN_CHANGE, decision.reasons)

    def test_change_below_threshold_does_not_trigger(self) -> None:
        counts = make_counts(visible_count=10, head_up_count=8, head_down_count=2,
                             unknown_count=0)
        decision = needs_review(counts=counts, coverage=1.0, unknown_ratio=0.0,
                                window_rate=0.8, previous_valid_rate=0.65, cfg=self.cfg,
                                status="VALID")
        self.assertFalse(decision.needed)   # 差 0.15 < 0.20

    def test_disabled_review_never_triggers(self) -> None:
        cfg = cfg_for_review(review_enabled=False)
        counts = make_counts(visible_count=10, head_up_count=4, head_down_count=2,
                             unknown_count=4)
        decision = needs_review(counts=counts, coverage=0.6, unknown_ratio=0.4,
                                window_rate=0.6, previous_valid_rate=0.1, cfg=cfg,
                                status="REVIEW")
        self.assertFalse(decision.needed)

    def test_thresholds_are_configurable(self) -> None:
        counts = make_counts(visible_count=10, head_up_count=8, head_down_count=2,
                             unknown_count=0)
        loose = needs_review(counts=counts, coverage=1.0, unknown_ratio=0.0, window_rate=0.8,
                             previous_valid_rate=0.7,
                             cfg=cfg_for_review(review_sudden_change_threshold=0.05),
                             status="VALID")
        self.assertTrue(loose.needed)
        strict = needs_review(counts=counts, coverage=1.0, unknown_ratio=0.0, window_rate=0.8,
                              previous_valid_rate=0.7,
                              cfg=cfg_for_review(review_sudden_change_threshold=0.5),
                              status="VALID")
        self.assertFalse(strict.needed)

    def test_low_quality_not_reviewed_by_default_but_configurable(self) -> None:
        counts = make_counts(visible_count=10, head_up_count=2, head_down_count=2,
                             unknown_count=6)      # coverage 0.4 ⇒ LOW_QUALITY
        default = needs_review(counts=counts, coverage=0.4, unknown_ratio=0.6,
                               window_rate=0.5, previous_valid_rate=0.5, cfg=self.cfg,
                               status="UNREADABLE")
        # coverage 0.4 且 unknown 0.6 ⇒ 仍会因 UNKNOWN 过高触发；把阈值抬高再看默认行为
        self.assertIn(REVIEW_REASON_UNKNOWN_HIGH, default.reasons)
        quiet = needs_review(counts=counts, coverage=0.4, unknown_ratio=0.6, window_rate=0.5,
                             previous_valid_rate=0.5,
                             cfg=cfg_for_review(review_unknown_ratio_threshold=0.9),
                             status="UNREADABLE")
        self.assertFalse(quiet.needed, "LOW_QUALITY 默认不额外复核")
        explicit = needs_review(counts=counts, coverage=0.4, unknown_ratio=0.6, window_rate=0.5,
                                previous_valid_rate=0.5,
                                cfg=cfg_for_review(review_unknown_ratio_threshold=0.9,
                                                   review_on_low_quality=True),
                                status="UNREADABLE")
        self.assertTrue(explicit.needed)


class ReviewFinalizeTest(unittest.TestCase):
    """三帧结论（Round 3 §2）：中位数聚合、不足则降级、绝不用时间连续性推断。"""

    def _counts(self, visible: int, up: int, down: int):
        return make_counts(visible_count=visible, head_up_count=up, head_down_count=down,
                           unknown_count=visible - up - down)

    def test_extra_evidence_confirms_valid(self) -> None:
        primary = self._counts(10, 4, 2)         # coverage 0.6
        extra = [self._counts(10, 7, 1),         # coverage 0.8
                 self._counts(10, 6, 2)]         # coverage 0.8
        decision, evidence = finalize_review(primary, extra, valid_threshold=0.70,
                                             review_threshold=0.50)
        self.assertEqual(decision.quality, "VALID")
        self.assertEqual(evidence["outcome"], REVIEW_OUTCOME_CONFIRMED_VALID)
        self.assertAlmostEqual(evidence["medianCoverage"], 0.8)
        self.assertEqual(evidence["frameCount"], 3)
        self.assertEqual(decision.status, "VALID")

    def test_insufficient_evidence_degrades_to_low_quality(self) -> None:
        primary = self._counts(10, 4, 2)         # 0.6
        extra = [self._counts(10, 3, 2),         # 0.5
                 self._counts(10, 4, 2)]         # 0.6
        decision, evidence = finalize_review(primary, extra, valid_threshold=0.70,
                                             review_threshold=0.50)
        self.assertEqual(decision.quality, "LOW_QUALITY")
        self.assertEqual(decision.reason, REASON_REVIEW_INSUFFICIENT)
        self.assertEqual(evidence["outcome"], REVIEW_OUTCOME_INSUFFICIENT)
        self.assertIsNone(decision.head_up_rate, "不可读时不得给出比率")

    def test_all_three_frames_unreadable_is_low_quality(self) -> None:
        blank = self._counts(6, 0, 0)            # 全 UNKNOWN
        decision, evidence = finalize_review(blank, [blank, blank], valid_threshold=0.70,
                                             review_threshold=0.50)
        self.assertEqual(decision.quality, "LOW_QUALITY")
        self.assertEqual(evidence["outcome"], REVIEW_OUTCOME_INSUFFICIENT)
        self.assertIsNone(decision.head_up_rate)

    def test_estimate_is_median_frame_not_sum(self) -> None:
        """不得把人·帧相加：结果必须是某一帧的计数（中位数帧）。"""
        primary = self._counts(10, 4, 2)          # rate 0.667
        extra = [self._counts(10, 8, 0),          # rate 1.0
                 self._counts(10, 5, 5)]          # rate 0.5
        decision, evidence = finalize_review(primary, extra, valid_threshold=0.70,
                                             review_threshold=0.50)
        selected = decision.counts
        self.assertIn(selected.head_up_rate, (0.5, 2 / 3, 1.0))
        self.assertEqual(selected.visible_count, 10, "不得把三帧人数相加（那会变成 30）")
        self.assertAlmostEqual(evidence["medianHeadUpRate"], 2 / 3)

    def test_no_temporal_extrapolation(self) -> None:
        """三帧里两帧不可读、一帧勉强可读但 coverage 不足 ⇒ 仍判 LOW_QUALITY。

        即使"时间上前后都在抬头"，也不允许据此推断不可读的那一帧。
        """
        good = self._counts(10, 6, 1)             # coverage 0.7
        weak = self._counts(20, 3, 1)             # coverage 0.2
        decision, _evidence = finalize_review(weak, [good, weak], valid_threshold=0.70,
                                              review_threshold=0.50)
        self.assertEqual(decision.quality, "LOW_QUALITY")


@unittest.skipUnless(find_tools() is not None and SYNTHETIC_MEDIA.is_file(),
                     "缺少 ffmpeg 或测试媒体")
class ReviewPipelineTest(unittest.TestCase):
    """端到端（本地媒体 + 脚本化分析器）：验证调用量与状态流转。"""

    def setUp(self) -> None:
        self.root = temp_root("rv")
        self.tools = FfmpegTools.locate(cfg_for_review())
        self.media = SYNTHETIC_MEDIA

    def _request(self, window_ms: int = 10_000, duration_ms: int = 20_000) -> JobRequest:
        return JobRequest(
            schema_version="1.0", client_request_id="req-review-001",
            module_type="HEAD_UP", class_session_id="s1", media_id="m1",
            media=MediaRef(sha256="0" * 64, duration_ms=duration_ms, mime_type="video/mp4",
                           source_url="https://media.example.invalid/x.mp4",
                           url_expires_at="2099-01-01T00:00:00Z"),
            analysis_range_start_ms=0, analysis_range_end_ms=duration_ms,
            parameters=HeadUpParameters(window_ms=window_ms, output_mode="COUNTS",
                                        definition_id=definitions.DEFINITION_ID_REAL))

    def test_valid_window_makes_single_call_no_review(self) -> None:
        analyzer = ScriptedAnalyzer({"000005000": (10, 8, 2), "000015000": (10, 8, 2)})
        cfg = cfg_for_review()
        result = run_job(request=self._request(), cfg=cfg, analyzer=analyzer,
                         work_root=self.root / "jobs", local_media=self.media)
        self.assertEqual(len(analyzer.calls), 2, f"两个窗口应各调用一次: {analyzer.calls}")
        for row in result.payload["windows"]:
            self.assertEqual(row["quality"], "VALID")
            self.assertEqual(row["resultKind"], "SINGLE_FRAME")
            self.assertNotIn("review", row)

    def test_review_window_makes_three_calls_with_adjacent_frames(self) -> None:
        """FRAME-4 起：REVIEW 窗口用**一次多图请求**（三张图），而不是三次单图调用。"""
        # win-001 coverage 1.0 ⇒ VALID；win-002 代表帧 0.6 ⇒ REVIEW；三图复核 0.8 ⇒ VALID
        analyzer = ScriptedAnalyzer(
            {"000005000": (10, 9, 1), "000015000": (10, 4, 2)},
            window_script={"win-002": (10, 7, 1)})
        cfg = cfg_for_review()
        result = run_job(request=self._request(), cfg=cfg, analyzer=analyzer,
                         work_root=self.root / "jobs", local_media=self.media)
        # 2 次模型请求：win-001 单图 + win-002 多图（一次带三张图）
        self.assertEqual(len(analyzer.window_calls), 1, analyzer.window_calls)
        self.assertEqual(len(analyzer.window_calls[0]), 3, "复核请求必须带三张图")
        self.assertEqual(len(analyzer.calls), 2 + 3, f"1 单图 + 一次三图请求: {analyzer.calls}")
        windows = {row["id"]: row for row in result.payload["windows"]}
        self.assertEqual(windows["win-001"]["quality"], "VALID")
        self.assertEqual(windows["win-002"]["quality"], "VALID")
        self.assertEqual(windows["win-002"]["resultKind"], "WINDOW_ESTIMATE_MULTI_FRAME")
        self.assertEqual(windows["win-002"]["qualityReason"], REASON_REVIEW_CONFIRMED_VALID)
        self.assertEqual(windows["win-002"]["review"]["frameCount"], 3)
        self.assertEqual(windows["win-002"]["review"]["strategy"], "MULTI_IMAGE_SINGLE_REQUEST")
        self.assertEqual(windows["win-002"]["review"]["outcome"], REVIEW_OUTCOME_CONFIRMED_VALID)
        # 三张图必须是三个不同时间点
        self.assertEqual(len(set(analyzer.window_calls[0])), 3, analyzer.window_calls[0])

    def test_insufficient_review_degrades_to_low_quality(self) -> None:
        """三图复核后 coverage 仍不足 ⇒ 诚实降级 LOW_QUALITY。"""
        analyzer = ScriptedAnalyzer(
            {"000005000": (10, 9, 1), "000015000": (10, 4, 2)},
            window_script={"win-002": (10, 4, 2)})   # 三图合并后 coverage 仍 0.6
        cfg = cfg_for_review()
        result = run_job(request=self._request(), cfg=cfg, analyzer=analyzer,
                         work_root=self.root / "jobs", local_media=self.media)
        windows = {row["id"]: row for row in result.payload["windows"]}
        row = windows["win-002"]
        self.assertEqual(row["quality"], "LOW_QUALITY")
        self.assertEqual(row["qualityStatus"], "UNREADABLE")
        self.assertEqual(row["qualityReason"], REASON_REVIEW_INSUFFICIENT)
        self.assertIsNone(row["headUpCount"])
        self.assertIsNone(row["headUpRate"])
        # 复核后仍不可读 ⇒ 不进入课程均值
        self.assertAlmostEqual(result.stats["headUp"]["meanRate"], 0.9)

    def test_review_disabled_keeps_single_call_and_review_state(self) -> None:
        analyzer = ScriptedAnalyzer({"000005000": (10, 4, 2), "000015000": (10, 4, 2)})
        cfg = cfg_for_review(review_enabled=False)
        result = run_job(request=self._request(), cfg=cfg, analyzer=analyzer,
                         work_root=self.root / "jobs", local_media=self.media)
        self.assertEqual(len(analyzer.calls), 2)
        for row in result.payload["windows"]:
            self.assertEqual(row["quality"], "REVIEW")
            self.assertEqual(row["resultKind"], "SINGLE_FRAME")

    def test_sudden_change_window_is_reviewed(self) -> None:
        # win-001 = 0.1（VALID），win-002 = 0.9（VALID）⇒ 突变 0.8 ⇒ 复核
        analyzer = ScriptedAnalyzer(
            {"000005000": (10, 1, 9), "000015000": (10, 9, 1)},
            window_script={"win-002": (10, 9, 1)})
        cfg = cfg_for_review()
        result = run_job(request=self._request(), cfg=cfg, analyzer=analyzer,
                         work_root=self.root / "jobs", local_media=self.media)
        windows = {row["id"]: row for row in result.payload["windows"]}
        self.assertNotIn("review", windows["win-001"], "第一个窗口没有前序值，不应复核")
        self.assertEqual(windows["win-002"]["review"]["reasons"], [REVIEW_REASON_SUDDEN_CHANGE])
        self.assertEqual(len(analyzer.window_calls), 1, "突变复核也是一次多图请求")

    def test_average_calls_per_window_stays_close_to_one(self) -> None:
        """正常情况（多数窗口稳定）下平均"模型请求数"应远低于 2。

        FRAME-4 起一次复核 = 1 次多图请求（带 3 张图），因此按**请求数**计的调用量更低。
        """
        analyzer = ScriptedAnalyzer(
            {"000002500": (10, 8, 2), "000007500": (10, 4, 2), "000012500": (10, 8, 2),
             "000017500": (10, 8, 2)},
            window_script={"win-002": (10, 4, 2)})     # 复核后仍不足 ⇒ 降级
        cfg = cfg_for_review()
        result = run_job(request=self._request(window_ms=5_000, duration_ms=20_000), cfg=cfg,
                         analyzer=analyzer, work_root=self.root / "jobs", local_media=self.media)
        windows = result.payload["windows"]
        self.assertEqual(len(windows), 4)
        requests = 3 + 1        # 3 个稳定窗口各 1 次单图 + 1 次多图复核
        self.assertEqual(len(analyzer.calls), requests + 3, analyzer.calls)
        self.assertEqual(len(analyzer.window_calls), 1)
        self.assertLess(requests / len(windows), 1.25, f"每窗请求数 {requests / len(windows)} 过高")
        reviewed = [w for w in result.payload["warnings"] if w.startswith("MULTI_FRAME")]
        self.assertEqual(reviewed, ["MULTI_FRAME_REVIEW_APPLIED:1"])

    def test_review_frames_are_distinct_timepoints(self) -> None:
        """直接验证抽帧：复核窗口内三帧时间点互不相同、文件名互不相同。

        注：本用例直接调 `_review_frames`（逐帧单图回退路径，返回逐帧计数），
        正式路径见 `test_frame4_multi_image.py`（一次多图请求）。
        """
        plan = plan_frames(start_ms=10_000, end_ms=20_000, window_ms=10_000)[0]
        frames_dir = self.root / "frames"
        frames_dir.mkdir(parents=True, exist_ok=True)
        analyzer = ScriptedAnalyzer({})
        readings = _review_frames(tools=self.tools, media_path=self.media, plan=plan,
                                  frames_dir=frames_dir, cfg=cfg_for_review(),
                                  analyzer=analyzer, media_duration_ms=20_000)
        self.assertEqual(len(readings), 3, "三帧观察窗口（t-500 / t / t+500）")
        names = list(analyzer.calls)
        self.assertEqual(len(set(names)), 3, f"复核帧不得重复同一张图: {names}")
        self.assertTrue(all("r" in name for name in names), names)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
