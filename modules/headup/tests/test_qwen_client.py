"""Qwen-VL 判定客户端的协议测试（用**本地假 DashScope 服务**，不对外发请求）。

这里覆盖"真实模型调用"这条路径的**客户端侧**：请求形状、鉴权头、图像数据 URL、
响应解析、重试与主备模型切换、以及失败时绝不编造人数。
它不证明模型在真实课堂画面上的精度——那需要真实凭证与人工标注集。
"""

from __future__ import annotations

import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from headup import prompts
from headup.analyze import HeuristicAnalyzer, QwenVlAnalyzer, build_analyzer
from headup.config import Config
from headup.pipeline import classify_reading
from headup.wire import TaskError

from support import SYNTHETIC_MEDIA, find_tools, temp_root

API_KEY = "sk-test-not-a-real-key"


class FakeDashScope:
    """假 DashScope：`/chat/completions`，按模型名分别配置返回。"""

    def __init__(self, script: dict[str, list[tuple[int, str]]], *, expect_key: str = API_KEY):
        self.script = {model: list(items) for model, items in script.items()}
        self.expect_key = expect_key
        self.requests: list[dict] = []
        self.calls_by_model: dict[str, int] = {}

        outer = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length)
                record = {
                    "path": self.path,
                    "authorization": self.headers.get("Authorization", ""),
                    "content_type": self.headers.get("Content-Type", ""),
                }
                try:
                    body = json.loads(raw.decode("utf-8"))
                except ValueError:
                    body = None
                record["body"] = body
                outer.requests.append(record)

                if outer.expect_key and record["authorization"] != f"Bearer {outer.expect_key}":
                    self._send(401, {"error": {"message": "invalid api key"}})
                    return
                if not isinstance(body, dict) or "model" not in body:
                    self._send(400, {"error": {"message": "bad request"}})
                    return
                model = body["model"]
                outer.calls_by_model[model] = outer.calls_by_model.get(model, 0) + 1
                queue = outer.script.get(model)
                if not queue:
                    self._send(500, {"error": {"message": "server error"}})
                    return
                status, content = queue.pop(0) if len(queue) > 1 else queue[0]
                if status != 200:
                    self._send(status, {"error": {"message": f"http {status}"}})
                    return
                self._send(200, {"choices": [{"message": {"role": "assistant",
                                                          "content": content}}]})

            def _send(self, status: int, payload: dict) -> None:
                data = json.dumps(payload).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, fmt: str, *args: object) -> None:
                del fmt, args

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}/v1"

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


def qwen_config(fake: FakeDashScope, **overrides) -> Config:
    defaults = dict(
        vision_provider="qwen-vl",
        vision_api_key=API_KEY,
        vision_base_url=fake.base_url,
        vision_model="qwen3-vl-plus",
        vision_fallback_model="qwen3-vl-flash",
        vision_max_retries=1,
        vision_timeout_s=20.0,
        vision_max_long_side=512,
        frame_tile_grid="1x1",
        prompt_version=prompts.PROMPT_VERSION_STRICT,
        strict_counts=True,
        counts_retry_extra=1,
    )
    tools = find_tools()
    if tools is not None:
        defaults["ffmpeg_path"] = tools[0]
        defaults["ffprobe_path"] = tools[1]
    defaults.update(overrides)
    return Config(**defaults)


def valid_answer(head_up: int = 12, head_down: int = 6, unknown: int = 2,
                 *, legacy: bool = False, confidence: float = 0.8) -> str:
    """严格口径的模型回复（五计数自洽）。`legacy=True` 时给出旧版三字段，用于兼容性测试。"""
    visible = head_up + head_down + unknown
    if legacy:
        return json.dumps({"head_up": head_up, "head_down": head_down, "unknown": unknown,
                           "legible_ratio": (head_up + head_down) / visible if visible else 0.0,
                           "confidence": confidence, "note": "legacy"})
    return json.dumps({"visible_count": visible, "evaluable_count": head_up + head_down,
                       "head_up_count": head_up, "head_down_count": head_down,
                       "unknown_count": unknown, "confidence": confidence, "note": "test"})


def inconsistent_answer() -> str:
    """计数不自洽：up+down+unknown ≠ visible。"""
    return json.dumps({"visible_count": 21, "evaluable_count": 15, "head_up_count": 10,
                       "head_down_count": 5, "unknown_count": 5, "confidence": 0.9})


def make_frame(root: Path, *, at_ms: int = 5_000) -> Path:
    """从交付样例媒体里抽一帧真实 JPEG，用于验证图像编码路径。"""
    from headup.media import FfmpegTools, extract_frame

    tools = find_tools()
    if tools is None or not SYNTHETIC_MEDIA.is_file():
        raise unittest.SkipTest("缺少 ffmpeg 或合成媒体")
    cfg = Config(ffmpeg_path=tools[0], ffprobe_path=tools[1], media_probe_timeout_s=60)
    out = root / f"frame-{at_ms}.jpg"
    extract_frame(FfmpegTools.locate(cfg), SYNTHETIC_MEDIA, at_ms, out, max_width=1600, cfg=cfg)
    return out


class RequestShapeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root = temp_root("qwen")
        self.fake = FakeDashScope({"qwen3-vl-plus": [(200, valid_answer())]})
        self.cfg = qwen_config(self.fake)
        self.frame = make_frame(self.root)

    def tearDown(self) -> None:
        self.fake.close()

    def test_request_is_openai_compatible_with_image_data_url(self) -> None:
        reading = QwenVlAnalyzer(self.cfg).analyze(self.frame)
        self.assertEqual((reading.head_up, reading.head_down, reading.unknown), (12, 6, 2))
        self.assertEqual(len(self.fake.requests), 1)
        record = self.fake.requests[0]
        self.assertTrue(record["path"].endswith("/chat/completions"), record["path"])
        self.assertEqual(record["authorization"], f"Bearer {API_KEY}")
        self.assertEqual(record["content_type"], "application/json")
        body = record["body"]
        self.assertEqual(body["model"], "qwen3-vl-plus")
        self.assertEqual(body["temperature"], 0.0)
        messages = body["messages"]
        self.assertEqual(messages[0]["role"], "system")
        self.assertEqual(messages[1]["role"], "user")
        parts = messages[1]["content"]
        self.assertEqual(parts[0]["type"], "text")
        # v0.2：默认提示词必须是严格口径（要求自洽计数、禁止推测）
        self.assertIn("visible_count", parts[0]["text"])
        self.assertIn("后脑", parts[0]["text"])
        self.assertIn("宁可增加 UNKNOWN", parts[0]["text"])
        self.assertEqual(parts[1]["type"], "image_url")
        self.assertTrue(parts[1]["image_url"]["url"].startswith("data:image/jpeg;base64,"))
        # 图像被缩放到配置的长边以内
        decoded_len = len(parts[1]["image_url"]["url"])
        self.assertGreater(decoded_len, 1000)
        # 计数与程序计算的比率
        counts = reading.counts
        self.assertEqual(counts.visible_count, 20)
        self.assertEqual(counts.evaluable_count, 18)
        self.assertAlmostEqual(reading.head_up_rate, 12 / 18)
        self.assertAlmostEqual(reading.coverage, 18 / 20)
        self.assertTrue(reading.counts_strict)

    def test_answer_maps_into_window_quality(self) -> None:
        reading = QwenVlAnalyzer(self.cfg).analyze(self.frame)
        quality, reason, head_up, observed = classify_reading(reading, min_legible_ratio=0.6)
        self.assertEqual((quality, reason), ("VALID", None))
        self.assertEqual((head_up, observed), (12, 18))


class ResilienceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root = temp_root("qwen-r")
        self.frame = make_frame(self.root)

    def test_switches_to_fallback_model_after_primary_failure(self) -> None:
        fake = FakeDashScope({
            "qwen3-vl-plus": [(500, "")],
            "qwen3-vl-flash": [(200, valid_answer(head_up=5, head_down=5, unknown=0))],
        })
        try:
            reading = QwenVlAnalyzer(qwen_config(fake)).analyze(self.frame)
            self.assertEqual((reading.head_up, reading.head_down), (5, 5))
            # 主模型重试耗尽后换备用模型（v0.2 起计数自洽重试会把上限抬到 max_retries+1+extra）
            self.assertLessEqual(fake.calls_by_model.get("qwen3-vl-plus", 0), 3)
            self.assertEqual(fake.calls_by_model.get("qwen3-vl-flash"), 1)
            self.assertTrue(any("fallbackModel" in note for note in reading.analysis_notes))
        finally:
            fake.close()

    def test_retries_rate_limit_then_succeeds(self) -> None:
        fake = FakeDashScope({"qwen3-vl-plus": [
            (429, ""), (200, valid_answer(head_up=1, head_down=1, unknown=0))]})
        try:
            reading = QwenVlAnalyzer(qwen_config(fake)).analyze(self.frame)
            self.assertEqual(reading.head_up, 1)
            self.assertEqual(fake.calls_by_model.get("qwen3-vl-plus"), 2)
        finally:
            fake.close()

    def test_non_retryable_error_still_tries_fallback_once(self) -> None:
        """401（凭证问题）不再重试同一模型，但仍然给备用模型一次机会，然后显式失败。"""
        fake = FakeDashScope({
            "qwen3-vl-plus": [(401, "")],
            "qwen3-vl-flash": [(401, "")],
        })
        try:
            with self.assertRaises(TaskError) as ctx:
                QwenVlAnalyzer(qwen_config(fake)).analyze(self.frame)
            self.assertEqual(ctx.exception.code, "VISION_PROVIDER_ERROR")
            self.assertEqual(fake.calls_by_model.get("qwen3-vl-plus"), 1)
            self.assertEqual(fake.calls_by_model.get("qwen3-vl-flash"), 1)
        finally:
            fake.close()

    def test_garbage_answer_never_becomes_counts(self) -> None:
        fake = FakeDashScope({
            "qwen3-vl-plus": [(200, "抱歉，我看不清这张图")],
            "qwen3-vl-flash": [(200, "还是看不清")],
        })
        try:
            with self.assertRaises(TaskError) as ctx:
                QwenVlAnalyzer(qwen_config(fake)).analyze(self.frame)
            self.assertEqual(ctx.exception.code, "VISION_PROVIDER_ERROR")
            self.assertTrue(ctx.exception.retryable)
        finally:
            fake.close()

    def test_negative_fields_are_rejected(self) -> None:
        payload = json.dumps({"head_up": -3, "head_down": 5, "unknown": 0,
                              "legible_ratio": 0.5, "confidence": 0.5})
        fake = FakeDashScope({"qwen3-vl-plus": [(200, payload)],
                              "qwen3-vl-flash": [(200, payload)]})
        try:
            with self.assertRaises(TaskError):
                QwenVlAnalyzer(qwen_config(fake)).analyze(self.frame)
        finally:
            fake.close()

    def test_pipeline_fails_job_when_vision_is_broken(self) -> None:
        """模型全挂时：单窗标 LOW_QUALITY(ANALYSIS_FAILED)，全部窗口失败则整体 FAILED。"""
        from headup import definitions, wire
        from headup.pipeline import run_job

        fake = FakeDashScope({
            "qwen3-vl-plus": [(500, "")],
            "qwen3-vl-flash": [(500, "")],
        })
        try:
            cfg = qwen_config(fake, work_dir=str(self.root / "work"))
            doc = {
                "schemaVersion": "1.0", "clientRequestId": "req-qwen-fail-001",
                "moduleType": "HEAD_UP", "classSessionId": "session-x", "mediaId": "media-x",
                "media": {"sha256": "0" * 64, "durationMs": 20_000, "mimeType": "video/mp4",
                          "sourceUrl": "https://media.example.invalid/x.mp4",
                          "urlExpiresAt": "2099-01-01T00:00:00Z"},
                "analysisRange": {"startMs": 0, "endMs": 20_000},
                "parameters": {"windowMs": 10_000, "outputMode": "COUNTS",
                               "definitionId": definitions.DEFINITION_ID_REAL},
            }
            request = wire.parse_job_request(doc, idempotency_key=doc["clientRequestId"],
                                            allowed_definition_ids=(definitions.DEFINITION_ID_REAL,),
                                            max_duration_ms=7_200_000, max_windows=20_000)
            with self.assertRaises(TaskError) as ctx:
                run_job(request=request, cfg=cfg, analyzer=QwenVlAnalyzer(cfg),
                        work_root=self.root / "jobs", local_media=SYNTHETIC_MEDIA)
            self.assertEqual(ctx.exception.code, "PROCESSING_FAILED")
        finally:
            fake.close()

    def test_missing_credential_does_not_select_qwen(self) -> None:
        analyzer = build_analyzer(Config(vision_provider="qwen-vl", vision_api_key=""))
        self.assertEqual(analyzer.name, "unavailable")
        reading = analyzer.analyze(self.root / "whatever.jpg")
        quality, reason, head_up, observed = classify_reading(reading, min_legible_ratio=0.6)
        self.assertEqual((quality, reason, head_up, observed),
                         ("NO_SUBJECTS", "NO_OBSERVABLE_SUBJECTS", 0, 0))
        self.assertIsInstance(build_analyzer(Config(vision_provider="heuristic")),
                              HeuristicAnalyzer)


class TiledAnalysisTest(unittest.TestCase):
    def test_tiles_are_summed_and_legible_ratio_recomputed(self) -> None:
        root = temp_root("qwen-tile")
        frame = make_frame(root)
        # 4 块，每块人为给不同计数
        answers = [valid_answer(3, 1, 0), valid_answer(2, 2, 0),
                   valid_answer(0, 4, 1), valid_answer(5, 0, 1)]
        fake = FakeDashScope({"qwen3-vl-plus": [(200, text) for text in answers]})
        try:
            cfg = qwen_config(fake, frame_tile_grid="2x2")
            reading = QwenVlAnalyzer(cfg).analyze(frame)
            self.assertEqual(len(fake.requests), 4)
            self.assertEqual(reading.head_up, 3 + 2 + 0 + 5)
            self.assertEqual(reading.head_down, 1 + 2 + 4 + 0)
            self.assertEqual(reading.unknown, 0 + 0 + 1 + 1)
            observed = reading.head_up + reading.head_down
            self.assertAlmostEqual(reading.legible_ratio, observed / (observed + reading.unknown))
            quality, _reason, head_up, denominator = classify_reading(reading, min_legible_ratio=0.6)
            self.assertEqual(quality, "VALID")
            self.assertEqual((head_up, denominator), (10, 17))
        finally:
            fake.close()


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
