"""任务库测试：幂等、409、终态不可变、租约回收、重启后恢复。"""

from __future__ import annotations

import unittest

from headup import definitions, wire
from headup.config import Config
from headup.store import Store, SubmitOutcome

from support import demo_request_doc, temp_root


def make_request(**overrides):
    doc = demo_request_doc(**overrides)
    return wire.parse_job_request(doc, idempotency_key=doc["clientRequestId"],
                                 allowed_definition_ids=(definitions.DEFINITION_ID_DEMO,),
                                 max_duration_ms=7_200_000, max_windows=20_000)


class StoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root = temp_root("headup-store-")
        self.store = Store(str(self.root / "headup.sqlite3"))
        self.cfg = Config(db_path=str(self.root / "headup.sqlite3"))

    def _submit(self, request, provider_id="head-up-demo", version="demo-1.0"):
        return self.store.submit(request, provider_id=provider_id, module_version=version)

    def test_first_submit_creates_queued_job(self) -> None:
        outcome, envelope = self._submit(make_request())
        self.assertEqual(outcome, SubmitOutcome.CREATED)
        self.assertEqual(envelope["status"], wire.STATUS_QUEUED)
        self.assertEqual(envelope["revision"], 0)
        self.assertTrue(envelope["jobId"].startswith("job-head-up-"))

    def test_same_key_same_content_is_replayed(self) -> None:
        _outcome, first = self._submit(make_request())
        outcome, second = self._submit(make_request())
        self.assertEqual(outcome, SubmitOutcome.CREDENTIALS_REFRESHED)
        self.assertIsNotNone(second)
        self.assertEqual(second["jobId"], first["jobId"])
        self.assertEqual(second["status"], wire.STATUS_QUEUED)
        self.assertEqual(self.store.envelope(first["jobId"]),
                         self.store.envelope_by_client_request(first["clientRequestId"]))

    def test_same_key_different_content_conflicts(self) -> None:
        _outcome, first = self._submit(make_request())
        outcome, _ = self._submit(make_request(window_ms=5_000))
        self.assertEqual(outcome, SubmitOutcome.CONFLICT)
        row = self.store.safe_view(first["jobId"])
        self.assertEqual(int(row["window_ms"]), 10_000)  # 原任务参数未被静默改动
        self.assertEqual(self.store.envelope(first["jobId"])["status"], wire.STATUS_QUEUED)

    def test_finished_job_is_replayed_and_never_rewritten(self) -> None:
        outcome, envelope = self._submit(make_request())
        self.assertEqual(outcome, SubmitOutcome.CREATED)
        claimed = self.store.claim_next()
        self.assertIsNotNone(claimed)
        terminal = dict(envelope)
        terminal.update({"status": wire.STATUS_SUCCEEDED, "revision": 1,
                         "updatedAt": wire.utc_now(), "payload": {
                             "definitionId": definitions.DEFINITION_ID_DEMO,
                             "denominatorDefinition": definitions.DEFINITION_TEXT_DEMO,
                             "sampleMode": wire.SAMPLE_MODE, "valueMode": "COUNTS",
                             "windowMs": 10_000,
                             "processedRanges": [{"startMs": 0, "endMs": 60_000}],
                             "windows": [
                                 {"id": f"win-{index + 1:03d}", "startMs": index * 10_000,
                                  "endMs": (index + 1) * 10_000, "sampleAtMs": index * 10_000 + 5_000,
                                  "quality": "VALID", "qualityReason": None, "headUpCount": 3,
                                  "validObservedCount": 4, "headUpRatio": None}
                                 for index in range(6)
                             ],
                             "warnings": []}})
        self.store.finish(envelope["jobId"], terminal)
        self.assertEqual(self.store.envelope(envelope["jobId"])["status"], wire.STATUS_SUCCEEDED)

        # 重复提交（含凭据续期）只能拿回同一终态，不得改写
        refreshed = make_request()
        outcome, replayed = self._submit(refreshed)
        self.assertEqual(outcome, SubmitOutcome.REPLAYED)
        self.assertEqual(replayed["status"], wire.STATUS_SUCCEEDED)

        # 迟到的终态写入被丢弃
        late = dict(terminal)
        late["status"] = wire.STATUS_FAILED
        late["payload"] = None
        late["error"] = {"code": "PROCESSING_FAILED", "message": "late", "retryable": True}
        self.store.finish(envelope["jobId"], late)
        self.assertEqual(self.store.envelope(envelope["jobId"])["status"], wire.STATUS_SUCCEEDED)

    def test_claim_marks_running_and_counts_attempts(self) -> None:
        _outcome, envelope = self._submit(make_request())
        claimed = self.store.claim_next()
        self.assertEqual(claimed["job_id"], envelope["jobId"])
        self.assertEqual(claimed["attempts"], 1)
        self.assertEqual(self.store.envelope(envelope["jobId"])["status"], wire.STATUS_RUNNING)
        # 租约未过期时不会被第二个 worker 抢走
        self.assertIsNone(self.store.claim_next())

    def test_expired_lease_is_recoverable(self) -> None:
        _outcome, envelope = self._submit(make_request())
        self.store.claim_next(lease_seconds=-1)
        self.assertEqual(self.store.requeue_stale_running(older_than_seconds=0), 1)
        reclaimed = self.store.claim_next()
        self.assertEqual(reclaimed["job_id"], envelope["jobId"])
        self.assertEqual(reclaimed["attempts"], 2)

    def test_release_allows_reclaim(self) -> None:
        _outcome, envelope = self._submit(make_request())
        self.store.claim_next()
        self.store.release(envelope["jobId"])
        self.assertEqual(self.store.claim_next()["job_id"], envelope["jobId"])

    def test_pending_count_tracks_queue(self) -> None:
        self.assertEqual(self.store.pending_count(), 0)
        _outcome, envelope = self._submit(make_request())
        self.assertEqual(self.store.pending_count(), 1)
        self.store.claim_next()
        self.assertEqual(self.store.pending_count(), 1)
        self.store.release(envelope["jobId"])
        claimed = self.store.claim_next()
        self.store.finish(claimed["job_id"], {
            **self.store.envelope(envelope["jobId"]),
            "status": wire.STATUS_FAILED, "revision": 1, "payload": None,
            "error": {"code": "DECODE_FAILED", "message": "坏容器", "retryable": False},
            "updatedAt": wire.utc_now()})
        self.assertEqual(self.store.pending_count(), 0)
        self.assertEqual(self.store.envelope(envelope["jobId"])["error"]["code"], "DECODE_FAILED")

    def test_store_is_persistent_across_instances(self) -> None:
        _outcome, envelope = self._submit(make_request())
        reopened = Store(self.cfg.db_path)
        self.assertEqual(reopened.envelope(envelope["jobId"])["clientRequestId"],
                         envelope["clientRequestId"])
        self.assertEqual(reopened.claim_next()["job_id"], envelope["jobId"])

    def test_source_url_is_stored_but_not_in_stored_request_json(self) -> None:
        _outcome, envelope = self._submit(make_request())
        row = self.store.get(envelope["jobId"])
        self.assertEqual(row["media_source_url"],
                         "https://media.example.invalid/fixture.mp4")
        self.assertNotIn("sourceUrl", row["request_json"])
        safe = self.store.safe_view(envelope["jobId"])
        self.assertNotIn("media_source_url", safe)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
