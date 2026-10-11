"""Round 4（v0.2）稳定性与版本固化测试。

验收对照（用户 v0.2 Round 4）：
- 同一结果可以追溯模型与口径版本（model_name / model_version / prompt_version /
  sampling_policy_version / metric_version）
- 存在可重复执行的稳定性实验（脚本 → `.work/stability_experiment.py`；本文件覆盖其统计口径）
- Flash / Plus 不会混入同一条正式曲线（换模型必须留痕并告警）
- 模型随机性被量化（统计量由 counts.summarize 提供，不靠描述）
"""

from __future__ import annotations

import statistics
import unittest
from pathlib import Path

from headup import counts as counts_mod
from headup import prompts, wire
from headup.analyze import QwenVlAnalyzer
from headup.config import Config
from headup.counts import make_counts
from headup.pipeline import (WARN_FALLBACK_MODEL, _generation_policy, classify_window,
                             run_job)
from headup.wire import HeadUpParameters, JobRequest, MediaRef

from support import SYNTHETIC_MEDIA, find_tools, temp_root
from test_round3_review import ScriptedAnalyzer, cfg_for_review


class VersionTraceabilityTest(unittest.TestCase):
    """§2 模型与口径版本固化：五个版本字段必须出现在正式结果里。"""

    def setUp(self) -> None:
        self.cfg = cfg_for_review()

    def test_generation_policy_has_all_five_versions(self) -> None:
        policy = _generation_policy(self.cfg, [])
        for field in ("modelName", "modelVersion", "promptVersion",
                      "samplingPolicyVersion", "metricVersion"):
            self.assertIn(field, policy, field)
            self.assertTrue(policy[field], f"{field} 不能为空")
        self.assertEqual(policy["promptVersion"], prompts.PROMPT_VERSION_STRICT)
        self.assertEqual(policy["metricVersion"], counts_mod.METRIC_VERSION)
        self.assertEqual(policy["samplingPolicyVersion"], counts_mod.SAMPLING_POLICY_VERSION)
        self.assertEqual(policy["modelName"], self.cfg.vision_model)
        self.assertEqual(policy["statisticsVersion"], "stats-v2")

    def test_model_version_note_is_honest_about_missing_snapshot(self) -> None:
        """服务端不返回不可变快照号，必须如实说明而不是伪造一个版本号。"""
        policy = _generation_policy(self.cfg, [])
        self.assertIn("system_fingerprint=null", policy["modelVersionNote"])
        self.assertEqual(policy["modelVersion"], self.cfg.vision_model)

    def test_generation_parameters_recorded(self) -> None:
        cfg = cfg_for_review(vision_temperature=0.0, vision_seed=42,
                             vision_disable_thinking=True)
        policy = _generation_policy(cfg, [])
        self.assertEqual(policy["generationParameters"]["seed"], 42)
        self.assertTrue(policy["generationParameters"]["disableThinking"])
        self.assertEqual(policy["generationParameters"]["temperature"], 0.0)

    def test_seed_and_thinking_default_to_off(self) -> None:
        """默认不发送 seed / thinking 开关（Round 4 实测它们没有稳定性收益）。"""
        cfg = Config()
        self.assertIsNone(cfg.vision_seed)
        self.assertFalse(cfg.vision_disable_thinking)

    def test_windows_with_other_model_are_flagged(self) -> None:
        """换模型必须留痕：出现非主模型的窗口要告警。"""
        cfg = cfg_for_review()
        primary = {"modelUsed": cfg.vision_model}
        fallback = {"modelUsed": cfg.vision_fallback_model}
        from headup.frames import plan_frames

        plans = plan_frames(start_ms=0, end_ms=20_000, window_ms=10_000)
        from headup.pipeline import WindowOutcome

        outcomes = [
            WindowOutcome(plan=plans[0], quality="VALID", report=dict(primary)),
            WindowOutcome(plan=plans[1], quality="VALID", report=dict(fallback)),
        ]
        policy = _generation_policy(cfg, outcomes)
        self.assertEqual(policy["windowsWithOtherModel"], ["win-002"])
        self.assertIn(cfg.vision_fallback_model, policy["modelsUsed"])


class StabilityStatisticsTest(unittest.TestCase):
    """§3 随机性量化：统计口径本身可复算（真实实验见 .work/stability_experiment.py）。"""

    def test_summarize_reports_mean_std_and_range(self) -> None:
        values = [8.0, 8.0, 7.0, 8.0, 7.0]
        summary = counts_mod.summarize({"baseline": [
            make_counts(visible_count=14, head_up_count=int(v), head_down_count=14 - int(v) - 4,
                        unknown_count=4) for v in values]})
        self.assertEqual(summary["head_up_count"]["baseline"], 7.6)
        self.assertAlmostEqual(summary["head_up_count_std"]["baseline"],
                               statistics.stdev(values), places=9)

    def test_identical_readings_have_zero_std(self) -> None:
        same = [make_counts(visible_count=14, head_up_count=8, head_down_count=2,
                            unknown_count=4) for _ in range(5)]
        summary = counts_mod.summarize({"x": same})
        self.assertEqual(summary["head_up_count_std"]["x"], 0.0)
        self.assertEqual(summary["head_up_rate_std"]["x"], 0.0)


@unittest.skipUnless(find_tools() is not None and SYNTHETIC_MEDIA.is_file(),
                     "缺少 ffmpeg 或测试媒体")
class PayloadTraceabilityTest(unittest.TestCase):
    """payload 必须能回答"谁算的、用什么口径算的"，并逐窗记录模型。"""

    def _request(self, duration_ms: int = 20_000) -> JobRequest:
        from headup import definitions

        return JobRequest(schema_version="1.0", client_request_id="req-r4-001",
                          module_type="HEAD_UP", class_session_id="s1", media_id="m1",
                          media=MediaRef(sha256="0" * 64, duration_ms=duration_ms,
                                         mime_type="video/mp4",
                                         source_url="https://media.example.invalid/x.mp4",
                                         url_expires_at="2099-01-01T00:00:00Z"),
                          analysis_range_start_ms=0, analysis_range_end_ms=duration_ms,
                          parameters=HeadUpParameters(window_ms=10_000, output_mode="COUNTS",
                                                      definition_id=definitions.DEFINITION_ID_REAL))

    def test_payload_carries_generation_block_and_per_window_model(self) -> None:
        analyzer = ScriptedAnalyzer({"win-001": (10, 8, 2), "win-002": (10, 8, 2)})
        cfg = cfg_for_review()
        result = run_job(request=self._request(), cfg=cfg, analyzer=analyzer,
                         work_root=temp_root("r4") / "jobs", local_media=SYNTHETIC_MEDIA)
        generation = result.payload["generation"]
        for field in ("modelName", "modelVersion", "promptVersion",
                      "samplingPolicyVersion", "metricVersion", "statisticsVersion"):
            self.assertTrue(generation.get(field), field)
        self.assertEqual(generation["generationParameters"]["seed"], None)
        # 逐窗记录模型来源（脚本化分析器不带 model_used，允许为空但要显式存在该键）
        for row in result.payload["windows"]:
            self.assertIn("modelUsed", row)
            self.assertIn("modelRequestId", row)

    def test_dto_required_fields_present_per_window(self) -> None:
        """主项目 DTO 需要的最小字段集：head_up_rate / coverage / quality。"""
        analyzer = ScriptedAnalyzer({"000005000": (10, 8, 2), "000015000": (10, 4, 2)})
        cfg = cfg_for_review(review_enabled=False)
        result = run_job(request=self._request(), cfg=cfg, analyzer=analyzer,
                         work_root=temp_root("r4b") / "jobs", local_media=SYNTHETIC_MEDIA)
        for row in result.payload["windows"]:
            self.assertIn("headUpRate", row)
            self.assertIn("coverage", row)
            self.assertIn("quality", row)
            self.assertIn("qualityStatus", row)
        # visible=10, up=8, down=2, unknown=0 ⇒ rate 0.8、coverage 1.0
        self.assertAlmostEqual(result.payload["windows"][0]["headUpRate"], 0.8)
        self.assertAlmostEqual(result.payload["windows"][0]["coverage"], 1.0)


class GenerationParameterTransmissionTest(unittest.TestCase):
    """参数只在我们显式配置时才发送到请求体里。"""

    def _body_for(self, cfg: Config) -> dict:
        sent: dict = {}

        class Capture:
            def __init__(self, *args, **kwargs):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self, size=-1) -> bytes:
                import json
                return json.dumps({"choices": [{"message": {"content": "{}"}}],
                                   "model": "m", "id": "req-1"}).encode()

        import headup.vision.qwen_client as vision_mod

        original = vision_mod.urllib.request.urlopen

        def fake_urlopen(request, timeout=None):  # noqa: ANN001
            import json
            sent.update(json.loads(request.data.decode()))
            return Capture()

        vision_mod.urllib.request.urlopen = fake_urlopen
        try:
            analyzer = QwenVlAnalyzer(cfg)
            analyzer._chat(cfg.vision_model, "data:image/jpeg;base64,AAAA")
        finally:
            vision_mod.urllib.request.urlopen = original
        return sent

    def test_default_request_omits_seed_and_thinking(self) -> None:
        body = self._body_for(Config(vision_api_key="k", vision_seed=None,
                                     vision_disable_thinking=False))
        self.assertNotIn("seed", body)
        self.assertNotIn("enable_thinking", body)
        self.assertEqual(body["temperature"], 0.0)

    def test_explicit_parameters_are_sent(self) -> None:
        body = self._body_for(Config(vision_api_key="k", vision_seed=42,
                                     vision_disable_thinking=True, vision_max_tokens=256))
        self.assertEqual(body["seed"], 42)
        self.assertFalse(body["enable_thinking"])
        self.assertEqual(body["max_tokens"], 256)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
