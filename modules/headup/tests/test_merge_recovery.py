from pathlib import Path
from dataclasses import replace
import json,time,unittest,os
from unittest.mock import patch
from headup import wire
from headup.store import Store
from headup.recovery import Checkpoint,LeaseLost,BudgetStop,UnknownRequest
from headup.pipeline import run_job,WindowOutcome
from headup.vision.schema import FrameReading
from support import temp_root,demo_request_doc,make_test_media,find_tools
from headup.config import Config

class MergeRecoveryTest(unittest.TestCase):
 def setup_job(self):
  root=temp_root('merge-recovery-');store=Store(str(root/'jobs.db'))
  doc=demo_request_doc();req=wire.parse_job_request(doc,idempotency_key=None,allowed_definition_ids={doc['parameters']['definitionId']},max_duration_ms=7200000,max_windows=20000)
  store.submit(req,provider_id='test',module_version='test');row=store.claim_next();return root,store,req,row
 def test_stale_owner_cannot_checkpoint_or_renew(self):
  root,store,req,row=self.setup_job();cp=Checkpoint(store,row)
  cp.save({'signature':'test','nextIndex':1,'previousValidRate':.8})
  with store._connect() as conn:conn.execute('UPDATE head_up_job SET lease_until=0')
  replacement=store.claim_next()
  self.assertNotEqual(row['owner_token'],replacement['owner_token'])
  self.assertFalse(store.heartbeat(row['job_id'],row['owner_token']))
  with self.assertRaises(LeaseLost):cp.save({'signature':'test'})
  self.assertEqual(Checkpoint(store,replacement).load('test')['previousValidRate'],.8)
 def test_checkpoint_version_mismatch_rejected(self):
  root,store,req,row=self.setup_job();cp=Checkpoint(store,row);cp.save({'signature':'a'})
  with self.assertRaises(wire.TaskError):cp.load('b')
 def test_http_pending_is_not_resubmitted_and_budget_durable(self):
  root,store,req,row=self.setup_job();cp=Checkpoint(store,row);cp.window_start('w1');cp.ordinal=1;cp.transport=0
  with patch.dict(os.environ,{'MERGE_VL_MAX_REQUESTS':'2'}):
   self.assertIsNone(cp.reserve_http({'model':'fake'}))
   fresh=Checkpoint(store,row);fresh.window_start('w1');fresh.ordinal=1;fresh.transport=0
   with self.assertRaises(UnknownRequest):fresh.reserve_http({'model':'fake'})
   cp.record_http({'choices':[{'message':{'content':'ok'}}]})
   fresh.transport=0
   self.assertEqual(fresh.reserve_http({'model':'fake'})['choices'][0]['message']['content'],'ok')
   with store._connect() as conn:self.assertEqual(conn.execute('SELECT calls FROM merge_call_budget').fetchone()[0],1)
 def test_response_journal_replays_without_model_call(self):
  root,store,req,row=self.setup_job();frame=root/'f.jpg';frame.write_bytes(b'test')
  class Fake:
   name='fake';version='v1';used_models=[];last_trace={}
   calls=0
   def analyze(self,path):self.calls+=1;return FrameReading(8,2,0,1,1,model_used='fake',trace={'id':'r1'})
  cp=Checkpoint(store,row);cp.window_start('w1');fake=Fake();first=cp.journal_analyzer(fake);a=first.analyze(frame)
  resumed=Checkpoint(store,row);resumed.window_start('w1');second=resumed.journal_analyzer(fake);b=second.analyze(frame)
  self.assertEqual(fake.calls,1);self.assertEqual(a.head_up_rate,b.head_up_rate)
 def test_cleanup_requires_terminal_task(self):
  root,store,req,row=self.setup_job()
  with self.assertRaises(wire.ApiError):store.delete_terminal(row['job_id'])
  with store._connect() as conn:conn.execute("UPDATE head_up_job SET status='FAILED' WHERE job_id=?",(row['job_id'],))
  self.assertIsNotNone(store.delete_terminal(row['job_id']))
  self.assertIsNone(store.get(row['job_id']))
 @unittest.skipUnless(find_tools(),'ffmpeg missing')
 def test_sequential_resume_restores_previous_final_valid_window(self):
  root,store,req,row=self.setup_job();media=make_test_media(root,duration_ms=60000)
  ffmpeg,ffprobe=find_tools();cfg=Config(work_dir=str(root/'work'),ffmpeg_path=ffmpeg,ffprobe_path=ffprobe)
  class Fake:
   name='fake';version='v1';used_models=[];last_trace={}
  class Crash(BaseException):pass
  class InterruptedCheckpoint(Checkpoint):
   def save(self,doc):
    super().save(doc)
    if doc['nextIndex']==3:raise Crash()
  decisions=[]
  def process(tools,media,plan,frames,cfg,analyzer,reports,previous_valid_rate=None,**kw):
   rates=[.8,.6,None,.1,.9,.7];rate=rates[plan.window_index]
   review=previous_valid_rate is not None and rate is not None and abs(rate-previous_valid_rate)>=.2
   final=.65 if review else rate
   decisions.append((plan.window_id,previous_valid_rate,review,final))
   report={'windowId':plan.window_id,'reviewDecision':review};reports.append(report)
   return WindowOutcome(plan=plan,quality='LOW_QUALITY' if rate is None else 'VALID',quality_reason='UNREADABLE' if rate is None else None,head_up=None if rate is None else round(final*100),observed=None if rate is None else 100,head_up_rate=final,coverage=1,quality_status='UNREADABLE' if rate is None else 'VALID',report=report)
  with patch('headup.pipeline._process_window',side_effect=process):
   whole=run_job(request=req,cfg=cfg,analyzer=Fake(),work_root=root/'whole',local_media=media)
   expected=list(decisions);decisions.clear()
   with self.assertRaises(Crash):run_job(request=req,cfg=cfg,analyzer=Fake(),work_root=root/'resume',local_media=media,checkpoint=InterruptedCheckpoint(store,row))
   resumed=run_job(request=req,cfg=cfg,analyzer=Fake(),work_root=root/'resume',local_media=media,checkpoint=Checkpoint(store,row))
  self.assertEqual(decisions,expected)
  self.assertEqual(resumed.payload['windows'],whole.payload['windows'])
  self.assertEqual(resumed.stats,whole.stats)
if __name__=='__main__':unittest.main()
