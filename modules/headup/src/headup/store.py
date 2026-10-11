"""任务库（SQLite）。

- `client_request_id` 唯一：同键同内容复用（契约 §3），异内容 409。
- 终态不可变：`revision` 非终态 0、终态 1，终态记录不再被任何路径改写。
- 单机 worker 用 `claim_next()` + 租约（lease）避免重复处理；进程重启后可恢复未终态任务。

本库是模块自己的库，与主项目数据库无关（契约 §1）。
"""

from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterator

from . import wire

SCHEMA = """
CREATE TABLE IF NOT EXISTS head_up_job (
    job_id              TEXT PRIMARY KEY,
    client_request_id   TEXT NOT NULL UNIQUE,
    fingerprint         TEXT NOT NULL,
    module_type         TEXT NOT NULL,
    provider_id         TEXT NOT NULL,
    module_version      TEXT NOT NULL,
    class_session_id    TEXT NOT NULL,
    media_id            TEXT NOT NULL,
    media_sha256        TEXT NOT NULL,
    duration_ms         INTEGER NOT NULL,
    analysis_start_ms   INTEGER NOT NULL,
    analysis_end_ms     INTEGER NOT NULL,
    window_ms           INTEGER NOT NULL,
    output_mode         TEXT NOT NULL,
    definition_id       TEXT NOT NULL,
    request_json        TEXT NOT NULL,
    media_source_url    TEXT,
    media_url_expires_at TEXT,
    status              TEXT NOT NULL,
    revision            INTEGER NOT NULL,
    envelope_json       TEXT,
    error_code          TEXT,
    error_message       TEXT,
    error_retryable     INTEGER,
    attempts            INTEGER NOT NULL DEFAULT 0,
    lease_until         REAL,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    -- FRAME-5：任务阶段（plan §18）与失败定位；对外仍只暴露契约的
    -- QUEUED/RUNNING/SUCCEEDED/FAILED，阶段是给运维与页面看的内部进度
    current_stage       TEXT,
    error_stage         TEXT,
    -- FRAME-6：一次运行的实测性能指标（分阶段耗时/调用数/token/字节数/成本），
    -- 只通过 GET 的增量字段 metrics 暴露，不进契约 payload
    metrics_json        TEXT
);
CREATE INDEX IF NOT EXISTS idx_head_up_job_status ON head_up_job(status, created_at);

-- FRAME-5：逐窗口样本（plan §19）。**不保存临时帧**，只保存时间戳/观测/质量/版本，
-- 且带 final_observation_source，避免"同一时间点有多个结果却不知道哪个是最终值"。
--
-- `timestamp_ms` 允许为 NULL：抽帧失败的窗口（qualityReason=FRAME_EXTRACTION_FAILED）
-- **没有采样点**（payload 里 sampleAtMs 也是 null），此时用 window_start_ms/window_end_ms
-- 定位窗口。旧版本这里误加了 NOT NULL，导致"一个窗口抽帧失败"时整批样本被回滚丢弃
-- （见 docs/rounds/FRAME-6-性能与稳定性Benchmark.md §9 补记）。
CREATE TABLE IF NOT EXISTS head_up_sample (
    id                          INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id                      TEXT NOT NULL,
    client_request_id           TEXT NOT NULL,
    class_session_id            TEXT NOT NULL,
    media_id                    TEXT NOT NULL,
    window_id                   TEXT NOT NULL,
    timestamp_ms                INTEGER,
    window_start_ms             INTEGER NOT NULL,
    window_end_ms               INTEGER NOT NULL,
    visible_count               INTEGER,
    evaluable_count             INTEGER,
    head_up_count               INTEGER,
    head_down_count             INTEGER,
    unknown_count               INTEGER,
    head_up_rate                REAL,
    coverage                    REAL,
    quality                     TEXT NOT NULL,
    quality_status              TEXT,
    quality_reason              TEXT,
    reviewed                    INTEGER NOT NULL DEFAULT 0,
    review_strategy             TEXT,
    final_observation_source    TEXT NOT NULL,
    model_name                  TEXT,
    model_version               TEXT,
    prompt_version              TEXT,
    sampling_policy_version     TEXT,
    metric_version              TEXT,
    statistics_version          TEXT,
    created_at                  TEXT NOT NULL,
    UNIQUE (job_id, window_id)
);
CREATE INDEX IF NOT EXISTS idx_head_up_sample_job ON head_up_sample(job_id, timestamp_ms);
"""

#: `head_up_sample` 的字段顺序（迁移重建时按它搬运数据；与 SCHEMA 的一致性由测试钉住）
SAMPLE_COLUMNS: tuple[str, ...] = (
    "id", "job_id", "client_request_id", "class_session_id", "media_id", "window_id",
    "timestamp_ms", "window_start_ms", "window_end_ms", "visible_count", "evaluable_count",
    "head_up_count", "head_down_count", "unknown_count", "head_up_rate", "coverage", "quality",
    "quality_status", "quality_reason", "reviewed", "review_strategy",
    "final_observation_source", "model_name", "model_version", "prompt_version",
    "sampling_policy_version", "metric_version", "statistics_version", "created_at",
)

SAMPLE_TABLE_DDL = """
CREATE TABLE head_up_sample (
    id                          INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id                      TEXT NOT NULL,
    client_request_id           TEXT NOT NULL,
    class_session_id            TEXT NOT NULL,
    media_id                    TEXT NOT NULL,
    window_id                   TEXT NOT NULL,
    timestamp_ms                INTEGER,
    window_start_ms             INTEGER NOT NULL,
    window_end_ms               INTEGER NOT NULL,
    visible_count               INTEGER,
    evaluable_count             INTEGER,
    head_up_count               INTEGER,
    head_down_count             INTEGER,
    unknown_count               INTEGER,
    head_up_rate                REAL,
    coverage                    REAL,
    quality                     TEXT NOT NULL,
    quality_status              TEXT,
    quality_reason              TEXT,
    reviewed                    INTEGER NOT NULL DEFAULT 0,
    review_strategy             TEXT,
    final_observation_source    TEXT NOT NULL,
    model_name                  TEXT,
    model_version               TEXT,
    prompt_version              TEXT,
    sampling_policy_version     TEXT,
    metric_version              TEXT,
    statistics_version          TEXT,
    created_at                  TEXT NOT NULL,
    UNIQUE (job_id, window_id)
);
"""

SAMPLE_INDEX_DDL = ("CREATE INDEX IF NOT EXISTS idx_head_up_sample_job"
                    " ON head_up_sample(job_id, timestamp_ms);")


@dataclass
class SamplePersistOutcome:
    """逐窗口样本落库结果：写入了多少行、哪些窗口失败了（**绝不静默**）。"""

    written: int = 0
    failed: list[dict[str, Any]] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.failed

    def to_dict(self) -> dict[str, Any]:
        return {"written": self.written, "failed": list(self.failed),
                "failedCount": len(self.failed)}


class SubmitOutcome:
    CREATED = "created"
    REPLAYED = "replayed"          # 同键同内容 → 200
    CREDENTIALS_REFRESHED = "refreshed"  # 同键同内容、仅下载凭据更新
    CONFLICT = "conflict"          # 同键异内容 → 409


class Store:
    def __init__(self, db_path: str):
        self.path = db_path
        self._lock = threading.RLock()
        parent = Path(db_path).expanduser().resolve().parent
        parent.mkdir(parents=True, exist_ok=True)
        self._init()

    # -- 连接管理 ---------------------------------------------------------
    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.path, timeout=30.0, isolation_level=None)
        conn.row_factory = sqlite3.Row
        try:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA synchronous=NORMAL")
            conn.execute("PRAGMA foreign_keys=ON")
            yield conn
        finally:
            conn.close()

    def _init(self) -> None:
        with self._lock, self._connect() as conn:
            # Online SQLite backup is consistent with WAL; no raw file copy.
            tables = conn.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
            names = {row["name"] for row in tables}
            if names - {'sqlite_sequence'}:
                if 'head_up_job' in names:
                    existing_columns={row['name'] for row in conn.execute('PRAGMA table_info(head_up_job)')}
                    if not {'job_id','client_request_id','envelope_json','lease_until','media_source_url'}.issubset(existing_columns):raise RuntimeError('Unknown legacy headup schema')
                elif names.issubset({'head_up_sample','sqlite_sequence'}):
                    existing_columns={row['name'] for row in conn.execute('PRAGMA table_info(head_up_sample)')}
                    if not {'job_id','window_id','timestamp_ms','quality'}.issubset(existing_columns):raise RuntimeError('Unknown legacy sample schema')
                else:raise RuntimeError('Unknown SQLite database, refusing baseline')
            if names and "merge_schema" not in names:
                with sqlite3.connect(self.path + ".pre-merge.bak") as backup:
                    conn.backup(backup)
            if "merge_schema" in names:
                version = conn.execute("SELECT MAX(version) FROM merge_schema").fetchone()[0]
                if version and version > 1:
                    raise RuntimeError("Database schema is newer than this application")
            conn.execute("BEGIN IMMEDIATE")
            try:
                self._migrate(conn)
                for statement in SCHEMA.split(";"):
                    if statement.strip(): conn.execute(statement)
                columns = {row["name"] for row in conn.execute("PRAGMA table_info(head_up_job)")}
                if "owner_token" not in columns:conn.execute("ALTER TABLE head_up_job ADD COLUMN owner_token TEXT")
                conn.execute("CREATE TABLE IF NOT EXISTS merge_schema(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL, applied_at TEXT NOT NULL)")
                conn.execute("CREATE TABLE IF NOT EXISTS merge_checkpoint(job_id TEXT PRIMARY KEY, document TEXT NOT NULL)")
                conn.execute("CREATE TABLE IF NOT EXISTS merge_response(job_id TEXT NOT NULL, call_key TEXT NOT NULL, document TEXT NOT NULL, PRIMARY KEY(job_id,call_key))")
                conn.execute("CREATE TABLE IF NOT EXISTS merge_transport(job_id TEXT NOT NULL,call_key TEXT NOT NULL,document TEXT NOT NULL,PRIMARY KEY(job_id,call_key))")
                conn.execute("CREATE TABLE IF NOT EXISTS merge_call_budget(job_id TEXT PRIMARY KEY,calls INTEGER NOT NULL)")
                checksum = __import__('hashlib').sha256((SCHEMA + '|owner_token|checkpoint|response|transport|call_budget-v1').encode()).hexdigest()
                existing = conn.execute("SELECT checksum FROM merge_schema WHERE version=1").fetchone()
                if existing and existing["checksum"] != checksum: raise RuntimeError("Schema migration checksum mismatch")
                conn.execute("INSERT OR IGNORE INTO merge_schema VALUES(1,?,?)",(checksum,wire.utc_now()))
                conn.execute("COMMIT")
            except BaseException:
                conn.execute("ROLLBACK")
                raise

    @staticmethod
    def _migrate(conn: sqlite3.Connection) -> None:
        """幂等增量迁移。

        - FRAME-5：`head_up_job` 补 `current_stage` / `error_stage`
        - FRAME-6：`head_up_job` 补 `metrics_json`
        - 补记修复：`head_up_sample.timestamp_ms` 去掉 `NOT NULL`
          （抽帧失败的窗口没有采样点；旧约束会让**整批**样本回滚丢失）
        """
        existing = conn.execute("PRAGMA table_info(head_up_job)").fetchall()
        if existing:
            columns = {row["name"] for row in existing}
            for name in ("current_stage", "error_stage", "metrics_json"):
                if name not in columns:
                    conn.execute(f"ALTER TABLE head_up_job ADD COLUMN {name} TEXT")
        Store._migrate_sample_timestamp(conn)

    @staticmethod
    def _migrate_sample_timestamp(conn: sqlite3.Connection) -> bool:
        """把老库的 `head_up_sample.timestamp_ms NOT NULL` 重建为可空（保留全部数据）。

        SQLite 不能直接改列约束，只能重建表；重建过程：改名 → 去掉旧索引 → 建新表 →
        按字段搬运 → 删旧表 → 建索引。返回是否真的做了迁移（便于测试与日志）。
        """
        rows = conn.execute("PRAGMA table_info(head_up_sample)").fetchall()
        if not rows:
            return False
        info = {row["name"]: row for row in rows}
        timestamp = info.get("timestamp_ms")
        if timestamp is None or not int(timestamp["notnull"] or 0):
            return False                      # 新库或已迁移库：什么都不做
        conn.execute("ALTER TABLE head_up_sample RENAME TO head_up_sample_old")
        conn.execute("DROP INDEX IF EXISTS idx_head_up_sample_job")
        conn.execute(SAMPLE_TABLE_DDL)
        carried = [name for name in SAMPLE_COLUMNS if name in info]
        columns = ", ".join(carried)
        conn.execute(f"INSERT INTO head_up_sample ({columns})"
                     f" SELECT {columns} FROM head_up_sample_old")
        conn.execute("DROP TABLE head_up_sample_old")
        conn.execute(SAMPLE_INDEX_DDL)
        return True

    # -- 提交 -------------------------------------------------------------
    def submit(self, request: wire.JobRequest, *, provider_id: str,
               module_version: str) -> tuple[str, dict[str, Any] | None]:
        """返回 (outcome, envelope)。CREDENTIALS_REFRESHED 时 envelope 可能为 None。"""
        fingerprint = request.idempotency_fingerprint()
        now = wire.utc_now()
        with self._lock, self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                row = conn.execute(
                    "SELECT * FROM head_up_job WHERE client_request_id = ?",
                    (request.client_request_id,),
                ).fetchone()
                if row is not None:
                    if row["fingerprint"] != fingerprint:
                        conn.execute("ROLLBACK")
                        return SubmitOutcome.CONFLICT, None
                    terminal = row["status"] in wire.TERMINAL_STATUSES
                    # 终态信封是不可变的审计结果。允许同幂等键刷新短期下载凭据，
                    # 但不能把一次无业务变化的重放伪装成新的任务更新时间。
                    if terminal:
                        conn.execute(
                            "UPDATE head_up_job SET media_source_url = ?,"
                            " media_url_expires_at = ? WHERE job_id = ?",
                            (request.media.source_url, request.media.url_expires_at,
                             row["job_id"]),
                        )
                    else:
                        conn.execute(
                            "UPDATE head_up_job SET media_source_url = ?,"
                            " media_url_expires_at = ?, updated_at = ? WHERE job_id = ?",
                            (request.media.source_url, request.media.url_expires_at, now,
                             row["job_id"]),
                        )
                    conn.execute("COMMIT")
                    if terminal:
                        return SubmitOutcome.REPLAYED, json.loads(row["envelope_json"])
                    return SubmitOutcome.CREDENTIALS_REFRESHED, self._envelope_from_row(
                        {**dict(row), "media_source_url": request.media.source_url})

                job_id = "job-head-up-" + uuid.uuid4().hex[:20]
                envelope = wire.build_envelope(
                    job_id=job_id,
                    client_request_id=request.client_request_id,
                    provider_id=provider_id,
                    module_version=module_version,
                    class_session_id=request.class_session_id,
                    media_id=request.media_id,
                    media_sha256=request.media.sha256,
                    duration_ms=request.duration_ms,
                    analysis_range=request.analysis_range,
                    status=wire.STATUS_QUEUED,
                    revision=0,
                    created_at=now,
                    updated_at=now,
                )
                conn.execute(
                    "INSERT INTO head_up_job (job_id, client_request_id, fingerprint, module_type,"
                    " provider_id, module_version, class_session_id, media_id, media_sha256,"
                    " duration_ms, analysis_start_ms, analysis_end_ms, window_ms, output_mode,"
                    " definition_id, request_json, media_source_url, media_url_expires_at,"
                    " status, revision, envelope_json, attempts, created_at, updated_at)"
                    " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        job_id, request.client_request_id, fingerprint, request.module_type,
                        provider_id, module_version, request.class_session_id, request.media_id,
                        request.media.sha256, request.duration_ms,
                        request.analysis_range_start_ms, request.analysis_range_end_ms,
                        request.parameters.window_ms, request.parameters.output_mode,
                        request.parameters.definition_id,
                        wire.canonical_json(request.for_storage()),
                        request.media.source_url, request.media.url_expires_at,
                        wire.STATUS_QUEUED, 0, wire.canonical_json(envelope), 0, now, now,
                    ),
                )
                conn.execute("COMMIT")
                return SubmitOutcome.CREATED, envelope
            except Exception:
                conn.execute("ROLLBACK")
                raise

    # -- 查询 -------------------------------------------------------------
    def get(self, job_id: str) -> dict[str, Any] | None:
        """含短期下载凭据；仅供 worker 内部使用，禁止直接进入响应或日志。"""
        with self._lock, self._connect() as conn:
            row = conn.execute("SELECT * FROM head_up_job WHERE job_id = ?", (job_id,)).fetchone()
        return dict(row) if row is not None else None

    def safe_view(self, job_id: str) -> dict[str, Any] | None:
        """去掉受控下载地址后的视图（HTTP 层只允许用这个）。"""
        row = self.get(job_id)
        if row is None:
            return None
        for key in ("media_source_url", "media_url_expires_at"):
            row.pop(key, None)
        return row

    def envelope(self, job_id: str) -> dict[str, Any] | None:
        row = self.get(job_id)
        if row is None:
            return None
        return self._envelope_from_row(row)

    def envelope_by_client_request(self, client_request_id: str) -> dict[str, Any] | None:
        with self._lock, self._connect() as conn:
            row = conn.execute("SELECT * FROM head_up_job WHERE client_request_id = ?",
                               (client_request_id,)).fetchone()
        if row is None:
            return None
        return self._envelope_from_row(dict(row))

    def delete_terminal(self,job_id):
        with self._lock,self._connect() as conn:
            conn.execute('BEGIN IMMEDIATE')
            row=conn.execute('SELECT * FROM head_up_job WHERE job_id=?',(job_id,)).fetchone()
            if row is None:conn.rollback();return None
            if row['status'] not in wire.TERMINAL_STATUSES:conn.rollback();raise wire.ApiError(409,'CONFLICT','任务仍在处理')
            for table in ('merge_checkpoint','merge_response','merge_transport','merge_call_budget','head_up_sample','head_up_job'):
                conn.execute('DELETE FROM '+table+' WHERE job_id=?',(job_id,))
            conn.commit();return dict(row)

    def pending_count(self) -> int:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT COUNT(*) AS n FROM head_up_job WHERE status IN (?,?)",
                (wire.STATUS_QUEUED, wire.STATUS_RUNNING),
            ).fetchone()
        return int(row["n"])

    # -- worker -----------------------------------------------------------
    def claim_next(self, *, lease_seconds: float = 900.0) -> dict[str, Any] | None:
        """认领一个待处理任务；RUNNING 且租约过期视为可恢复（崩溃/重启后）。"""
        now_monotonic = time.time()
        with self._lock, self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                row = conn.execute(
                    "SELECT * FROM head_up_job WHERE status = ?"
                    " OR (status = ? AND (lease_until IS NULL OR lease_until < ?))"
                    " ORDER BY created_at LIMIT 1",
                    (wire.STATUS_QUEUED, wire.STATUS_RUNNING, now_monotonic),
                ).fetchone()
                if row is None:
                    conn.execute("COMMIT")
                    return None
                now = wire.utc_now()
                owner = str(uuid.uuid4())
                envelope = json.loads(row["envelope_json"])
                if row["status"] != wire.STATUS_RUNNING:
                    envelope["status"] = wire.STATUS_RUNNING
                    envelope["updatedAt"] = now
                conn.execute(
                    "UPDATE head_up_job SET status = ?, attempts = attempts + 1,"
                    " lease_until = ?, owner_token = ?, envelope_json = ?, updated_at = ? WHERE job_id = ?",
                    (wire.STATUS_RUNNING, now_monotonic + lease_seconds, owner,
                     wire.canonical_json(envelope), now, row["job_id"]),
                )
                conn.execute("COMMIT")
                claimed = dict(row)
                claimed["owner_token"] = owner
                claimed["lease_until"] = now_monotonic + lease_seconds
                claimed["status"] = wire.STATUS_RUNNING
                claimed["attempts"] = int(row["attempts"]) + 1
                return claimed
            except Exception:
                conn.execute("ROLLBACK")
                raise

    def finish(self, job_id: str, envelope: dict[str, Any], *, owner_token: str | None = None) -> None:
        """写入终态。已终态的 job 不再被改写（迟到结果直接丢弃）。"""
        wire.validate_envelope(envelope)
        status = envelope["status"]
        if status not in wire.TERMINAL_STATUSES:
            raise ValueError("finish 只接受终态信封")
        error = envelope.get("error") or {}
        with self._lock, self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                row = conn.execute("SELECT status, owner_token, lease_until FROM head_up_job WHERE job_id = ?",
                                   (job_id,)).fetchone()
                if row is None:
                    conn.execute("ROLLBACK")
                    raise KeyError(f"未知 job: {job_id}")
                if owner_token is not None and (row["owner_token"] != owner_token or (row["lease_until"] or 0) < time.time()):
                    conn.execute("ROLLBACK")
                    return
                if row["status"] in wire.TERMINAL_STATUSES:
                    conn.execute("ROLLBACK")
                    return
                conn.execute(
                    "UPDATE head_up_job SET status = ?, revision = ?, envelope_json = ?,"
                    " error_code = ?, error_message = ?, error_retryable = ?, lease_until = NULL,"
                    " updated_at = ? WHERE job_id = ?",
                    (status, 1, wire.canonical_json(envelope), error.get("code"),
                     error.get("message"), int(bool(error.get("retryable"))) if error else None,
                     envelope["updatedAt"], job_id),
                )
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise

    def resume_unknown(self, identity, expected_key):
        """Continue an existing attempt, preserving its journal and global budget."""
        with self._lock,self._connect() as conn:
            conn.execute('BEGIN IMMEDIATE')
            row=conn.execute('SELECT * FROM head_up_job WHERE class_session_id=? AND media_id=? AND media_sha256=? AND window_ms=? AND client_request_id=?',
                (identity.get('classSessionId'),identity.get('mediaId'),identity.get('mediaSha256'),identity.get('windowMs'),expected_key)).fetchone()
            if not row:conn.rollback();raise wire.ApiError(409,'RESUME_NOT_AVAILABLE','未找到可续跑的原视觉任务')
            pending=conn.execute("SELECT COUNT(*) FROM merge_transport WHERE job_id=? AND json_extract(document,'$.state') IN ('PENDING','UNKNOWN')",(row['job_id'],)).fetchone()[0]
            checkpoint=conn.execute('SELECT 1 FROM merge_checkpoint WHERE job_id=?',(row['job_id'],)).fetchone()
            error=row['error_message'] or ''
            if row['status']!='FAILED' or not pending or not checkpoint or not ('状态不明' in error or '响应不明' in error or '计费状态待核实' in error):
                conn.rollback();raise wire.ApiError(409,'RESUME_NOT_AVAILABLE','此任务不能安全续跑，不能重置调用预算')
            envelope=json.loads(row['envelope_json']);envelope.update(status='QUEUED',revision=0,error=None,payload=None,updatedAt=wire.utc_now())
            conn.execute("UPDATE head_up_job SET status='QUEUED',revision=0,envelope_json=?,error_code=NULL,error_message=NULL,error_retryable=NULL,lease_until=NULL,owner_token=NULL,updated_at=? WHERE job_id=?",
                (wire.canonical_json(envelope),envelope['updatedAt'],row['job_id']))
            conn.commit()
            return envelope

    def release(self, job_id: str, *, owner_token: str | None = None) -> None:
        """把租约归还，使任务重新可被认领（内部错误时的安全网）。"""
        with self._lock, self._connect() as conn:
            conn.execute(
                "UPDATE head_up_job SET lease_until = NULL WHERE job_id = ? AND status = ? AND (owner_token=? OR ? IS NULL)",
                (job_id, wire.STATUS_RUNNING, owner_token, owner_token),
            )

    def heartbeat(self, job_id: str, owner: str, lease_seconds: float = 900.0) -> bool:
        with self._lock,self._connect() as conn:
            return conn.execute("UPDATE head_up_job SET lease_until=? WHERE job_id=? AND owner_token=? AND status='RUNNING' AND lease_until>=?",(time.time()+lease_seconds,job_id,owner,time.time())).rowcount == 1

    # -- FRAME-5：任务阶段与逐窗口样本 --------------------------------------
    def set_stage(self, job_id: str, stage: str, *, error: bool = False, owner_token: str | None = None) -> None:
        """更新任务阶段（plan §18）。终态任务不再改阶段。"""
        with self._lock, self._connect() as conn:
            row = conn.execute("SELECT status,owner_token,lease_until FROM head_up_job WHERE job_id = ?",
                               (job_id,)).fetchone()
            if row is None or row["status"] in wire.TERMINAL_STATUSES or (owner_token is not None and (row["owner_token"]!=owner_token or (row["lease_until"] or 0)<time.time())):
                return
            if error:
                conn.execute(
                    "UPDATE head_up_job SET error_stage = ?, updated_at = ? WHERE job_id = ?",
                    (stage, wire.utc_now(), job_id))
            else:
                conn.execute(
                    "UPDATE head_up_job SET current_stage = ?, updated_at = ? WHERE job_id = ?",
                    (stage, wire.utc_now(), job_id))

    def stage_of(self, job_id: str) -> dict[str, Any] | None:
        row = self.get(job_id)
        if row is None:
            return None
        with self._connect() as conn:
            checkpoint = conn.execute('SELECT document FROM merge_checkpoint WHERE job_id=?', (job_id,)).fetchone()
            primary = conn.execute("SELECT COUNT(*) FROM merge_response WHERE job_id=? AND instr(call_key, ':primary:1:')>0", (job_id,)).fetchone()[0]
            pending = conn.execute("SELECT COUNT(*) FROM merge_transport WHERE job_id=? AND json_extract(document, '$.state')='PENDING'", (job_id,)).fetchone()[0]
            unknown = conn.execute("SELECT COUNT(*) FROM merge_transport WHERE job_id=? AND json_extract(document, '$.state')='UNKNOWN'", (job_id,)).fetchone()[0]
        saved = json.loads(checkpoint['document']) if checkpoint else {}
        planned = max(0, (row['analysis_end_ms']-row['analysis_start_ms']+row['window_ms']-1)//row['window_ms'])
        total = saved.get('totalWindows', planned)
        completed = min(total, max(0, saved.get('nextIndex', total if row['status']==wire.STATUS_SUCCEEDED else 0)))
        start = saved.get('processingStartedAt')
        end = saved.get('processingFinishedAt', __import__('datetime').datetime.fromisoformat(row['updated_at'].replace('Z','+00:00')).timestamp() if row['status'] in wire.TERMINAL_STATUSES else time.time())
        elapsed = max(0, end-start) if start else None
        eta = round(elapsed/completed*(total-completed)) if elapsed is not None and completed>=4 and row['status']==wire.STATUS_RUNNING else None
        if row['status']==wire.STATUS_SUCCEEDED: eta=0
        return {"currentStage": row.get("current_stage"), "errorStage": row.get("error_stage"),
                "completedWindows": completed, "totalWindows": total,
                "primaryReturnedWindows": min(total, primary),
                "pendingRequests": pending if row["status"]==wire.STATUS_RUNNING else 0, "unknownRequests": unknown, "percentage": round(100*completed/total, 1) if total else 0,
                "executionConcurrency": saved.get('executionConcurrency', 1),
                "effectiveConcurrency": saved.get('effectiveConcurrency', 1),
                "elapsedSeconds": round(elapsed) if elapsed is not None else None,
                "estimatedRemainingSeconds": eta,
                "estimateBasis": "已完成窗口的平均墙钟耗时，包含复核；仅为估计"}

    def save_samples(self, job_id: str, samples: list[dict[str, Any]]) -> int:
        """写入逐窗口样本（幂等：同 (job_id, window_id) 覆盖）。返回写入行数。

        兼容入口：需要知道"哪些窗口没写进去"时用 `save_samples_detailed()`。
        """
        return self.save_samples_detailed(job_id, samples).written

    def save_samples_detailed(self, job_id: str,
                              samples: list[dict[str, Any]], *, owner_token: str | None = None) -> SamplePersistOutcome:
        """写入逐窗口样本，**逐行隔离**：一行失败不影响其它窗口。

        为什么逐行隔离（补记修复）：旧实现把所有行放在同一个事务里，任何一行触发约束
        错误（例如抽帧失败窗口的 `timestamp_ms`）就会整批回滚 —— 一个坏窗口让整个任务的
        审计样本全部消失，而且只留一条日志。现在每个窗口一个 SAVEPOINT：坏行回滚自己，
        好行照写，失败窗口 id 与原因**显式返回**给调用方去记录。
        """
        outcome = SamplePersistOutcome()
        if not samples:
            return outcome
        now = wire.utc_now()
        columns = ("job_id", "client_request_id", "class_session_id", "media_id", "window_id",
                   "timestamp_ms", "window_start_ms", "window_end_ms", "visible_count",
                   "evaluable_count", "head_up_count", "head_down_count", "unknown_count",
                   "head_up_rate", "coverage", "quality", "quality_status", "quality_reason",
                   "reviewed", "review_strategy", "final_observation_source", "model_name",
                   "model_version", "prompt_version", "sampling_policy_version", "metric_version",
                   "statistics_version", "created_at")
        placeholders = ",".join("?" for _ in columns)
        updates = ",".join(f"{name}=excluded.{name}" for name in columns
                           if name not in ("job_id", "window_id", "created_at"))
        sql = (f"INSERT INTO head_up_sample ({','.join(columns)}) VALUES ({placeholders})"
               f" ON CONFLICT(job_id, window_id) DO UPDATE SET {updates}")
        with self._lock, self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                owner=conn.execute("SELECT owner_token FROM head_up_job WHERE job_id=?",(job_id,)).fetchone()
                if owner_token is not None and (owner is None or owner["owner_token"]!=owner_token):
                    conn.execute("ROLLBACK")
                    return outcome
                for index, sample in enumerate(samples):
                    payload = {name: sample.get(name) for name in columns}
                    payload["job_id"] = job_id
                    if not payload.get("created_at"):
                        payload["created_at"] = now
                    payload["reviewed"] = int(bool(sample.get("reviewed")))
                    savepoint = f"sample_{index}"
                    conn.execute(f"SAVEPOINT {savepoint}")
                    try:
                        conn.execute(sql, [payload[name] for name in columns])
                        conn.execute(f"RELEASE {savepoint}")
                        outcome.written += 1
                    except sqlite3.Error as exc:            # 单行失败：只回滚这一行
                        conn.execute(f"ROLLBACK TO {savepoint}")
                        conn.execute(f"RELEASE {savepoint}")
                        outcome.failed.append({
                            "windowId": sample.get("window_id"),
                            "error": f"{type(exc).__name__}: {exc}",
                        })
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
        return outcome

    def samples_of(self, job_id: str) -> list[dict[str, Any]]:
        """按课堂时间顺序返回样本；没有采样点的窗口（timestamp_ms 为空）用窗口起点排序。"""
        with self._lock, self._connect() as conn:
            rows = conn.execute(
                "SELECT * FROM head_up_sample WHERE job_id = ?"
                " ORDER BY COALESCE(timestamp_ms, window_start_ms), window_id",
                (job_id,)).fetchall()
        return [dict(row) for row in rows]

    # -- FRAME-6：性能指标 --------------------------------------------------
    def set_metrics(self, job_id: str, metrics: dict[str, Any] | None, *, owner_token: str | None = None) -> None:
        """写入一次运行的实测指标（终态后也可写：指标是结果的一部分，不是状态）。"""
        if metrics is None:
            return
        with self._lock, self._connect() as conn:
            conn.execute("UPDATE head_up_job SET metrics_json = ?, updated_at = ? WHERE job_id = ? AND (owner_token=? OR ? IS NULL)",
                         (wire.canonical_json(metrics), wire.utc_now(), job_id,owner_token,owner_token))

    def metrics_of(self, job_id: str) -> dict[str, Any] | None:
        with self._lock, self._connect() as conn:
            row = conn.execute("SELECT metrics_json FROM head_up_job WHERE job_id = ?",
                               (job_id,)).fetchone()
        if row is None or not row["metrics_json"]:
            return None
        try:
            return json.loads(row["metrics_json"])
        except ValueError:  # pragma: no cover - 库内数据损坏时不拖垮 GET
            return None

    def requeue_stale_running(self, *, older_than_seconds: float = 900.0) -> int:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT COUNT(*) AS n FROM head_up_job WHERE status = ? AND"
                " (lease_until IS NULL OR lease_until < ?)",
                (wire.STATUS_RUNNING, time.time() - older_than_seconds),
            ).fetchone()
        return int(row["n"])

    # -- 内部 -------------------------------------------------------------
    def _envelope_from_row(self, row: dict[str, Any]) -> dict[str, Any]:
        envelope = json.loads(row["envelope_json"])
        # 终态信封是不可变审计结果；后续样本、指标等附属写入会更新主表维护时间，
        # 但不能改写契约信封的 updatedAt。非终态才以主表调度状态为准。
        if row["status"] not in wire.TERMINAL_STATUSES:
            envelope["status"] = row["status"]
            envelope["revision"] = int(row["revision"])
            envelope["updatedAt"] = row["updated_at"]
        return envelope


def default_db_path(base_dir: str | os.PathLike[str]) -> str:
    return str(Path(base_dir) / ".data" / "headup.sqlite3")
