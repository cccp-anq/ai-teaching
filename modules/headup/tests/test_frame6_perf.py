"""FRAME-6 测试：性能与稳定性可观测性 + 可复现基准 + 网格实现归一。

验收（plan §23 FRAME-6 + FRAME-5 遗留 #4）：
- 分阶段耗时、抽帧方式、帧数/字节、视觉调用数（逻辑 + HTTP）、token 全部实测可查；
- 成本只在配置单价时给出，并显式标注"估算 ≠ 账单"；
- 指标不进契约 payload，只通过 GET 的增量字段 `metrics` 暴露（契约字段不变）；
- 批量/逐点两种抽帧都能被分别计数，批量真实回退（首帧不等价）能被看见；
- `headup benchmark` 可复现地测出批量 vs 逐点耗时与等价性，并给出 Decord 决策依据；
- `frames.plan_frames` 不再是第二份网格实现（只做兼容包装）。
"""

from __future__ import annotations

import json
import sqlite3
import unittest
import urllib.request
from pathlib import Path

from headup import benchmark as bench
from headup import definitions, perf, wire
from headup.analyze import HeuristicAnalyzer
from headup.config import Config
from headup.counts import make_counts
from headup.frames import plan_frames
from headup.pipeline import (STAGE_AGGREGATING, STAGE_EXTRACTING_PRIMARY, STAGE_PROBING_VIDEO,
                             run_job)
from headup.server import HeadUpApp, create_server
from headup.service import JobService
from headup.store import Store
from headup.media import FfmpegTools
from headup.video.frame_extractor import FrameExtractor, _mean_pixel_diff, uniform_prefix
from headup.video.sampling import SamplingPolicy, plan_frame_plans
from headup.vision.schema import FrameReading
from headup.wire import HeadUpParameters, JobRequest, MediaRef

from support import SYNTHETIC_MEDIA, TempConfig, find_tools, temp_root
from test_round3_review import ScriptedAnalyzer, cfg_for_review

HAS_MEDIA = find_tools() is not None and SYNTHETIC_MEDIA.is_file()


class FakeClock:
    """可控时钟：让计时断言不依赖真实机器速度。"""

    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class RunMetricsTest(unittest.TestCase):
    def test_stage_timings_accumulate_count_and_max(self) -> None:
        clock = FakeClock()
        metrics = perf.RunMetrics(clock=clock)
        with metrics.stage("A"):
            clock.advance(0.5)
        with metrics.stage("A"):
            clock.advance(1.5)
        with metrics.stage("B"):
            clock.advance(0.25)
        payload = metrics.to_dict()
        self.assertEqual(payload["stageTimingsMs"]["A"],
                         {"count": 2, "totalMs": 2000.0, "meanMs": 1000.0, "maxMs": 1500.0})
        self.assertEqual(payload["stageTimingsMs"]["B"]["count"], 1)
        self.assertEqual(payload["wallClockMs"], 2250.0)
        self.assertEqual(payload["metricsVersion"], perf.METRICS_VERSION)

    def test_disabled_metrics_is_a_noop_and_serializes_to_none(self) -> None:
        metrics = perf.RunMetrics(enabled=False)
        with metrics.stage("A"):
            pass
        metrics.bump(perf.COUNTER_VISION_CALLS)
        metrics.note("x", 1)
        metrics.add_usage({"promptTokens": 10, "completionTokens": 2})
        self.assertEqual(metrics.stages, {})
        self.assertEqual(metrics.counters, {})
        self.assertEqual(metrics.notes, {})
        self.assertEqual(metrics.tokens["promptTokens"], 0)
        self.assertIsNone(perf.metrics_to_dict(metrics))
        self.assertIsNone(perf.metrics_to_dict(perf.null_metrics()))

    def test_usage_only_counts_when_provider_reports_it(self) -> None:
        metrics = perf.RunMetrics()
        metrics.add_usage({"promptTokens": 100, "completionTokens": 20})
        metrics.add_usage({"promptTokens": None, "completionTokens": None})
        metrics.add_usage(None)
        self.assertEqual(metrics.tokens, {"promptTokens": 100, "completionTokens": 20,
                                         "callsWithUsage": 1})

    def test_derived_ratios_are_null_without_windows(self) -> None:
        metrics = perf.RunMetrics()
        self.assertIsNone(metrics.derived()["reviewRatio"])
        metrics.bump(perf.COUNTER_WINDOWS_TOTAL, 4)
        metrics.bump(perf.COUNTER_WINDOWS_REVIEWED, 1)
        metrics.bump(perf.COUNTER_VISION_CALLS, 5)
        metrics.bump(perf.COUNTER_VISION_IMAGES, 7)
        derived = metrics.derived()
        self.assertEqual(derived["reviewRatio"], 0.25)
        self.assertEqual(derived["visionCallsPerWindow"], 1.25)
        self.assertEqual(derived["visionImagesPerWindow"], 1.75)

    def test_dict_is_json_serializable(self) -> None:
        metrics = perf.RunMetrics()
        metrics.bump("x")
        metrics.add_disk(perf.DISK_PRIMARY_FRAME_BYTES, 10)
        json.dumps(metrics.to_dict(), ensure_ascii=False)

    def test_measure_files_counts_and_tolerates_missing(self) -> None:
        root = temp_root("f6files")
        good = root / "a.jpg"
        good.write_bytes(b"x" * 25)
        stats = perf.measure_files([good, root / "missing.jpg", "not-a-path?"])
        self.assertEqual(stats.files, 1)
        self.assertEqual(stats.bytes, 25)
        self.assertEqual(stats.missing, 2, "不存在的路径只计数，不抛错")


class CostModelTest(unittest.TestCase):
    def test_no_price_configured_means_no_cost_number(self) -> None:
        model = perf.CostModel()
        self.assertFalse(model.configured)
        self.assertIsNone(model.estimate(1000, 200))
        cfg = Config()
        metrics = perf.RunMetrics()
        metrics.add_usage({"promptTokens": 1000, "completionTokens": 200})
        self.assertIsNone(perf.estimate_cost(cfg, metrics))

    def test_configured_price_is_pure_arithmetic_and_labelled_as_estimate(self) -> None:
        model = perf.CostModel(currency="CNY", prompt_price_per_1k=0.002,
                               completion_price_per_1k=0.008)
        estimate = model.estimate(1500, 250)
        self.assertAlmostEqual(estimate["estimatedAmount"], 0.002 * 1.5 + 0.008 * 0.25, places=9)
        self.assertEqual(estimate["currency"], "CNY")
        self.assertEqual(estimate["unitPrices"],
                         {"promptPer1KTokens": 0.002, "completionPer1KTokens": 0.008})
        self.assertIn("不是账单", estimate["note"])

    def test_config_prices_flow_into_estimate(self) -> None:
        cfg = Config(vision_price_prompt_per_1k=0.01, vision_price_completion_per_1k=0.02,
                     cost_currency="USD")
        metrics = perf.RunMetrics()
        metrics.add_usage({"promptTokens": 2000, "completionTokens": 500})
        estimate = perf.estimate_cost(cfg, metrics)
        self.assertAlmostEqual(estimate["estimatedAmount"], 0.03, places=9)
        self.assertEqual(estimate["currency"], "USD")


class _RecordingAnalyzer:
    """记录每次逻辑调用，返回带 token 追溯信息的观测（不碰网络）。"""

    name = "recording"
    version = "test-1"
    prompt_version = "headup-strict-v0.2"

    def __init__(self, *, multi_image: bool = True, model: str = "fake-model") -> None:
        self.calls: list[str] = []
        self.window_calls: list[list[str]] = []
        self.model = model
        if multi_image:
            self.analyze_window = self._analyze_window   # type: ignore[attr-defined]

    def _reading(self, *, frame_count: int = 1) -> FrameReading:
        counts = make_counts(visible_count=10, head_up_count=9, head_down_count=1,
                             unknown_count=0, confidence=0.9, strict=True)
        return FrameReading(head_up=9, head_down=1, unknown=0, legible_ratio=1.0, confidence=0.9,
                            analysis_notes=[], raw="{}", counts=counts, model_used=self.model,
                            trace={"requestId": "r1", "promptTokens": 1200,
                                   "completionTokens": 40},
                            frame_count=frame_count)

    def analyze(self, image_path: Path) -> FrameReading:
        self.calls.append(str(image_path))
        return self._reading()

    def _analyze_window(self, image_paths: list[Path]) -> FrameReading:
        self.window_calls.append([str(item) for item in image_paths])
        return self._reading(frame_count=len(image_paths))


class CountingAnalyzerTest(unittest.TestCase):
    def test_counts_logic_calls_images_models_and_tokens(self) -> None:
        metrics = perf.RunMetrics()
        inner = _RecordingAnalyzer()
        analyzer = perf.CountingAnalyzer(inner, metrics)
        analyzer.analyze(Path("frame-000005000.jpg"))
        analyzer.set_phase(perf.PHASE_REVIEW)          # pipeline 复核前会这样标注
        analyzer.analyze_window([Path("a.jpg"), Path("b.jpg"), Path("c.jpg")])
        self.assertEqual(metrics.counters[perf.COUNTER_VISION_CALLS], 2)
        self.assertEqual(metrics.counters[perf.COUNTER_VISION_IMAGES], 4)
        self.assertEqual(metrics.counters[perf.COUNTER_VISION_MULTI_IMAGE_CALLS], 1)
        self.assertEqual(metrics.models, {"fake-model": 2})
        self.assertEqual(metrics.tokens["promptTokens"], 2400)
        self.assertEqual(metrics.stages[perf.STAGE_VISION_PRIMARY].count, 1)
        self.assertEqual(metrics.stages[perf.STAGE_VISION_REVIEW].count, 1)

    def test_phase_switches_review_stage(self) -> None:
        metrics = perf.RunMetrics()
        analyzer = perf.CountingAnalyzer(_RecordingAnalyzer(multi_image=False), metrics)
        analyzer.set_phase(perf.PHASE_REVIEW)
        analyzer.analyze(Path("a.jpg"))
        self.assertEqual(metrics.stages[perf.STAGE_VISION_REVIEW].count, 1)
        self.assertNotIn(perf.STAGE_VISION_PRIMARY, metrics.stages)

    def test_wrapper_only_exposes_analyze_window_when_inner_supports_it(self) -> None:
        metrics = perf.RunMetrics()
        with_multi = perf.CountingAnalyzer(_RecordingAnalyzer(multi_image=True), metrics)
        without = perf.CountingAnalyzer(_RecordingAnalyzer(multi_image=False), metrics)
        self.assertTrue(callable(getattr(with_multi, "analyze_window", None)))
        self.assertFalse(callable(getattr(without, "analyze_window", None)),
                         "包装器不得凭空声称支持多图（pipeline 靠 callable 判断能力）")

    def test_transport_counters_are_snapshotted_from_client(self) -> None:
        class FakeClient(_RecordingAnalyzer):
            def __init__(self) -> None:
                super().__init__()
                self.request_count = 3
                self.request_notes = ["HTTP 429 model=fake"]

        metrics = perf.RunMetrics()
        analyzer = perf.CountingAnalyzer(FakeClient(), metrics)
        analyzer.analyze(Path("a.jpg"))
        self.assertEqual(metrics.notes["visionRequests"], 3)
        self.assertEqual(metrics.notes["visionRequestNotes"], ["HTTP 429 model=fake"])

    def test_name_and_version_pass_through(self) -> None:
        metrics = perf.RunMetrics()
        analyzer = perf.CountingAnalyzer(_RecordingAnalyzer(), metrics)
        self.assertEqual(analyzer.name, "recording")
        self.assertEqual(analyzer.version, "test-1")


def make_request(*, client_request_id: str = "req-f6-001", duration_ms: int = 20_000,
                 window_ms: int = 10_000) -> JobRequest:
    return JobRequest(
        schema_version="1.0", client_request_id=client_request_id, module_type="HEAD_UP",
        class_session_id="s1", media_id="m1",
        media=MediaRef(sha256="0" * 64, duration_ms=duration_ms, mime_type="video/mp4",
                       source_url="https://media.example.invalid/x.mp4",
                       url_expires_at="2099-01-01T00:00:00Z"),
        analysis_range_start_ms=0, analysis_range_end_ms=duration_ms,
        parameters=HeadUpParameters(window_ms=window_ms, output_mode="COUNTS",
                                    definition_id=definitions.DEFINITION_ID_REAL))


@unittest.skipUnless(HAS_MEDIA, "缺少 ffmpeg 或测试媒体")
class PipelineMetricsTest(unittest.TestCase):
    """端到端：指标来自真实抽帧 + 脚本化分析器（确定性）。"""

    def setUp(self) -> None:
        self.root = temp_root("f6p")

    def _run(self, *, batch: bool, request_id: str, analyzer=None):
        cfg = cfg_for_review(work_dir=str(self.root / f"work-{request_id}"),
                             sampling_use_batch_extraction=batch,
                             frames_retain=1)
        analyzer = analyzer or ScriptedAnalyzer({"000005000": (10, 9, 1),
                                                 "000015000": (10, 9, 1)})
        result = run_job(request=make_request(client_request_id=request_id), cfg=cfg,
                         analyzer=analyzer, work_root=self.root / "jobs",
                         local_media=SYNTHETIC_MEDIA, job_id=f"task-{request_id}")
        return result, cfg

    def test_metrics_record_stages_counters_disk_and_deterministic_payload(self) -> None:
        result, cfg = self._run(batch=False, request_id="seek")
        metrics = result.metrics
        self.assertIsNotNone(metrics, "默认开启指标")
        self.assertEqual(metrics["counters"][perf.COUNTER_WINDOWS_TOTAL], 2)
        self.assertEqual(metrics["counters"][perf.COUNTER_FRAMES_PRIMARY], 2)
        self.assertEqual(metrics["counters"][perf.COUNTER_VISION_CALLS], 2)
        self.assertEqual(metrics["counters"][perf.COUNTER_EXTRACTION_SEEK_FRAMES], 2)
        self.assertEqual(metrics["notes"]["extractionMode"], "SEEK")
        self.assertGreater(metrics["diskBytes"][perf.DISK_PRIMARY_FRAME_BYTES], 0)
        for stage in (perf.STAGE_DOWNLOAD, STAGE_PROBING_VIDEO, STAGE_EXTRACTING_PRIMARY,
                      STAGE_AGGREGATING, perf.STAGE_CLEANUP):
            if stage == perf.STAGE_DOWNLOAD:
                continue                     # 本地文件不下载
            self.assertIn(stage, metrics["stageTimingsMs"])
        self.assertIsNone(metrics["cost"], "未配置单价 ⇒ 不给金额")
        self.assertEqual(metrics["notes"]["mediaSource"], "LOCAL_FILE")
        self.assertEqual(metrics["notes"]["sampleCount"], 2)
        # 指标不进契约 payload（payload 保持确定性）
        self.assertNotIn("metrics", result.payload)
        self.assertNotIn("metrics", result.payload["generation"])
        json.dumps(result.payload, ensure_ascii=False)
        json.dumps(metrics, ensure_ascii=False)

    def test_batch_mode_is_counted_as_batch_and_matches_seek_payload(self) -> None:
        seek_result, _ = self._run(batch=False, request_id="seek2")
        batch_result, _ = self._run(batch=True, request_id="batch2")
        batch_metrics = batch_result.metrics
        self.assertEqual(batch_metrics["notes"]["extractionMode"], "BATCH")
        self.assertEqual(batch_metrics["counters"][perf.COUNTER_EXTRACTION_BATCH_RUNS], 1)
        self.assertNotIn(perf.COUNTER_EXTRACTION_BATCH_FALLBACKS, batch_metrics["counters"])
        # 两种抽帧方式产出的统计必须一致（否则说明抽帧语义变了）
        self.assertEqual(batch_result.stats["headUp"]["meanRate"],
                         seek_result.stats["headUp"]["meanRate"])
        for left, right in zip(batch_result.payload["windows"], seek_result.payload["windows"]):
            self.assertEqual((left["quality"], left["headUpCount"], left["validObservedCount"]),
                             (right["quality"], right["headUpCount"], right["validObservedCount"]))

    def test_review_counted_once_and_multi_image_flagged(self) -> None:
        # coverage 0.6 ⇒ REVIEW ⇒ 三图复核
        analyzer = ScriptedAnalyzer({"000005000": (10, 4, 2), "000015000": (10, 9, 1)},
                                    window_script={"win-001": (10, 9, 1)})
        result, _ = self._run(batch=False, request_id="review", analyzer=analyzer)
        counters = result.metrics["counters"]
        self.assertEqual(counters[perf.COUNTER_WINDOWS_REVIEWED], 1)
        self.assertEqual(counters[perf.COUNTER_WINDOWS_REVIEW_APPLIED], 1)
        self.assertEqual(counters[perf.COUNTER_VISION_MULTI_IMAGE_CALLS], 1)
        self.assertEqual(counters[perf.COUNTER_FRAMES_REVIEW], 3)
        self.assertTrue(result.metrics["diskBytes"][perf.DISK_REVIEW_FRAME_BYTES] > 0)
        self.assertEqual(result.metrics["stageTimingsMs"][perf.STAGE_VISION_REVIEW]["count"], 1)
        self.assertEqual(result.metrics["derived"]["reviewRatio"], 0.5)

    def test_metrics_can_be_disabled(self) -> None:
        cfg = cfg_for_review(work_dir=str(self.root / "off"), perf_metrics_enabled=False)
        analyzer = ScriptedAnalyzer({"000005000": (10, 9, 1), "000015000": (10, 9, 1)})
        result = run_job(request=make_request(client_request_id="off"), cfg=cfg,
                         analyzer=analyzer, work_root=self.root / "jobs",
                         local_media=SYNTHETIC_MEDIA)
        self.assertIsNone(result.metrics)

    def test_failed_run_still_records_error_notes(self) -> None:
        cfg = cfg_for_review(work_dir=str(self.root / "err"))

        class ExplodingAnalyzer:
            name = "boom"
            version = "test"
            prompt_version = "headup-strict-v0.2"

            def analyze(self, image_path):
                raise wire.TaskError("VISION_PROVIDER_ERROR", "模拟模型故障", retryable=True)

        metrics = perf.RunMetrics()
        with self.assertRaises(wire.TaskError):
            run_job(request=make_request(client_request_id="err"), cfg=cfg,
                    analyzer=ExplodingAnalyzer(), work_root=self.root / "jobs",
                    local_media=SYNTHETIC_MEDIA, metrics=metrics)
        payload = metrics.to_dict()
        # 逐窗分析失败被如实计数；全部窗口不可判读 ⇒ 任务按契约显式失败（不造一串 0）
        self.assertEqual(payload["notes"]["errorCode"], "PROCESSING_FAILED")
        self.assertEqual(payload["counters"][perf.COUNTER_ANALYSIS_FAILURES], 2)
        self.assertIn(STAGE_EXTRACTING_PRIMARY, payload["stageTimingsMs"])


class StoreMetricsTest(unittest.TestCase):
    def test_metrics_roundtrip_and_migration_adds_column(self) -> None:
        root = temp_root("f6store")
        db = root / "old.sqlite3"
        # 造一个"FRAME-5 时代"的库：结构齐全，但没有 FRAME-6 的 metrics_json 列
        conn = sqlite3.connect(db)
        conn.executescript("""
            CREATE TABLE head_up_job (
                job_id TEXT PRIMARY KEY, client_request_id TEXT NOT NULL UNIQUE,
                fingerprint TEXT NOT NULL, module_type TEXT NOT NULL, provider_id TEXT NOT NULL,
                module_version TEXT NOT NULL, class_session_id TEXT NOT NULL,
                media_id TEXT NOT NULL, media_sha256 TEXT NOT NULL, duration_ms INTEGER NOT NULL,
                analysis_start_ms INTEGER NOT NULL, analysis_end_ms INTEGER NOT NULL,
                window_ms INTEGER NOT NULL, output_mode TEXT NOT NULL, definition_id TEXT NOT NULL,
                request_json TEXT NOT NULL, media_source_url TEXT, media_url_expires_at TEXT,
                status TEXT NOT NULL, revision INTEGER NOT NULL, envelope_json TEXT,
                error_code TEXT, error_message TEXT, error_retryable INTEGER,
                attempts INTEGER NOT NULL DEFAULT 0, lease_until REAL,
                created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                current_stage TEXT, error_stage TEXT
            );
        """)
        conn.execute("INSERT INTO head_up_job (job_id, client_request_id, fingerprint,"
                     " module_type, provider_id, module_version, class_session_id, media_id,"
                     " media_sha256, duration_ms, analysis_start_ms, analysis_end_ms, window_ms,"
                     " output_mode, definition_id, request_json, status, revision, created_at,"
                     " updated_at) VALUES ('job-old','req-old','fp','HEAD_UP','p','v','s','m',"
                     " 'x',1000,0,1000,1000,'COUNTS','real','{}','SUCCEEDED',1,'t','t')")
        conn.commit()
        conn.close()

        store = Store(str(db))
        with sqlite3.connect(db) as check:
            columns = {row[1] for row in check.execute("PRAGMA table_info(head_up_job)")}
        self.assertIn("metrics_json", columns, "老库必须能幂等补列")
        self.assertIsNone(store.metrics_of("job-old"))
        store.set_metrics("job-old", {"metricsVersion": perf.METRICS_VERSION,
                                      "wallClockMs": 12.5})
        self.assertEqual(store.metrics_of("job-old")["wallClockMs"], 12.5)
        store.set_metrics("job-old", None)       # None 不覆盖
        self.assertEqual(store.metrics_of("job-old")["wallClockMs"], 12.5)
        self.assertIsNone(store.metrics_of("job-unknown"))


@unittest.skipUnless(HAS_MEDIA, "缺少 ffmpeg 或测试媒体")
class HttpMetricsFieldTest(unittest.TestCase):
    """GET 增量字段 metrics：契约字段不变、指标可查。"""

    def setUp(self) -> None:
        self.root = temp_root("f6http")
        self.cfg = TempConfig(self.root, vision_provider="heuristic",
                              heuristic_synthetic=True,
                              sampling_use_batch_extraction=True).cfg
        self.service = JobService(self.cfg, Store(self.cfg.db_path))
        self.service.start()
        self.server = create_server(self.cfg, HeadUpApp(self.cfg, self.service))
        import threading

        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self) -> None:
        self.server.shutdown()
        # shutdown() 只停 serve_forever；不 server_close() 会留下未关闭的监听 socket
        # （整轮套件里表现为一条 ResourceWarning）
        self.server.server_close()
        self.service.stop()

    def test_get_returns_metrics_without_touching_contract_fields(self) -> None:
        from support import LocalMediaServer, request_doc, sha256_of

        media = LocalMediaServer(SYNTHETIC_MEDIA)
        try:
            doc = request_doc(client_request_id="req-f6-http", class_session_id="session-real-001",
                              media_id="media-real-001", sha256=sha256_of(SYNTHETIC_MEDIA),
                              duration_ms=20_000, source_url=media.url, end_ms=20_000)
            body = json.dumps(doc).encode("utf-8")
            post = urllib.request.Request(f"{self.base}/api/v1/jobs", data=body, method="POST",
                                          headers={"Content-Type": "application/json",
                                                   "Authorization": "Bearer test-token",
                                                   "Idempotency-Key": doc["clientRequestId"]})
            with urllib.request.urlopen(post, timeout=60) as response:
                envelope = json.loads(response.read().decode("utf-8"))
            job_id = envelope["jobId"]
            deadline = 120
            import time

            started = time.time()
            while envelope["status"] not in wire.TERMINAL_STATUSES and time.time() - started < deadline:
                time.sleep(0.5)
                get = urllib.request.Request(f"{self.base}/api/v1/jobs/{job_id}",
                                             headers={"Authorization": "Bearer test-token"})
                with urllib.request.urlopen(get, timeout=30) as response:
                    envelope = json.loads(response.read().decode("utf-8"))
            self.assertEqual(envelope["status"], "SUCCEEDED")
            # 契约校验只看契约字段：增量字段不能破坏信封合法性
            contract = {key: value for key, value in envelope.items()
                        if key not in ("progress", "samples", "metrics")}
            wire.validate_envelope(contract)
            self.assertIn("metrics", envelope)
            metrics = envelope["metrics"]
            self.assertEqual(metrics["counters"][perf.COUNTER_WINDOWS_TOTAL], 2)
            self.assertEqual(metrics["counters"][perf.COUNTER_VISION_CALLS], 2)
            self.assertEqual(metrics["notes"]["extractionMode"], "BATCH")
            self.assertIsNone(metrics["cost"])
        finally:
            media.close()


@unittest.skipUnless(HAS_MEDIA, "缺少 ffmpeg 或测试媒体")
class BenchmarkCommandTest(unittest.TestCase):
    """FRAME-6 基准：两种抽帧都测、等价性有证据、结论由数据生成。"""

    def setUp(self) -> None:
        self.root = temp_root("f6bench")

    def _cfg(self, **overrides) -> Config:
        tools = find_tools()
        base = dict(work_dir=str(self.root / "work"))
        if tools:
            base["ffmpeg_path"] = tools[0]
            base["ffprobe_path"] = tools[1]
        base.update(overrides)
        return Config(**base)

    def test_extraction_benchmark_measures_both_modes_and_equivalence(self) -> None:
        report = bench.run_extraction_benchmark(
            self._cfg(), SYNTHETIC_MEDIA, window_ms=10_000, max_points=2, repeats=2,
            out_dir=self.root / "extract")
        self.assertEqual(report["samplePoints"], 2)
        self.assertEqual(report["timestamps"], [5_000, 15_000])
        for mode in (bench.MODE_SEEK, bench.MODE_BATCH):
            summary = report["extraction"][mode]
            self.assertEqual(summary["ok"], 2, f"{mode} 两次重复都应成功")
            self.assertEqual(summary["frames"], 2)
            self.assertGreater(summary["bytes"], 0)
            self.assertGreater(summary["secondsPerFrame"]["median"], 0)
        self.assertTrue(report["equivalence"]["comparable"])
        self.assertTrue(report["equivalence"]["allEquivalent"],
                        "批量与逐点在测试媒体上必须等价")
        self.assertLessEqual(report["equivalence"]["maxMeanPixelDiff"],
                            report["equivalence"]["tolerance"])
        self.assertTrue(any("Decord" in line for line in report["conclusions"]))
        self.assertTrue(any("加速" in line for line in report["conclusions"]))
        evidence = report["timestampEvidence"]
        self.assertEqual(evidence["seekSampled"]["count"], 2)
        self.assertLessEqual(evidence["seekSampled"]["maxOffsetMs"], 200.0)
        self.assertTrue(any("时间点证据" in line for line in report["conclusions"]))

    def test_sample_grid_every_keeps_uniform_spacing(self) -> None:
        from headup.video.probe import probe_video

        meta = probe_video(SYNTHETIC_MEDIA, self._cfg())
        full = bench.sample_grid(meta, window_ms=2_000)
        sparse = bench.sample_grid(meta, window_ms=2_000, every=3)
        self.assertEqual(sparse, full[::3])
        steps = {b - a for a, b in zip(sparse, sparse[1:])}
        self.assertEqual(len(steps), 1, "抽稀后的子集必须保持等间隔")

    def test_build_report_carries_caveats_and_never_hides_missing_cost(self) -> None:
        report = bench.build_report(
            extraction={"conclusions": ["批量更快"], "extraction": {}, "timestamps": []},
            pipeline={"wallClockSeconds": 1.0, "windows": 2,
                      "analyzer": {"name": "heuristic", "synthetic": True},
                      "metrics": {"counters": {perf.COUNTER_VISION_CALLS: 2},
                                  "derived": {"reviewRatio": 0.0}, "tokens": {},
                                  "cost": None}},
            config={"windowMs": 10_000})
        self.assertEqual(report["benchmarkVersion"], bench.BENCHMARK_VERSION)
        self.assertTrue(any("未配置单价" in line for line in report["conclusions"]))
        self.assertTrue(report["caveats"], "报告必须带前提说明")
        json.dumps(report, ensure_ascii=False)

    def test_non_uniform_grid_is_split_and_reported(self) -> None:
        """末窗短导致的不等间隔：基准必须显示"批量前缀 + 尾部逐点"，不得假装整段批量。"""
        report = bench.run_extraction_benchmark(
            self._cfg(), SYNTHETIC_MEDIA, timestamps=[1_000, 5_000, 9_000, 12_000],
            modes=(bench.MODE_BATCH,), out_dir=self.root / "tail-batch")
        summary = report["extraction"][bench.MODE_BATCH]
        self.assertEqual(summary["failed"], 0)
        run = summary["runs"][0]
        self.assertEqual(run["extractorMode"], "BATCH+SEEK_TAIL")
        self.assertEqual(run["gridSplit"], {"batchPoints": 3, "seekPoints": 1})
        self.assertEqual(run["frames"], 4)
        self.assertTrue(any("尾部逐点" in line for line in report["conclusions"]))

    def test_failed_extraction_is_reported_not_hidden(self) -> None:
        """抽帧失败（媒体不存在）必须如实进报告：error 有值、帧数为 0。"""
        missing = self.root / "no-such-media.mp4"
        run = bench._run_extraction_once(self._cfg(), FfmpegTools.locate(self._cfg()), missing,
                                         [1_000, 2_000], bench.MODE_BATCH,
                                         self.root / "missing", duration_ms=10_000)
        self.assertIsNotNone(run.error)
        self.assertEqual(run.frames, 0)
        self.assertGreater(run.seconds, 0)


@unittest.skipUnless(HAS_MEDIA, "缺少 ffmpeg 或测试媒体")
class GridExtractionTest(unittest.TestCase):
    """FRAME-6 发现的真问题：真实网格在"末窗短"时不是等间隔，整段批量会被正确拒绝，
    但整节课退回逐点 seek 又慢到不可用 ⇒ 等间隔前缀批量 + 尾部少量点逐点，网格不变。"""

    def setUp(self) -> None:
        self.root = temp_root("f6grid")
        tools = find_tools()
        assert tools is not None
        self.cfg = Config(ffmpeg_path=tools[0], ffprobe_path=tools[1],
                          work_dir=str(self.root / "work"))
        self.extractor = FrameExtractor(self.cfg, FfmpegTools.locate(self.cfg), SYNTHETIC_MEDIA,
                                        duration_ms=20_000)

    def test_uniform_grid_uses_batch_only(self) -> None:
        samples = self.extractor.extract_grid([5_000, 15_000], self.root / "uniform")
        self.assertEqual([sample.timestamp_ms for sample in samples], [5_000, 15_000])
        self.assertEqual(self.extractor.last_mode, "BATCH")
        self.assertEqual(self.extractor.last_grid_split, {"batchPoints": 2, "seekPoints": 0})

    def test_short_last_window_is_split_without_moving_any_timestamp(self) -> None:
        timestamps = [5_000, 15_000, 19_000]      # 最后一点与前面不同间隔（模拟末窗短）
        samples = self.extractor.extract_grid(timestamps, self.root / "split")
        self.assertEqual([sample.timestamp_ms for sample in samples], timestamps)
        self.assertEqual(self.extractor.last_mode, "BATCH+SEEK_TAIL")
        self.assertEqual(self.extractor.last_grid_split, {"batchPoints": 2, "seekPoints": 1})
        for sample in samples:
            self.assertTrue(sample.image_path.is_file(), sample.image_path)

    def test_every_non_uniform_grid_still_gets_the_longest_batch_prefix(self) -> None:
        timestamps = [1_000, 5_000, 9_000, 12_000]     # 前缀 [1000,5000,9000] 等间隔
        samples = self.extractor.extract_grid(timestamps, self.root / "seek")
        self.assertEqual([sample.timestamp_ms for sample in samples], timestamps)
        self.assertEqual(self.extractor.last_mode, "BATCH+SEEK_TAIL")
        self.assertEqual(self.extractor.last_grid_split, {"batchPoints": 3, "seekPoints": 1})

    def test_single_point_grid_uses_seek(self) -> None:
        samples = self.extractor.extract_grid([5_000], self.root / "single")
        self.assertEqual([sample.timestamp_ms for sample in samples], [5_000])
        self.assertEqual(self.extractor.last_mode, "SEEK")
        self.assertEqual(self.extractor.last_grid_split, {"batchPoints": 0, "seekPoints": 1})

    def test_split_result_equals_full_seek_result(self) -> None:
        """拆分不得改变画面：等间隔前缀 + 尾部逐点 与 全逐点 必须逐张等价。"""
        timestamps = [5_000, 15_000, 19_000]
        split = self.extractor.extract_grid(timestamps, self.root / "cmp-split")
        plain = FrameExtractor(self.cfg, FfmpegTools.locate(self.cfg), SYNTHETIC_MEDIA,
                               duration_ms=20_000)
        reference = [plain.extract_at(ts, self.root / "cmp-seek") for ts in timestamps]
        for left, right in zip(split, reference):
            difference = _mean_pixel_diff(left.image_path, right.image_path)
            self.assertLessEqual(difference, 2.0,
                                 f"{left.timestamp_ms}ms 处两种抽帧方式画面不等价：{difference}")

    def test_uniform_prefix_splits_only_on_step_change(self) -> None:
        self.assertEqual(uniform_prefix([5_000, 15_000, 25_000, 27_002_233]),
                         ([5_000, 15_000, 25_000], [27_002_233]))
        self.assertEqual(uniform_prefix([5_000]), ([5_000], []))
        self.assertEqual(uniform_prefix([]), ([], []))

    def test_extract_at_is_frame_accurate_within_one_frame_interval(self) -> None:
        """FRAME-6：输入侧 seek（accurate_seek）同样是帧精确 —— 用实际解码 pts 证明。"""
        evidence = self.extractor.decoded_timestamps([5_000, 15_000, 19_000])
        self.assertEqual(evidence["count"], 3)
        for point in evidence["points"]:
            self.assertIsNotNone(point["offsetMs"], point)
            # 测试媒体 5fps ⇒ 帧间隔 200ms；偏移必须落在一帧之内
            self.assertLessEqual(point["offsetMs"], 200.0,
                                 f"{point['timestampMs']}ms 处解码帧偏移过大")

    def test_decoded_timestamps_batch_lands_on_the_grid(self) -> None:
        evidence = self.extractor.decoded_timestamps([5_000, 15_000], batch=True)
        self.assertEqual(evidence["batch"], True)
        self.assertEqual([point["timestampMs"] for point in evidence["points"]], [5_000, 15_000])
        for point in evidence["points"]:
            self.assertLessEqual(abs(point["offsetMs"]), 200.0, point)


@unittest.skipUnless(HAS_MEDIA, "缺少 ffmpeg 或测试媒体")
class ShortLastWindowPipelineTest(unittest.TestCase):
    """端到端：25 秒媒体 + 10 秒窗口 ⇒ 网格 [5000, 15000, 22500]（末点间隔不同）。

    批量路径必须仍可用（批量前缀 + 尾部逐点），且指标如实记录拆分。
    """

    def test_pipeline_uses_batch_prefix_and_counts_tail_as_seek(self) -> None:
        from support import make_test_media

        root = temp_root("f6short")
        media = make_test_media(root, duration_ms=25_000)
        cfg = cfg_for_review(work_dir=str(root / "work"), sampling_use_batch_extraction=True)
        analyzer = ScriptedAnalyzer({"000005000": (10, 9, 1), "000015000": (10, 9, 1),
                                     "000022500": (10, 9, 1)})
        result = run_job(request=make_request(client_request_id="short-last", duration_ms=25_000),
                         cfg=cfg, analyzer=analyzer, work_root=root / "jobs", local_media=media)
        metrics = result.metrics
        self.assertEqual(metrics["notes"]["extractionMode"], "BATCH+SEEK_TAIL")
        self.assertEqual(metrics["notes"]["extractionGridSplit"],
                         {"batchPoints": 2, "seekPoints": 1})
        self.assertEqual(metrics["counters"][perf.COUNTER_EXTRACTION_BATCH_RUNS], 1)
        self.assertEqual(metrics["counters"][perf.COUNTER_EXTRACTION_SEEK_FRAMES], 1)
        self.assertEqual(metrics["counters"][perf.COUNTER_WINDOWS_TOTAL], 3)
        self.assertEqual([plan["sampleAtMs"] for plan in result.payload["windows"]],
                         [5_000, 15_000, 22_500], "采样点/网格不得因为拆分而移动")


class PlanFramesDelegationTest(unittest.TestCase):
    """FRAME-5 遗留 #4：网格实现只剩一份（SamplingPolicy）。"""

    def test_wrapper_delegates_to_sampling_policy(self) -> None:
        for case in (dict(start_ms=0, end_ms=60_000, window_ms=10_000, position="middle"),
                     dict(start_ms=3_000, end_ms=20_000, window_ms=7_000, position="end")):
            policy = SamplingPolicy(window_ms=case["window_ms"], position=case["position"])
            expected = plan_frame_plans(policy, start_ms=case["start_ms"], end_ms=case["end_ms"])
            self.assertEqual(plan_frames(**case), expected)

    def test_degenerate_and_invalid_ranges(self) -> None:
        self.assertEqual(plan_frames(start_ms=1_000, end_ms=1_000, window_ms=10_000), [])
        with self.assertRaises(ValueError):
            plan_frames(start_ms=0, end_ms=10_000, window_ms=0)
        with self.assertRaises(ValueError):
            plan_frames(start_ms=0, end_ms=10_000, window_ms=10_000, position="random")

    def test_offset_still_maps_sample_timestamps(self) -> None:
        plans = plan_frames(start_ms=0, end_ms=20_000, window_ms=10_000, offset_ms=10_000)
        self.assertEqual([plan.sample_at_ms for plan in plans], [15_000, 25_000])
        self.assertEqual([plan.window_start_ms for plan in plans], [0, 10_000],
                         "offset 只映射采样点，不改窗口边界")


class HeuristicAnalyzerContractTest(unittest.TestCase):
    """基准里的合成分析器必须自曝身份（避免被当成真实性能数据）。"""

    def test_heuristic_is_not_qwen(self) -> None:
        analyzer = HeuristicAnalyzer(synthetic_counts=True)
        self.assertNotEqual(analyzer.name, "qwen-vl")


if __name__ == "__main__":
    unittest.main()
