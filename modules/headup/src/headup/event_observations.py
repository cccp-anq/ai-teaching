"""Independent point observations: no fixed-window aggregation or cross-window state."""
from __future__ import annotations
from contextlib import contextmanager
import base64
import hashlib
import json
import os
import re
import sqlite3
import tempfile
from dataclasses import replace
from pathlib import Path
from .wire import ApiError
from .vision.qwen_client import QwenVlAnalyzer
from .analysis.quality import classify_window

class EventObservations:
    def __init__(self,cfg):
        self.cfg=cfg
        self.root=Path(cfg.work_dir)/'event-observations'
        self.root.mkdir(parents=True,exist_ok=True)
        self.db=self.root/'requests.sqlite3'
        with self.connect() as db:
            db.execute('CREATE TABLE IF NOT EXISTS observation (request_key TEXT PRIMARY KEY, task_id TEXT NOT NULL, input_hash TEXT NOT NULL, status TEXT NOT NULL, result TEXT)')
            db.execute("UPDATE observation SET status='UNKNOWN' WHERE status='PENDING'")
    @contextmanager
    def connect(self):
        db=sqlite3.connect(self.db,timeout=15)
        try:
            with db:yield db
        finally:db.close()
    def get(self,key):
        if not re.fullmatch(r'[a-f0-9]{64}',key):raise ApiError(422,'VALIDATION_FAILED','无效观测键')
        with self.connect() as db:row=db.execute('SELECT status,result FROM observation WHERE request_key=?',(key,)).fetchone()
        if row is None:raise ApiError(404,'NOT_FOUND','观测不存在')
        return {'state':row[0],**(json.loads(row[1]) if row[1] else {})}
    def observe(self,body):
        if os.getenv('MERGE_RECOGNITION_ENABLED','false').lower()!='true':raise ApiError(409,'DISABLED','真实识别未启用')
        key=body.get('requestKey','');task=body.get('taskId','')
        if not re.fullmatch(r'[a-f0-9]{64}',key) or not re.fullmatch(r'[a-f0-9-]{36}',task):raise ApiError(422,'VALIDATION_FAILED','观测身份无效')
        at=body.get('sampleAtMs');duration=body.get('durationMs')
        if type(at)!=int or type(duration)!=int or not 0<=at<duration<=3600000:raise ApiError(422,'VALIDATION_FAILED','观测时间无效')
        try:image=base64.b64decode(body['jpeg'],validate=True)
        except (KeyError,ValueError):raise ApiError(422,'VALIDATION_FAILED','截图编码无效')
        if len(image)>1000000 or not image.startswith(b'\xff\xd8'):raise ApiError(413,'PAYLOAD_TOO_LARGE','截图无效或过大')
        fingerprint=hashlib.sha256(json.dumps(body,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('SELECT input_hash FROM observation WHERE request_key=?',(key,)).fetchone()
            if row:
                if row[0]!=fingerprint:raise ApiError(409,'IDEMPOTENCY_CONFLICT','观测内容变化')
                return self.get(key)
            if db.execute('SELECT COUNT(*) FROM observation WHERE task_id=?',(task,)).fetchone()[0]>=12:raise ApiError(409,'BUDGET_EXHAUSTED','事件观测额度已用完')
            db.execute('INSERT INTO observation VALUES(?,?,?,?,NULL)',(key,task,fingerprint,'PENDING'))
        path=None
        try:
            # A fresh analyzer for every observation. No retry, tiling, fallback or sequential baseline.
            cfg=replace(self.cfg,vision_max_retries=0,counts_retry_extra=0,vision_fallback_model='',frame_tile_grid='1x1',vision_max_tokens=512,vision_max_long_side=1600,vision_disable_thinking=True)
            if cfg.vision_model!='qwen3-vl-flash':raise ValueError('event price profile does not support model')
            with tempfile.NamedTemporaryFile(suffix='.jpg',dir=self.root,delete=False) as f:f.write(image);path=Path(f.name)
            analyzer=QwenVlAnalyzer(cfg)
            reading=analyzer.analyze(path)
            decision=classify_window(reading.counts,valid_threshold=cfg.quality_valid_threshold,review_threshold=cfg.quality_review_threshold)
            c=reading.counts
            result={'sampleAtMs':at,'headUpCount':c.head_up_count if c else None,'validObservedCount':c.evaluable_count if c else None,'visibleStudentCount':c.visible_count if c else None,'headUpRate':decision.head_up_rate,'coverage':decision.coverage,'quality':decision.quality,'qualityReason':decision.reason,'model':reading.model_used,'schemaVersion':'event-observation-v1','promptVersion':analyzer.prompt_version,'usage':analyzer.token_totals,'includedInBaseStatistics':False}
            with self.connect() as db:db.execute('UPDATE observation SET status=?,result=? WHERE request_key=?',('SUCCEEDED',json.dumps(result),key))
        except Exception:
            # Even rejected or malformed responses retain their reservation; never resubmit.
            with self.connect() as db:db.execute('UPDATE observation SET status=? WHERE request_key=?',('UNKNOWN',key))
        finally:
            if path:path.unlink(missing_ok=True)
        return self.get(key)
    def delete(self,task):
        if not re.fullmatch(r'[a-f0-9-]{36}',task):raise ApiError(422,'VALIDATION_FAILED','任务身份无效')
        with self.connect() as db:
            if db.execute("SELECT COUNT(*) FROM observation WHERE task_id=? AND status='PENDING'",(task,)).fetchone()[0]:raise ApiError(409,'PROCESSING','观测状态未确定')
            db.execute('DELETE FROM observation WHERE task_id=?',(task,))
