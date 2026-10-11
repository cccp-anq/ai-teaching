"""Cloud-only integration tests: ordering, replay, budget and HTTP admission."""
from dataclasses import replace
from pathlib import Path
import json
import os
import threading
import time
import unittest
from unittest.mock import patch
from headup import wire
from headup.cloud import CloudBatcher, CloudGate
from headup.config import Config
from headup.counts import make_counts
from headup.pipeline import run_job, ReviewObservation
from headup.recovery import Checkpoint, BudgetStop, LeaseLost, UnknownRequest
from headup.store import Store
from headup.vision.schema import FrameReading
from support import temp_root, demo_request_doc, make_test_media, find_tools

class Tracker:
    def __init__(self, slow=False):
        self.lock=threading.Lock();self.active=0;self.maximum=0
        self.calls=[];self.returned=[];self.instances=[];self.slow=slow

class CloudFake:
    name='cloud-fake';version='v1'
    def __init__(self, tracker):
        self.tracker=tracker;self.used_models=[];self.last_trace={};self.request_count=0
        self.token_totals={'promptTokens':0,'completionTokens':0};self.request_notes=[]
        tracker.instances.append(self)
    def fork(self):return type(self)(self.tracker)
    def analyze(self,path):
        index=int(Path(path).stem);body={'index':index,'model':'cloud-fake'}
        try:
            before=getattr(self,'before_request',None)
            cached=before(body) if before else None
            if cached is None:
                self.request_count+=1
                with self.tracker.lock:
                    self.tracker.active+=1;self.tracker.maximum=max(self.tracker.maximum,self.tracker.active)
                    self.tracker.calls.append(index)
                try:
                    if self.tracker.slow:time.sleep(1.5 if index%4==0 else (0.2 if index%4==3 else 1.0))
                    cached={'index':index,'up':[8,1,8,0,9,7][index]}
                    after=getattr(self,'after_request',None)
                    if after:after(cached)
                finally:
                    with self.tracker.lock:
                        self.tracker.active-=1;self.tracker.returned.append(index)
        finally:
            finish=getattr(self,'request_finished',None)
            if finish:finish()
        self.used_models.append('cloud-fake');self.last_trace={'requestId':f'req-{index}'}
        self.token_totals['promptTokens']+=100;self.token_totals['completionTokens']+=10
        return FrameReading(cached['up'],10-cached['up'],0,1,1,model_used='cloud-fake',trace=self.last_trace)

@unittest.skipUnless(find_tools(),'ffmpeg missing')
class CloudPipelineTest(unittest.TestCase):
    def setup_job(self):
        root=temp_root('cloud-pipeline-');store=Store(str(root/'jobs.db'));doc=demo_request_doc()
        req=wire.parse_job_request(doc,idempotency_key=None,allowed_definition_ids={doc['parameters']['definitionId']},max_duration_ms=7200000,max_windows=20000)
        store.submit(req,provider_id='test',module_version='test');row=store.claim_next()
        ffmpeg,ffprobe=find_tools();cfg=Config(work_dir=str(root/'work'),ffmpeg_path=ffmpeg,ffprobe_path=ffprobe,analysis_concurrency=4)
        media=make_test_media(root,duration_ms=60000)
        return root,store,req,row,cfg,media
    def extract(self,**kwargs):
        root=kwargs['workspace'].primary_dir;frames={}
        for plan in kwargs['plans']:
            frame=root/f'{plan.window_index}.jpg';frame.write_bytes(f'frame-{plan.window_index}'.encode());frames[plan.window_id]=frame
        return frames
    def review(self,**kwargs):
        return ReviewObservation(make_counts(visible_count=10,head_up_count=6,head_down_count=4,unknown_count=0,confidence=1,strict=True),3,'MULTI_IMAGE_SINGLE_REQUEST')
    def run_pipeline(self,root,req,cfg,media,tracker,cp=None):
        with patch('headup.pipeline._extract_primary_frames',side_effect=self.extract),patch('headup.pipeline._review_observation',side_effect=self.review),patch.dict(os.environ,{'MERGE_VL_MAX_REQUESTS':'100'}):
            return run_job(request=req,cfg=cfg,analyzer=CloudFake(tracker),work_root=root/'jobs',local_media=media,checkpoint=cp)
    def test_out_of_order_cloud_returns_match_serial_final_sequence(self):
        root,store,req,row,cfg,media=self.setup_job();serial=Tracker()
        baseline=self.run_pipeline(root,req,replace(cfg,analysis_concurrency=1),media,serial)
        cloud=Tracker(slow=True)
        result=self.run_pipeline(root,req,cfg,media,cloud,Checkpoint(store,row))
        self.assertGreaterEqual(cloud.maximum,3);self.assertLessEqual(cloud.maximum,4)
        self.assertNotEqual(cloud.returned,list(range(6)))
        self.assertEqual(result.payload['windows'],baseline.payload['windows'])
        self.assertTrue(all(row['visibleStudentCount']==10 for row in result.payload['windows']))
        self.assertEqual(result.stats,baseline.stats)
        saved=Checkpoint(store,row).load(__import__('headup.recovery',fromlist=['signature']).signature(req,cfg,[w.plan for w in result.windows]))
        self.assertEqual(saved['analyzerState']['request_count'],6)
        self.assertEqual(saved['analyzerState']['token_totals']['promptTokens'],600)
        self.assertEqual(saved['nextIndex'],6)
        self.assertEqual(store.stage_of(row['job_id'])['completedWindows'],6)
        self.assertEqual(store.stage_of(row['job_id'])['pendingRequests'],0)
    def test_restart_after_ordered_prefix_reuses_later_raw_results(self):
        root,store,req,row,cfg,media=self.setup_job();tracker=Tracker()
        class Crash(BaseException):pass
        class Interrupted(Checkpoint):
            def save(self,document):
                super().save(document)
                if document['nextIndex']==2:raise Crash()
        with self.assertRaises(Crash):self.run_pipeline(root,req,cfg,media,tracker,Interrupted(store,row))
        self.assertEqual(sorted(tracker.calls),[0,1,2,3])
        result=self.run_pipeline(root,req,cfg,media,tracker,Checkpoint(store,row))
        self.assertEqual(sorted(tracker.calls),list(range(6)))
        baseline=self.run_pipeline(root,req,replace(cfg,analysis_concurrency=1),media,Tracker())
        self.assertEqual(result.payload['windows'],baseline.payload['windows'])
        with store._connect() as conn:
            saved=json.loads(conn.execute('SELECT document FROM merge_checkpoint').fetchone()[0])
            calls=conn.execute('SELECT calls FROM merge_call_budget').fetchone()[0]
        self.assertEqual(calls,6);self.assertEqual(saved['analyzerState']['request_count'],6)
        self.assertEqual(saved['analyzerState']['token_totals']['promptTokens'],600)
    def test_concurrent_workers_cannot_exceed_durable_global_budget(self):
        root,store,req,row,cfg,media=self.setup_job();cp=Checkpoint(store,row)
        from concurrent.futures import ThreadPoolExecutor
        def reserve(index):
            worker=cp.fork(str(index));worker.ordinal=1
            try:worker.reserve_http({'index':index});return True
            except BudgetStop:return False
        with patch.dict(os.environ,{'MERGE_VL_MAX_REQUESTS':'2'}),ThreadPoolExecutor(max_workers=4) as pool:
            results=list(pool.map(reserve,range(8)))
        self.assertEqual(sum(results),2)
        with store._connect() as conn:self.assertEqual(conn.execute('SELECT calls FROM merge_call_budget').fetchone()[0],2)
    def test_stale_parallel_worker_cannot_commit_returned_response(self):
        root,store,req,row,cfg,media=self.setup_job();cp=Checkpoint(store,row).fork('w');cp.ordinal=1
        with patch.dict(os.environ,{'MERGE_VL_MAX_REQUESTS':'2'}):cp.reserve_http({'index':0})
        with store._connect() as conn:conn.execute('UPDATE head_up_job SET lease_until=0')
        store.claim_next()
        with self.assertRaises(LeaseLost):cp.record_http({'index':0})

    def test_unknown_primary_is_missing_but_remaining_windows_finish(self):
        root,store,req,row,cfg,media=self.setup_job();tracker=Tracker();original=CloudFake.analyze
        def analyze(inner,path):
            if int(Path(path).stem)==1:raise UnknownRequest()
            return original(inner,path)
        with patch.object(CloudFake,'analyze',analyze):result=self.run_pipeline(root,req,cfg,media,tracker,Checkpoint(store,row))
        self.assertEqual(len(result.windows),6);self.assertEqual(result.windows[1].quality,'LOW_QUALITY')
        self.assertIsNone(result.windows[1].head_up_rate)
        self.assertIn('WARN_UNKNOWN_REQUEST_NOT_RESUBMITTED',result.payload['warnings'])
        self.assertEqual(sorted(tracker.calls),[0,2,3,4,5])
    def test_unknown_review_is_excluded_and_never_falls_back_to_single_frames(self):
        root,store,req,row,cfg,media=self.setup_job();tracker=Tracker();reviews=[]
        def review(inner,paths):reviews.append(paths);raise UnknownRequest()
        with patch.object(CloudFake,'analyze_window',review,create=True),patch('headup.pipeline._extract_primary_frames',side_effect=self.extract),patch.dict(os.environ,{'MERGE_VL_MAX_REQUESTS':'100'}):
            result=run_job(request=req,cfg=cfg,analyzer=CloudFake(tracker),work_root=root/'jobs',local_media=media,checkpoint=Checkpoint(store,row))
        self.assertEqual(len(result.windows),6);self.assertTrue(reviews)
        self.assertEqual(result.windows[1].quality,'LOW_QUALITY');self.assertIsNone(result.windows[1].head_up_rate)
        self.assertEqual(sorted(tracker.calls),list(range(6)))
        self.assertIn('WARN_UNKNOWN_REQUEST_NOT_RESUBMITTED',result.payload['warnings'])
    def test_failed_unknown_resume_keeps_budget_and_rejects_identity_mismatch(self):
        root,store,req,row,cfg,media=self.setup_job();cp=Checkpoint(store,row);cp.save({'signature':'test','nextIndex':2})
        cp.window_start('w');cp.ordinal=1
        with patch.dict(os.environ,{'MERGE_VL_MAX_REQUESTS':'2'}):cp.reserve_http({'index':2})
        with store._connect() as conn:
            env=json.loads(conn.execute('SELECT envelope_json FROM head_up_job').fetchone()[0]);env.update(status='FAILED',revision=1,error={'code':'PROCESSING_FAILED','message':'提交状态不明','retryable':False})
            conn.execute("UPDATE head_up_job SET status='FAILED',envelope_json=?,error_message='提交状态不明',lease_until=NULL",(json.dumps(env),))
        identity={'classSessionId':req.class_session_id,'mediaId':req.media_id,'mediaSha256':req.media.sha256,'windowMs':req.parameters.window_ms}
        with self.assertRaises(wire.ApiError):store.resume_unknown({**identity,'mediaSha256':'wrong'},req.client_request_id)
        returned=store.resume_unknown(identity,req.client_request_id);self.assertEqual(returned['jobId'],row['job_id']);self.assertEqual(returned['status'],'QUEUED')
        with store._connect() as conn:
            self.assertEqual(conn.execute('SELECT calls FROM merge_call_budget').fetchone()[0],1)
            self.assertEqual(json.loads(conn.execute('SELECT document FROM merge_checkpoint').fetchone()[0])['nextIndex'],2)
        fresh=Checkpoint(store,store.claim_next());fresh.window_start('w');fresh.ordinal=1
        with patch.dict(os.environ,{'MERGE_VL_MAX_REQUESTS':'2'}):
            with self.assertRaises(UnknownRequest):fresh.reserve_http({'index':2})
        self.assertEqual(store.stage_of(row['job_id'])['unknownRequests'],1)

class CloudGateTest(unittest.TestCase):
    def test_429_reduces_concurrency_and_respects_cooldown(self):
        gate=CloudGate(4)
        with patch('headup.cloud.time.monotonic',return_value=100):gate.rejected('7')
        self.assertEqual(gate.limit,1);self.assertEqual(gate.cooldown,107)
        with self.assertRaises(BudgetStop):gate.rejected('60')
        self.assertTrue(gate.stopped.is_set())
    def test_pending_checkpoint_replay_never_resubmits(self):
        root=temp_root('cloud-pending-');store=Store(str(root/'db'));doc=demo_request_doc()
        req=wire.parse_job_request(doc,idempotency_key=None,allowed_definition_ids={doc['parameters']['definitionId']},max_duration_ms=7200000,max_windows=20000)
        store.submit(req,provider_id='test',module_version='test');row=store.claim_next();frame=root/'0.jpg';frame.write_bytes(b'frame')
        cp=Checkpoint(store,row).fork('w');cp.ordinal=1
        with patch.dict(os.environ,{'MERGE_VL_MAX_REQUESTS':'8'}):
            cp.reserve_http({'index':0,'model':'cloud-fake'})
            tracker=Tracker();worker=Checkpoint(store,row).fork('w').journal_analyzer(CloudFake(tracker))
            with self.assertRaises(UnknownRequest):worker.analyze(frame)
            self.assertEqual(tracker.calls,[])

class CloudTransportTest(unittest.TestCase):
    def setup_transport(self):
        from headup.vision.qwen_client import QwenVlAnalyzer
        from PIL import Image
        root=temp_root('cloud-transport-');store=Store(str(root/'db'));doc=demo_request_doc()
        req=wire.parse_job_request(doc,idempotency_key=None,allowed_definition_ids={doc['parameters']['definitionId']},max_duration_ms=7200000,max_windows=20000)
        store.submit(req,provider_id='test',module_version='test');row=store.claim_next();frame=root/'0.jpg';Image.new('RGB',(32,32)).save(frame)
        cp=Checkpoint(store,row);cp.window_start('w')
        raw=QwenVlAnalyzer(Config(vision_api_key='test-not-real',vision_max_retries=1))
        wrapped=cp.journal_analyzer(raw);gate=CloudGate(4);gate.attach(raw,cp)
        return store,cp,frame,raw,wrapped,gate
    def test_timeout_stops_without_resubmitting_and_releases_slot(self):
        store,cp,frame,raw,wrapped,gate=self.setup_transport()
        with patch.dict(os.environ,{'MERGE_VL_MAX_REQUESTS':'8'}),patch('headup.vision.qwen_client.urllib.request.urlopen',side_effect=TimeoutError) as http:
            with self.assertRaises(UnknownRequest):wrapped.analyze(frame)
            self.assertEqual(http.call_count,1)
        self.assertEqual(raw.request_count,1);self.assertEqual(gate.active,0)
        with store._connect() as conn:
            self.assertEqual(json.loads(conn.execute('SELECT document FROM merge_transport').fetchone()[0])['state'],'UNKNOWN')
    def test_429_is_journaled_retry_is_bounded_and_slot_is_released(self):
        import io
        import urllib.error
        store,cp,frame,raw,wrapped,gate=self.setup_transport()
        failure=urllib.error.HTTPError('https://fake.invalid',429,'limited',{'Retry-After':'2'},io.BytesIO(b'limited'))
        content=json.dumps({'visible_count':10,'head_up_count':8,'head_down_count':2,'unknown_count':0,'confidence':1})
        payload={'id':'r1','model':'cloud-fake','choices':[{'message':{'content':content}}],'usage':{'prompt_tokens':100,'completion_tokens':10}}
        with patch.dict(os.environ,{'MERGE_VL_MAX_REQUESTS':'8'}),patch.object(gate,'rejected') as cooldown,patch('headup.vision.qwen_client.urllib.request.urlopen',side_effect=[failure,io.BytesIO(json.dumps(payload).encode())]) as http:
            reading=wrapped.analyze(frame)
            self.assertEqual(reading.head_up,8);self.assertEqual(http.call_count,2)
            cooldown.assert_called_once_with('2')
        self.assertEqual(gate.active,0);self.assertEqual(raw.request_count,2)
        with store._connect() as conn:
            states=[json.loads(r[0])['state'] for r in conn.execute('SELECT document FROM merge_transport')]
            self.assertEqual(sorted(states),['REJECTED','RETURNED'])
            self.assertEqual(conn.execute('SELECT calls FROM merge_call_budget').fetchone()[0],2)
    def test_other_http_error_stops_without_blind_retry(self):
        import io
        import urllib.error
        store,cp,frame,raw,wrapped,gate=self.setup_transport()
        failure=urllib.error.HTTPError('https://fake.invalid',500,'error',{},io.BytesIO(b'error'))
        with patch.dict(os.environ,{'MERGE_VL_MAX_REQUESTS':'8'}),patch('headup.vision.qwen_client.urllib.request.urlopen',side_effect=failure) as http:
            with self.assertRaises(UnknownRequest):wrapped.analyze(frame)
            self.assertEqual(http.call_count,1)
        self.assertEqual(gate.active,0)

if __name__=='__main__':unittest.main()
