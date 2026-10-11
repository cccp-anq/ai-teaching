"""HTTP 链路测试：真实媒体 → 受控下载 → 核验 → 抽帧 → 判定 → 契约信封。

覆盖契约 §2/§3/§4/§5 的返回码与不变量：202/200/409/401/403/404/413/422，
以及"同键重提不重复处理""终态不可变""响应不含受控下载地址"。
"""

from __future__ import annotations

import json
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from headup import wire
from headup.server import HeadUpApp, create_server
from headup.service import JobService
from headup.store import Store

from support import (DEMO_SHA256, LocalMediaServer, TempConfig, demo_request_doc, find_tools,
                     make_test_media, probe_duration_ms, request_doc, sha256_of, temp_root)

TOKEN = "test-token"


@unittest.skipUnless(find_tools() is not None, "缺少 ffmpeg/ffprobe，跳过需要真实媒体的用例")
class HttpContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.root = temp_root("headup-http-")
        cls.media = make_test_media(cls.root, duration_ms=20_000)
        ffmpeg, ffprobe = find_tools()  # type: ignore[misc]
        cls.media_sha = sha256_of(cls.media)
        cls.media_duration = probe_duration_ms(ffprobe, cls.media)
        cls.media_server = LocalMediaServer(cls.media)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.media_server.close()

    def setUp(self) -> None:
        self.case_dir = self.root / self._testMethodName
        self.case_dir.mkdir(parents=True, exist_ok=True)
        wrapper = TempConfig(self.case_dir)
        self.cfg = wrapper.cfg
        self.store = Store(self.cfg.db_path)
        self.service = JobService(self.cfg, self.store)
        self.service.start()
        self.server = create_server(self.cfg, HeadUpApp(self.cfg, self.service))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.service.stop()

    # -- 工具 ----------------------------------------------------------
    def call(self, method: str, path: str, *, token: str | None = TOKEN,
             body: bytes | None = None, headers: dict[str, str] | None = None):
        request = urllib.request.Request(self.base + path, data=body, method=method)
        if token is not None:
            request.add_header("Authorization", f"Bearer {token}")
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.status, json.loads(response.read().decode("utf-8")), dict(response.headers)
        except urllib.error.HTTPError as exc:
            return exc.code, json.loads(exc.read().decode("utf-8")), dict(exc.headers)

    def submit(self, doc: dict, *, token: str | None = TOKEN, idem: str | None = None):
        body = json.dumps(doc, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if idem is not None:
            headers["Idempotency-Key"] = idem
        return self.call("POST", "/api/v1/jobs", token=token, body=body, headers=headers)

    def real_doc(self, **overrides):
        base = dict(
            client_request_id="req-http-real-001",
            class_session_id="session-http-001",
            media_id="media-http-001",
            sha256=self.media_sha,
            duration_ms=self.media_duration,
            source_url=self.media_server.url,
            window_ms=10_000,
        )
        base.update(overrides)
        return request_doc(**base)

    def await_terminal(self, job_id: str, *, timeout: float = 120.0) -> dict:
        import time

        deadline = time.time() + timeout
        envelope = None
        while time.time() < deadline:
            status, envelope, _ = self.call("GET", f"/api/v1/jobs/{job_id}")
            self.assertEqual(status, 200)
            if envelope["status"] in wire.TERMINAL_STATUSES:
                return envelope
            time.sleep(0.3)
        self.fail(f"任务未在 {timeout}s 内进入终态: {envelope}")

    # -- 用例 ----------------------------------------------------------
    def test_health_is_public_and_free_of_secrets(self) -> None:
        status, body, _ = self.call("GET", "/api/v1/health", token=None)
        self.assertEqual(status, 200)
        self.assertEqual(body["providerId"], self.cfg.provider_id)
        self.assertEqual(body["moduleType"], "HEAD_UP")
        self.assertTrue(body["auth"]["tokensConfigured"])
        text = json.dumps(body, ensure_ascii=False)
        self.assertNotIn(TOKEN, text)
        self.assertNotIn(self.media_server.url, text)

    def test_submit_returns_202_then_200_on_replay(self) -> None:
        doc = self.real_doc()
        status, envelope, _ = self.submit(doc, idem=doc["clientRequestId"])
        self.assertEqual(status, 202)
        self.assertEqual(envelope["status"], wire.STATUS_QUEUED)
        self.assertEqual(envelope["revision"], 0)
        self.assertIsNone(envelope["payload"])
        self.assertIsNone(envelope["error"])

        terminal = self.await_terminal(envelope["jobId"])
        self.assertEqual(terminal["status"], wire.STATUS_SUCCEEDED)
        self.assertEqual(terminal["revision"], 1)
        wire.validate_envelope(terminal)
        self.assertNotIn("sourceUrl", json.dumps(terminal))
        self.assertNotIn(self.media_server.url, json.dumps(terminal))

        replayed_status, replayed, _ = self.submit(doc, idem=doc["clientRequestId"])
        self.assertEqual(replayed_status, 200)
        self.assertEqual(replayed["jobId"], envelope["jobId"])
        # 重提返回**持久化终态**；GET 会额外附带 progress / samples / metrics（FRAME-5/6 增量字段），
        # 因此只比较契约字段
        self.assertEqual(replayed, {k: v for k, v in terminal.items()
                                    if k not in ("progress", "samples", "metrics")})
        # 而 GET 的增量字段确实存在且自洽
        self.assertEqual(terminal["progress"]["currentStage"], "SUCCESS")
        self.assertEqual(len(terminal["samples"]), len(terminal["payload"]["windows"]))
        self.assertEqual(terminal["metrics"]["metricsVersion"], "headup-perf-v1")
        self.assertEqual(terminal["metrics"]["counters"]["windows.total"],
                         len(terminal["payload"]["windows"]))

    def test_low_quality_windows_do_not_fabricate_counts(self) -> None:
        """无凭证启发式判定：必须显式降级，绝不输出人数。

        合成测试媒体画面里没有真人，因此模型看到的每个窗口都是"无可见对象" =>
        `NO_SUBJECTS`（0/0）而不是编造人数；`LOW_QUALITY`（两数 null）也必须是合法降级态。
        两条路径都不能出现任何计数。
        """
        doc = self.real_doc()
        _status, envelope, _ = self.submit(doc, idem=doc["clientRequestId"])
        terminal = self.await_terminal(envelope["jobId"])
        payload = terminal["payload"]
        self.assertTrue(payload["windows"])
        for row in payload["windows"]:
            if row["quality"] == "NO_SUBJECTS":
                self.assertEqual((row["headUpCount"], row["validObservedCount"]), (0, 0))
                self.assertEqual(row["qualityReason"], "NO_VISIBLE_PERSON")
                self.assertEqual(row["qualityStatus"], "NO_DATA")
            else:
                self.assertIn(row["quality"], ("LOW_QUALITY", "REVIEW"))
                self.assertIsNone(row["headUpCount"])
                self.assertIsNone(row["validObservedCount"])
            self.assertIsNone(row["headUpRatio"])
        # 没有任何窗口真的被判定为可用的可观测数据
        self.assertNotIn("VALID", {row["quality"] for row in payload["windows"]})

    def test_heuristic_synthetic_counters_produce_valid_windows(self) -> None:
        """演练模式（HEADUP_HEURISTIC_SYNTHETIC）：能产出 VALID 窗口并满足契约，
        但这是**合成计数**，只为验证链路，任何真实部署都不得开启。"""
        # 先停掉 setUp 里的服务：否则它的 worker 会先认领任务，用默认（无语义）判定处理掉
        self.server.shutdown()
        self.server.server_close()
        self.service.stop()

        wrapped = TempConfig(self.case_dir / "synthetic", vision_provider="heuristic",
                             heuristic_synthetic=True)
        cfg = wrapped.cfg
        store = Store(cfg.db_path)
        service = JobService(cfg, store)
        service.start()
        server = create_server(cfg, HeadUpApp(cfg, service))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            doc = self.real_doc()
            body = json.dumps(doc, ensure_ascii=False).encode("utf-8")
            request = urllib.request.Request(
                f"http://127.0.0.1:{server.server_address[1]}/api/v1/jobs", data=body,
                method="POST", headers={"Content-Type": "application/json",
                                        "Authorization": f"Bearer {TOKEN}",
                                        "Idempotency-Key": doc["clientRequestId"]})
            with urllib.request.urlopen(request, timeout=60) as response:
                envelope = json.loads(response.read().decode("utf-8"))
            deadline = time.time() + 120
            while time.time() < deadline:
                get = urllib.request.Request(
                    f"http://127.0.0.1:{server.server_address[1]}/api/v1/jobs/"
                    f"{envelope['jobId']}", headers={"Authorization": f"Bearer {TOKEN}"})
                with urllib.request.urlopen(get, timeout=30) as response:
                    envelope = json.loads(response.read().decode("utf-8"))
                if envelope["status"] in wire.TERMINAL_STATUSES:
                    break
                time.sleep(0.3)
            self.assertEqual(envelope["status"], wire.STATUS_SUCCEEDED)
            wire.validate_envelope(envelope)
            qualities = {row["quality"] for row in envelope["payload"]["windows"]}
            self.assertIn("VALID", qualities)
            for row in envelope["payload"]["windows"]:
                if row["quality"] == "VALID":
                    self.assertGreater(row["validObservedCount"], 0)
                    self.assertLessEqual(row["headUpCount"], row["validObservedCount"])
        finally:
            server.shutdown()
            server.server_close()
            service.stop()

    def test_same_key_with_different_content_conflicts(self) -> None:
        doc = self.real_doc()
        self.submit(doc, idem=doc["clientRequestId"])
        changed = self.real_doc(sha256="b" * 64)
        status, body, _ = self.submit(changed, idem=changed["clientRequestId"])
        self.assertEqual(status, 409)
        self.assertEqual(body["code"], "IDEMPOTENCY_CONFLICT")
        self.assertFalse(body["retryable"])

    def test_credential_refresh_keeps_the_same_job(self) -> None:
        doc = self.real_doc()
        _status, envelope, _ = self.submit(doc, idem=doc["clientRequestId"])
        refreshed = dict(doc)
        refreshed["media"] = dict(doc["media"])
        refreshed["media"]["sourceUrl"] = self.media_server.url + "?sign=2"
        refreshed["media"]["urlExpiresAt"] = "2099-01-02T00:00:00Z"
        status, body, _ = self.submit(refreshed, idem=refreshed["clientRequestId"])
        self.assertEqual(status, 200)
        self.assertEqual(body["jobId"], envelope["jobId"])

    def test_hash_mismatch_is_terminal_and_not_retryable(self) -> None:
        doc = self.real_doc(sha256="c" * 64)
        _status, envelope, _ = self.submit(doc, idem=doc["clientRequestId"])
        terminal = self.await_terminal(envelope["jobId"])
        self.assertEqual(terminal["status"], wire.STATUS_FAILED)
        self.assertEqual(terminal["error"]["code"], "MEDIA_HASH_MISMATCH")
        self.assertFalse(terminal["error"]["retryable"])
        self.assertNotIn(self.media_server.url, json.dumps(terminal))

    def test_expired_url_maps_to_retryable_error(self) -> None:
        expired = LocalMediaServer(self.media, status=403)
        try:
            doc = self.real_doc(source_url=expired.url)
            _status, envelope, _ = self.submit(doc, idem=doc["clientRequestId"])
            terminal = self.await_terminal(envelope["jobId"])
            self.assertEqual(terminal["status"], wire.STATUS_FAILED)
            self.assertEqual(terminal["error"]["code"], "MEDIA_URL_EXPIRED")
            self.assertTrue(terminal["error"]["retryable"])
        finally:
            expired.close()

    def test_private_address_is_refused(self) -> None:
        doc = self.real_doc(source_url="https://10.0.0.5/internal.mp4")
        _status, envelope, _ = self.submit(doc, idem=doc["clientRequestId"])
        terminal = self.await_terminal(envelope["jobId"])
        self.assertEqual(terminal["status"], wire.STATUS_FAILED)
        self.assertEqual(terminal["error"]["code"], "UNSUPPORTED_MEDIA")
        self.assertFalse(terminal["error"]["retryable"])

    def test_missing_bearer_is_401(self) -> None:
        status, body, _ = self.submit(self.real_doc(), token=None)
        self.assertEqual(status, 401)
        self.assertEqual(body["code"], "UNAUTHENTICATED")

    def test_wrong_bearer_is_401(self) -> None:
        status, body, _ = self.submit(self.real_doc(), token="not-the-token")
        self.assertEqual(status, 401)
        self.assertEqual(body["code"], "UNAUTHENTICATED")

    def test_wrong_module_type_is_403(self) -> None:
        doc = self.real_doc()
        doc["moduleType"] = "TRANSCRIPTION"
        status, body, _ = self.submit(doc, idem=doc["clientRequestId"])
        self.assertEqual(status, 403)
        self.assertEqual(body["code"], "FORBIDDEN")

    def test_malformed_json_is_400(self) -> None:
        status, body, _ = self.call("POST", "/api/v1/jobs", body=b"{not json",
                                    headers={"Content-Type": "application/json"})
        self.assertEqual(status, 400)
        self.assertEqual(body["code"], "MALFORMED_JSON")

    def test_idempotency_header_mismatch_is_422(self) -> None:
        doc = self.real_doc()
        status, body, _ = self.submit(doc, idem="different-key")
        self.assertEqual(status, 422)
        self.assertEqual(body["code"], "VALIDATION_FAILED")
        self.assertEqual(body["details"][0]["path"], "Idempotency-Key")

    def test_unsupported_schema_is_422(self) -> None:
        doc = self.real_doc()
        doc["schemaVersion"] = "9.9"
        status, body, _ = self.submit(doc, idem=doc["clientRequestId"])
        self.assertEqual(status, 422)
        self.assertEqual(body["code"], "UNSUPPORTED_SCHEMA")

    def test_oversized_body_is_413(self) -> None:
        import http.client
        conn=http.client.HTTPConnection("127.0.0.1",self.server.server_port)
        conn.putrequest("POST","/api/v1/jobs")
        conn.putheader("Authorization","Bearer " + self.cfg.bearer_tokens[0])
        conn.putheader("Content-Length",str(self.cfg.max_body_bytes+100))
        conn.endheaders()                 # no body: proves rejection happens before read
        response=conn.getresponse()
        self.assertEqual(response.status,413)
        self.assertEqual(json.loads(response.read())["code"],"PAYLOAD_TOO_LARGE")
        conn.close()

    def test_unknown_job_is_404_and_does_not_leak(self) -> None:
        status, body, _ = self.call("GET", "/api/v1/jobs/job-does-not-exist")
        self.assertEqual(status, 404)
        self.assertEqual(body["code"], "NOT_FOUND")
        self.assertEqual(body["details"], [])

    def test_get_with_mismatched_client_request_id_is_404(self) -> None:
        doc = self.real_doc()
        _status, envelope, _ = self.submit(doc, idem=doc["clientRequestId"])
        status, _body, _ = self.call("GET", f"/api/v1/jobs/{envelope['jobId']}",
                                     headers={"X-Client-Request-Id": "someone-else"})
        self.assertEqual(status, 404)

    def test_synthetic_identity_uses_fixture_provider(self) -> None:
        doc = demo_request_doc()
        status, envelope, _ = self.submit(doc, idem=doc["clientRequestId"])
        self.assertEqual(status, 202)
        self.assertEqual(envelope["providerId"], self.cfg.fixture_provider_id)
        terminal = self.await_terminal(envelope["jobId"])
        self.assertEqual(terminal["status"], wire.STATUS_SUCCEEDED)
        wire.validate_envelope(terminal)
        # 合成观测表必须与主项目冻结样例一致（同键不同内容会 409，故这里直接查窗口数值）
        counts = [(w["headUpCount"], w["validObservedCount"]) for w in terminal["payload"]["windows"]]
        self.assertEqual(counts[:3], [(30, 40), (24, 40), (0, 40)])
        self.assertEqual(terminal["payload"]["definitionId"], "head-up-demo-v1")
        self.assertEqual(terminal["moduleVersion"], self.cfg.fixture_provider_version)
        # 演示形状里含"没有采样点"的 LOW_QUALITY 窗口（sampleAtMs=null）：修复前它会让
        # 这个任务的逐窗口样本整批回滚丢失（见 tests/test_sample_persistence.py）
        self.assertTrue(any(w["sampleAtMs"] is None for w in terminal["payload"]["windows"]))
        self.assertEqual(len(terminal["samples"]), len(terminal["payload"]["windows"]),
                         "每个窗口都要有审计样本（含抽不到帧/不可判读的窗口）")
        self.assertIsNone(terminal["metrics"]["notes"].get("samplesPersistFailed"))
        self.assertEqual(terminal["metrics"]["notes"]["samplesWritten"],
                         len(terminal["payload"]["windows"]))

    def test_unregistered_definition_id_is_422(self) -> None:
        doc = self.real_doc()
        doc["parameters"]["definitionId"] = "head-up-someone-elses-v1"
        status, body, _ = self.submit(doc, idem=doc["clientRequestId"])
        self.assertEqual(status, 422)
        self.assertEqual(body["code"], "VALIDATION_FAILED")
        self.assertEqual(body["details"][0]["path"], "parameters.definitionId")

    def test_real_identity_never_falls_back_to_synthetic(self) -> None:
        """合成 provider 已开启，但真实身份必须走真实路径（结果是降级而非假数字）。"""
        doc = self.real_doc(client_request_id="req-not-demo-001")
        doc["media"]["sha256"] = DEMO_SHA256  # 故意用演示 hash，但身份不是演示课堂
        _status, envelope, _ = self.submit(doc, idem=doc["clientRequestId"])
        self.assertEqual(envelope["providerId"], self.cfg.provider_id)
        terminal = self.await_terminal(envelope["jobId"])
        self.assertEqual(terminal["status"], wire.STATUS_FAILED)
        self.assertEqual(terminal["error"]["code"], "MEDIA_HASH_MISMATCH")

    def test_terminal_state_is_immutable_after_credential_refresh(self) -> None:
        doc = self.real_doc()
        _status, envelope, _ = self.submit(doc, idem=doc["clientRequestId"])
        terminal = self.await_terminal(envelope["jobId"])
        refreshed = dict(doc)
        refreshed["media"] = dict(doc["media"])
        refreshed["media"]["sourceUrl"] = self.media_server.url + "?sign=3"
        status, body, _ = self.submit(refreshed, idem=refreshed["clientRequestId"])
        self.assertEqual(status, 200)
        # 只比较契约字段（GET 会额外带 progress / samples / metrics 增量字段，见 FRAME-5/6）
        self.assertEqual(body, {k: v for k, v in terminal.items()
                                if k not in ("progress", "samples", "metrics")})
        self.assertEqual(body["status"], wire.STATUS_SUCCEEDED)
        self.assertEqual(body["revision"], 1)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
