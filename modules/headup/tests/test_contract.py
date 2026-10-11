"""契约一致性测试：形状、语义、统计固定数值、与主项目冻结 fixture 的逐字段一致。

这些用例回答的问题只有一个：**本模块产出的东西能不能被主项目按 v0.1 契约接收？**
"""

from __future__ import annotations

import copy
import json
import math
import unittest

from headup import definitions, wire
from headup.pipeline import compute_stats
from headup.providers import DEMO_COUNTS, DEMO_RATIOS, FixtureProvider
from headup.config import Config

from support import (DEMO_SHA256, MAIN_PROJECT_FIXTURES, demo_request_doc, load_json,
                     main_project_fixtures_available)

DEMO_CFG = Config(enable_fixture_provider=True,
                  definition_id=definitions.DEFINITION_ID_DEMO,
                  definition_text=definitions.DEFINITION_TEXT_DEMO)


def _demo_request(**overrides):
    doc = demo_request_doc(**overrides)
    return wire.parse_job_request(doc, idempotency_key=doc["clientRequestId"],
                                 allowed_definition_ids=(definitions.DEFINITION_ID_DEMO,),
                                 max_duration_ms=7_200_000, max_windows=20_000)


def _demo_payload(output_mode: str = "COUNTS") -> dict:
    provider = FixtureProvider(DEMO_CFG)
    result = provider.run(_demo_request(output_mode=output_mode), source_url=None)
    return result.payload


class FrozenStatisticsTest(unittest.TestCase):
    """抬头专用文档 §4 的固定验算（60s / 6×10s）。"""

    def test_counts_payload_matches_expected_statistics(self) -> None:
        payload = _demo_payload("COUNTS")
        stats = compute_stats(payload, 60_000)
        head_up = stats["headUp"]
        self.assertEqual(head_up["windowRates"], [0.75, 0.60, 0.0, None, None, 0.50])
        self.assertAlmostEqual(head_up["meanRate"], 0.4625, places=12)
        self.assertEqual(head_up["validDurationMs"], 40_000)
        self.assertAlmostEqual(head_up["validCoverage"], 2 / 3, places=12)
        self.assertEqual(head_up["processedDurationMs"], 60_000)
        self.assertEqual(head_up["processedCoverage"], 1.0)
        self.assertEqual(
            [(c["fromWindowId"], c["toWindowId"]) for c in head_up["changes"]],
            [("win-001", "win-002"), ("win-002", "win-003")],
        )
        self.assertAlmostEqual(head_up["changes"][0]["deltaPercentagePoints"], -15.0, places=9)
        self.assertAlmostEqual(head_up["changes"][1]["deltaPercentagePoints"], -60.0, places=9)

    def test_mean_is_duration_weighted_not_count_summed(self) -> None:
        """v1 口径（时长加权）仍然可复算，并作为 v2 的对照字段保留。

        v0.2 Round 2 起**正式均值**改为 `SUM(up)/SUM(evaluable)`（`meanRate`），
        但契约 v0.1 冻结的 `stats-v1` 口徑（时长加权）继续以
        `v1DurationWeightedMeanRate` 暴露，便于主项目核对口径切换。
        """
        windows = [
            {"id": "win-001", "startMs": 0, "endMs": 50_000, "quality": "VALID",
             "headUpCount": 40, "validObservedCount": 40, "headUpRatio": None},
            {"id": "win-002", "startMs": 50_000, "endMs": 60_000, "quality": "VALID",
             "headUpCount": 1, "validObservedCount": 20, "headUpRatio": None},
        ]
        payload = {"valueMode": "COUNTS", "windows": windows,
                   "processedRanges": [{"startMs": 0, "endMs": 60_000}]}
        stats = compute_stats(payload, 60_000)["headUp"]
        # v1: (1.0*50000 + 0.05*10000)/60000 = 50.5/60
        self.assertAlmostEqual(stats["v1DurationWeightedMeanRate"], 50.5 / 60, places=12)
        # v2: (40+1)/(40+20) = 41/60
        self.assertAlmostEqual(stats["meanRate"], 41 / 60, places=12)

    def test_equal_length_windows_make_both_formulas_agree(self) -> None:
        """演示 fixture 的 6 个窗口等长，因此这组数据上两式同值 0.4625。"""
        payload = _demo_payload("COUNTS")
        lengths = {w["endMs"] - w["startMs"] for w in payload["windows"]}
        denominators = {w["validObservedCount"] for w in payload["windows"]
                        if w["quality"] == "VALID"}
        self.assertEqual(lengths, {10_000})
        self.assertEqual(denominators, {40})
        valid = [w for w in payload["windows"] if w["quality"] == "VALID"]
        count_summed = (sum(w["headUpCount"] for w in valid)
                        / sum(w["validObservedCount"] for w in valid))
        stats = compute_stats(payload, 60_000)["headUp"]
        self.assertAlmostEqual(stats["meanRate"], count_summed, places=12)
        self.assertAlmostEqual(stats["v1DurationWeightedMeanRate"], count_summed, places=12)

    def test_ratio_only_has_no_counts_and_same_mean(self) -> None:
        payload = _demo_payload("RATIO_ONLY")
        for row in payload["windows"]:
            self.assertIsNone(row["headUpCount"])
            self.assertIsNone(row["validObservedCount"])
        stats = compute_stats(payload, 60_000)
        self.assertEqual(stats["headUp"]["windowRates"], [0.75, 0.60, 0.0, None, None, 0.50])
        self.assertAlmostEqual(stats["headUp"]["meanRate"], 0.4625, places=12)

    def test_true_zero_is_not_the_same_as_no_data(self) -> None:
        payload = _demo_payload("COUNTS")
        windows = {w["id"]: w for w in payload["windows"]}
        self.assertEqual(windows["win-003"]["quality"], "VALID")
        self.assertEqual((windows["win-003"]["headUpCount"],
                          windows["win-003"]["validObservedCount"]), (0, 40))
        self.assertEqual(windows["win-004"]["quality"], "NO_SUBJECTS")
        self.assertEqual((windows["win-004"]["headUpCount"],
                          windows["win-004"]["validObservedCount"]), (0, 0))
        self.assertEqual(windows["win-005"]["quality"], "LOW_QUALITY")
        self.assertIsNone(windows["win-005"]["headUpCount"])
        self.assertIsNone(windows["win-005"]["sampleAtMs"])
        rates = compute_stats(payload, 60_000)["headUp"]["windowRates"]
        self.assertEqual(rates[2], 0.0)
        self.assertIsNone(rates[3])
        self.assertIsNone(rates[4])

    def test_adjacent_change_not_computed_across_invalid_window(self) -> None:
        payload = _demo_payload("COUNTS")
        changes = compute_stats(payload, 60_000)["headUp"]["changes"]
        pairs = [(c["fromWindowId"], c["toWindowId"]) for c in changes]
        self.assertNotIn(("win-003", "win-004"), pairs)
        self.assertNotIn(("win-004", "win-005"), pairs)
        self.assertNotIn(("win-005", "win-006"), pairs)


class EnvelopeTest(unittest.TestCase):
    def test_payload_windows_cover_processed_ranges_exactly(self) -> None:
        payload = _demo_payload("COUNTS")
        windows = payload["windows"]
        self.assertEqual(windows[0]["startMs"], 0)
        self.assertEqual(windows[-1]["endMs"], 60_000)
        for left, right in zip(windows, windows[1:]):
            self.assertEqual(left["endMs"], right["startMs"])
        for row in windows:
            if row["quality"] != "LOW_QUALITY":
                self.assertTrue(row["startMs"] <= row["sampleAtMs"] < row["endMs"])

    def test_short_last_window_is_allowed(self) -> None:
        """25 秒媒体 + 10 秒窗 => 末窗 5 秒，仍必须合法。"""
        provider = FixtureProvider(DEMO_CFG)
        request = _demo_request(client_request_id="req-short-001")
        trimmed = wire.JobRequest(
            schema_version=request.schema_version,
            client_request_id=request.client_request_id,
            module_type=request.module_type,
            class_session_id=request.class_session_id,
            media_id=request.media_id,
            media=wire.MediaRef(sha256=request.media.sha256,
                                duration_ms=request.media.duration_ms,
                                mime_type=request.media.mime_type,
                                source_url=request.media.source_url,
                                url_expires_at=request.media.url_expires_at),
            analysis_range_start_ms=0,
            analysis_range_end_ms=25_000,
            parameters=request.parameters,
        )
        result = provider.run(trimmed, source_url=None)
        windows = result.payload["windows"]
        self.assertEqual([(w["startMs"], w["endMs"]) for w in windows],
                         [(0, 10_000), (10_000, 20_000), (20_000, 25_000)])
        wire.validate_windows(windows, bounds=(0, 25_000), window_ms=10_000,
                              processed=[(0, 25_000)], value_mode="COUNTS")

    def test_envelope_round_trip_validates(self) -> None:
        provider = FixtureProvider(DEMO_CFG)
        result = provider.run(_demo_request(), source_url=None)
        envelope = wire.build_envelope(
            job_id="job-1", client_request_id="req-head-up-001",
            provider_id=provider.provider_id, module_version=provider.version,
            class_session_id="session-demo-001", media_id="media-demo-001",
            media_sha256=DEMO_SHA256, duration_ms=60_000,
            analysis_range={"startMs": 0, "endMs": 60_000},
            status=wire.STATUS_SUCCEEDED, revision=1,
            created_at="2026-09-13T03:00:00Z", updated_at="2026-09-13T03:01:00Z",
            payload=result.payload)
        wire.validate_envelope(envelope)
        self.assertEqual(envelope["timeBase"], "MEDIA_START_MS")
        self.assertEqual(envelope["payload"]["sampleMode"], "REPRESENTATIVE_FRAME")
        self.assertIn("非应到或实到人数", envelope["payload"]["denominatorDefinition"])
        self.assertIn("不等于班级应到人数", definitions.DEFINITION_TEXT_REAL)


class RejectsInvalidPayloadTest(unittest.TestCase):
    """契约 §3/§4：下列每一种都必须被拒绝（主项目验收会逐条对照）。"""

    def setUp(self) -> None:
        self.payload = _demo_payload("COUNTS")
        self.bounds = (0, 60_000)
        self.processed = [(0, 60_000)]

    def _expect_invalid(self, mutate) -> None:
        payload = copy.deepcopy(self.payload)
        mutate(payload)
        with self.assertRaises(ValueError):
            wire.validate_windows(payload["windows"], bounds=self.bounds, window_ms=10_000,
                                  processed=self.processed, value_mode=payload["valueMode"])

    def test_numerator_exceeds_denominator(self) -> None:
        self._expect_invalid(lambda p: p["windows"][0].update({"headUpCount": 41}))

    def test_zero_denominator_on_valid_window(self) -> None:
        self._expect_invalid(lambda p: p["windows"][0].update({"validObservedCount": 0}))

    def test_visible_student_count_cannot_be_smaller_than_evaluable_count(self) -> None:
        self._expect_invalid(lambda p: p["windows"][0].update({"visibleStudentCount": 39}))

    def test_ratio_present_in_counts_mode(self) -> None:
        self._expect_invalid(lambda p: p["windows"][0].update({"headUpRatio": 0.75}))

    def test_unknown_quality(self) -> None:
        self._expect_invalid(lambda p: p["windows"][0].update({"quality": "UNKNOWN"}))

    def test_low_quality_window_with_counts(self) -> None:
        self._expect_invalid(lambda p: p["windows"][4].update({"headUpCount": 0}))

    def test_sample_outside_window(self) -> None:
        self._expect_invalid(lambda p: p["windows"][0].update({"sampleAtMs": 10_000}))

    def test_window_start_moved_into_previous_window(self) -> None:
        self._expect_invalid(lambda p: p["windows"][1].update({"startMs": 9_999}))

    def test_window_dropped_creates_gap(self) -> None:
        self._expect_invalid(lambda p: p["windows"].pop())

    def test_window_overlap(self) -> None:
        def mutate(payload: dict) -> None:
            payload["windows"][1]["startMs"] = 5_000
        self._expect_invalid(mutate)

    def test_duplicate_window_id(self) -> None:
        self._expect_invalid(lambda p: p["windows"][1].update({"id": "win-001"}))

    def test_missing_quality_reason(self) -> None:
        self._expect_invalid(lambda p: p["windows"][4].update({"qualityReason": None}))

    def test_ratio_out_of_range(self) -> None:
        payload = _demo_payload("RATIO_ONLY")
        payload["windows"][0]["headUpRatio"] = 1.1
        with self.assertRaises(ValueError):
            wire.validate_windows(payload["windows"], bounds=self.bounds, window_ms=10_000,
                                  processed=self.processed, value_mode="RATIO_ONLY")

    def test_ratio_mode_with_fabricated_counts(self) -> None:
        payload = _demo_payload("RATIO_ONLY")
        payload["windows"][0]["headUpCount"] = 30
        with self.assertRaises(ValueError):
            wire.validate_windows(payload["windows"], bounds=self.bounds, window_ms=10_000,
                                  processed=self.processed, value_mode="RATIO_ONLY")


class RequestValidationTest(unittest.TestCase):
    def _parse(self, doc, **kwargs):
        options = dict(idempotency_key=doc["clientRequestId"],
                       allowed_definition_ids=(definitions.DEFINITION_ID_REAL,),
                       max_duration_ms=7_200_000, max_windows=20_000)
        options.update(kwargs)
        return wire.parse_job_request(doc, **options)

    def _base_doc(self) -> dict:
        return demo_request_doc(definition_id=definitions.DEFINITION_ID_REAL,
                                source_url="https://media.example.com/a.mp4")

    def test_accepts_reference_shape(self) -> None:
        request = self._parse(self._base_doc())
        self.assertEqual(request.parameters.window_ms, 10_000)

    def test_rejects_unsupported_schema_version(self) -> None:
        doc = self._base_doc()
        doc["schemaVersion"] = "2.0"
        with self.assertRaises(wire.ApiError) as ctx:
            self._parse(doc)
        self.assertEqual(ctx.exception.code, "UNSUPPORTED_SCHEMA")
        self.assertEqual(ctx.exception.http_status, 422)

    def test_rejects_wrong_module_type(self) -> None:
        doc = self._base_doc()
        doc["moduleType"] = "TRANSCRIPTION"
        with self.assertRaises(wire.ApiError) as ctx:
            self._parse(doc)
        self.assertEqual(ctx.exception.http_status, 403)

    def test_rejects_uppercase_hash(self) -> None:
        doc = self._base_doc()
        doc["media"]["sha256"] = "A" * 64
        with self.assertRaises(wire.ApiError):
            self._parse(doc)

    def test_rejects_range_beyond_duration(self) -> None:
        doc = self._base_doc()
        doc["analysisRange"]["endMs"] = doc["media"]["durationMs"] + 1
        with self.assertRaises(wire.ApiError):
            self._parse(doc)

    def test_rejects_empty_range(self) -> None:
        doc = self._base_doc()
        doc["analysisRange"] = {"startMs": 5000, "endMs": 5000}
        with self.assertRaises(wire.ApiError):
            self._parse(doc)

    def test_rejects_unknown_output_mode_instead_of_switching(self) -> None:
        doc = self._base_doc()
        doc["parameters"]["outputMode"] = "PERCENT"
        with self.assertRaises(wire.ApiError) as ctx:
            self._parse(doc)
        self.assertEqual(ctx.exception.code, "VALIDATION_FAILED")

    def test_rejects_unregistered_definition_id(self) -> None:
        doc = self._base_doc()
        doc["parameters"]["definitionId"] = "head-up-other-v9"
        with self.assertRaises(wire.ApiError):
            self._parse(doc)

    def test_rejects_mismatched_idempotency_key(self) -> None:
        doc = self._base_doc()
        with self.assertRaises(wire.ApiError) as ctx:
            self._parse(doc, idempotency_key="other-key")
        self.assertEqual(ctx.exception.http_status, 422)

    def test_rejects_non_utc_expiry(self) -> None:
        doc = self._base_doc()
        doc["media"]["urlExpiresAt"] = "2026-09-13T04:00:00+08:00"
        with self.assertRaises(wire.ApiError):
            self._parse(doc)

    def test_fingerprint_ignores_download_credentials(self) -> None:
        first = self._parse(self._base_doc())
        doc = self._base_doc()
        doc["media"]["sourceUrl"] = "https://media.example.com/a.mp4?sign=refreshed"
        doc["media"]["urlExpiresAt"] = "2099-01-02T00:00:00Z"
        second = self._parse(doc)
        self.assertEqual(first.idempotency_fingerprint(), second.idempotency_fingerprint())

    def test_fingerprint_changes_with_parameters(self) -> None:
        first = self._parse(self._base_doc())
        doc = self._base_doc()
        doc["parameters"]["windowMs"] = 5_000
        second = self._parse(doc)
        self.assertNotEqual(first.idempotency_fingerprint(), second.idempotency_fingerprint())

    def test_storage_form_drops_source_url(self) -> None:
        request = self._parse(self._base_doc())
        stored = request.for_storage()
        self.assertNotIn("sourceUrl", stored["media"])
        self.assertNotIn("urlExpiresAt", stored["media"])


@unittest.skipUnless(main_project_fixtures_available(),
                     "主项目冻结 fixture 不在本机（ai_teaching/docs/integration/fixtures）")
class MatchesMainProjectFixturesTest(unittest.TestCase):
    """本模块的合成输出必须逐字段等于主项目冻结样例（否则联调第一轮就会对不上）。"""

    def test_counts_envelope_equals_reference(self) -> None:
        provider = FixtureProvider(DEMO_CFG)
        result = provider.run(_demo_request(), source_url=None)
        reference = load_json(MAIN_PROJECT_FIXTURES / "head-up-success.json")
        envelope = wire.build_envelope(
            job_id=reference["jobId"], client_request_id=reference["clientRequestId"],
            provider_id=reference["providerId"], module_version=reference["moduleVersion"],
            class_session_id=reference["classSessionId"], media_id=reference["mediaId"],
            media_sha256=reference["mediaSha256"], duration_ms=reference["durationMs"],
            analysis_range=reference["analysisRange"], status=reference["status"],
            revision=reference["revision"], created_at=reference["createdAt"],
            updated_at=reference["updatedAt"], payload=result.payload)
        self.assertEqual(envelope, reference)

    def test_ratio_envelope_equals_reference(self) -> None:
        provider = FixtureProvider(DEMO_CFG)
        result = provider.run(_demo_request(client_request_id="req-head-up-ratio-001",
                                            output_mode="RATIO_ONLY"), source_url=None)
        reference = load_json(MAIN_PROJECT_FIXTURES / "head-up-ratio-success.json")
        envelope = wire.build_envelope(
            job_id=reference["jobId"], client_request_id=reference["clientRequestId"],
            provider_id=reference["providerId"], module_version=reference["moduleVersion"],
            class_session_id=reference["classSessionId"], media_id=reference["mediaId"],
            media_sha256=reference["mediaSha256"], duration_ms=reference["durationMs"],
            analysis_range=reference["analysisRange"], status=reference["status"],
            revision=reference["revision"], created_at=reference["createdAt"],
            updated_at=reference["updatedAt"], payload=result.payload)
        self.assertEqual(envelope, reference)

    def test_frozen_statistics_file_agrees(self) -> None:
        expected = load_json(MAIN_PROJECT_FIXTURES / "expected-statistics.json")
        provider = FixtureProvider(DEMO_CFG)
        result = provider.run(_demo_request(), source_url=None)
        stats = compute_stats(result.payload, 60_000)
        self.assertEqual(stats["headUp"]["windowRates"], expected["headUp"]["windowRates"])
        self.assertTrue(math.isclose(stats["headUp"]["meanRate"], expected["headUp"]["meanRate"]))
        self.assertTrue(math.isclose(stats["headUp"]["validCoverage"],
                                     expected["headUp"]["validCoverage"]))
        self.assertEqual(stats["headUp"]["processedDurationMs"],
                         expected["headUp"]["processedDurationMs"])

    def test_demo_observation_table_unchanged(self) -> None:
        """合成观测表一旦改动，主项目冻结样例就会失配——本用例把它钉住。"""
        self.assertEqual(DEMO_COUNTS, [(30, 40), (24, 40), (0, 40), (0, 0), None, (20, 40)])
        self.assertEqual(DEMO_RATIOS, [0.75, 0.60, 0.0, None, None, 0.50])
        self.assertEqual(len(DEMO_COUNTS), 6)


class DeliverableFixtureTest(unittest.TestCase):
    """本仓库 docs/fixtures 下交付的样例必须自洽且满足契约。"""

    def test_module_fixtures_validate(self) -> None:
        from support import MODULE_FIXTURES
        if not MODULE_FIXTURES.is_dir():
            self.skipTest("尚未生成交付 fixture")
        checked = 0
        for path in sorted(MODULE_FIXTURES.glob("*.json")):
            data = json.loads(path.read_text(encoding="utf-8"))
            if data.get("moduleType") != "HEAD_UP" or "status" not in data:
                continue
            wire.validate_envelope(data)
            checked += 1
        self.assertGreaterEqual(checked, 2)

    def test_real_model_fixture_is_self_consistent(self) -> None:
        """真实模型样例：请求/结果一致、统计可独立复算、且必须标明素材性质。"""
        from support import MODULE_FIXTURES
        result_path = MODULE_FIXTURES / "head-up-realclassroom-success.json"
        request_path = MODULE_FIXTURES / "head-up-realclassroom-request.json"
        stats_path = MODULE_FIXTURES / "head-up-realclassroom-stats.json"
        record_path = MODULE_FIXTURES / "realclassroom-run-record.json"
        if not result_path.is_file():
            self.skipTest("尚未生成真实模型样例")
        envelope = load_json(result_path)
        wire.validate_envelope(envelope)
        request = load_json(request_path)
        self.assertEqual(request["media"]["sha256"], envelope["mediaSha256"])
        self.assertEqual(request["media"]["durationMs"], envelope["durationMs"])
        self.assertEqual(request["parameters"]["definitionId"],
                         envelope["payload"]["definitionId"])
        recomputed = compute_stats(envelope["payload"], envelope["durationMs"])
        stored = load_json(stats_path)
        self.assertEqual(recomputed["headUp"]["windowRates"], stored["headUp"]["windowRates"])
        self.assertAlmostEqual(recomputed["headUp"]["meanRate"], stored["headUp"]["meanRate"],
                               places=12)
        record = load_json(record_path)
        self.assertIn("非真实课堂录像", record["media"]["note"])
        self.assertTrue(record["visionModel"])
        self.assertTrue(record["promptVersion"])
        self.assertIs(record["credentialsConfigured"], True)
        self.assertEqual(len(record["perWindowNotes"]), len(envelope["payload"]["windows"]))

    def test_synthetic_fixture_is_labelled(self) -> None:
        from support import MODULE_FIXTURES
        manifest_path = MODULE_FIXTURES / "media-manifest.json"
        if not manifest_path.is_file():
            self.skipTest("尚未生成交付 fixture")
        manifest = load_json(manifest_path)
        self.assertIn("人工合成", manifest["syntheticFixtures"]["note"])
        self.assertIn("不是模型对真实媒体的处理结果", manifest["syntheticFixtures"]["note"])
        self.assertIn("非真实课堂数据", manifest["realFixture"]["note"])


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
