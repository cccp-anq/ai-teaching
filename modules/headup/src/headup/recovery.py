"""Fenced checkpoints and response journal for the sequential visual pipeline."""
from __future__ import annotations
from dataclasses import asdict
import hashlib,json,time,os
from pathlib import Path
from .wire import TaskError
from .frames import FramePlan
from .counts import WindowCounts
from .vision.schema import FrameReading

class LeaseLost(RuntimeError): pass
class BudgetStop(TaskError):
 def __init__(self,message):super().__init__("PROCESSING_FAILED",message,retryable=False)

class UnknownRequest(TaskError):
 def __init__(self,message='云端请求响应不明，该观测标记缺失，不重复计费'):
  super().__init__('PROCESSING_FAILED',message,retryable=False)

class Checkpoint:
 def __init__(self,store,row):
  self.store,self.job,self.owner=store,row['job_id'],row['owner_token']
  self.window=None;self.ordinal=0;self.phase='primary'
 def check(self):
  row=self.store.get(self.job)
  if row and os.environ.get('MERGE_MODE')=='1':
   from datetime import datetime,timezone
   created=datetime.fromisoformat(row['created_at'].replace('Z','+00:00')).timestamp()
   if time.time()-created>int(os.environ.get('MERGE_RECOGNITION_TIMEOUT_SECONDS','7200')):raise BudgetStop('视觉任务处理期限已过')
  if not row or row['owner_token']!=self.owner or row['status']!='RUNNING' or (row['lease_until'] or 0)<time.time():raise LeaseLost('worker lease lost')
 def load(self,signature):
  self.check()
  with self.store._connect() as conn:row=conn.execute('SELECT document FROM merge_checkpoint WHERE job_id=?',(self.job,)).fetchone()
  if not row:return None
  saved=json.loads(row['document'])
  if saved['signature']!=signature:raise TaskError('PROCESSING_FAILED','检查点输入或算法版本不同，拒绝续跑')
  return saved
 def save(self,document):
  with self.store._lock,self.store._connect() as conn:
   conn.execute('BEGIN IMMEDIATE')
   row=conn.execute('SELECT owner_token,status,lease_until FROM head_up_job WHERE job_id=?',(self.job,)).fetchone()
   if not row or row['owner_token']!=self.owner or row['status']!='RUNNING' or (row['lease_until'] or 0)<time.time():conn.rollback();raise LeaseLost('stale checkpoint')
   conn.execute('INSERT INTO merge_checkpoint(job_id,document) VALUES(?,?) ON CONFLICT(job_id) DO UPDATE SET document=excluded.document',(self.job,json.dumps(document,ensure_ascii=False)))
   conn.commit()
 def reserve_http(self,body):
  self.check()
  maximum=int(os.environ.get('MERGE_VL_MAX_REQUESTS','0'))
  if maximum<=0:raise BudgetStop('视觉 HTTP 调用预算未配置')
  self.transport+=1
  self.transport_key=f'{self.window}:{self.phase}:{self.ordinal}:{self.transport}:'+hashlib.sha256(json.dumps(body,sort_keys=True).encode()).hexdigest()
  with self.store._lock,self.store._connect() as conn:
   conn.execute('BEGIN IMMEDIATE')
   owner=conn.execute("SELECT owner_token,lease_until FROM head_up_job WHERE job_id=?",(self.job,)).fetchone()
   if not owner or owner['owner_token']!=self.owner or owner['lease_until']<time.time():conn.rollback();raise LeaseLost('stale transport')
   row=conn.execute('SELECT document FROM merge_transport WHERE job_id=? AND call_key=?',(self.job,self.transport_key)).fetchone()
   if row:
    document=json.loads(row['document'])
    if document['state']=='PENDING':
     conn.execute('UPDATE merge_transport SET document=? WHERE job_id=? AND call_key=?',(json.dumps({'state':'UNKNOWN'}),self.job,self.transport_key))
    conn.commit()
    if document['state']=='RETURNED':return document['payload']
    if document['state']=='REJECTED':raise TaskError('VISION_PROVIDER_ERROR','视觉模型返回 HTTP 429',retryable=True)
    raise UnknownRequest()
   conn.execute('INSERT OR IGNORE INTO merge_call_budget(job_id,calls) VALUES(?,0)',(self.job,))
   if conn.execute('UPDATE merge_call_budget SET calls=calls+1 WHERE job_id=? AND calls<?',(self.job,maximum)).rowcount!=1:conn.rollback();raise BudgetStop('视觉 HTTP 调用预算用尽')
   conn.execute('INSERT INTO merge_transport VALUES(?,?,?)',(self.job,self.transport_key,json.dumps({'state':'PENDING'})))
   conn.commit()
  return None
 def record_http(self,payload):
  self.check()
  with self.store._lock,self.store._connect() as conn:
   conn.execute('BEGIN IMMEDIATE')
   owner=conn.execute('SELECT owner_token,lease_until FROM head_up_job WHERE job_id=?',(self.job,)).fetchone()
   if owner['owner_token']!=self.owner or owner['lease_until']<time.time():conn.rollback();raise LeaseLost('stale returned transport')
   conn.execute('UPDATE merge_transport SET document=? WHERE job_id=? AND call_key=?',(json.dumps({'state':'RETURNED','payload':payload}),self.job,self.transport_key))
   conn.commit()
 def record_unknown(self):
  self.check()
  with self.store._lock,self.store._connect() as conn:
   conn.execute('BEGIN IMMEDIATE')
   owner=conn.execute('SELECT owner_token,status,lease_until FROM head_up_job WHERE job_id=?',(self.job,)).fetchone()
   if not owner or owner['owner_token']!=self.owner or owner['status']!='RUNNING' or (owner['lease_until'] or 0)<time.time():conn.rollback();raise LeaseLost('stale unknown transport')
   conn.execute('UPDATE merge_transport SET document=? WHERE job_id=? AND call_key=?',(json.dumps({'state':'UNKNOWN'}),self.job,self.transport_key))
   conn.commit()
 def record_rejection(self,status,retry_after=None):
  if status!=429:
   self.record_unknown()
   raise UnknownRequest('视觉请求返回错误，计费状态待核实；该观测缺失，不自动重试')
  self.check()
  with self.store._lock,self.store._connect() as conn:
   conn.execute('BEGIN IMMEDIATE')
   owner=conn.execute('SELECT owner_token,status,lease_until FROM head_up_job WHERE job_id=?',(self.job,)).fetchone()
   if not owner or owner['owner_token']!=self.owner or owner['status']!='RUNNING' or owner['lease_until']<time.time():conn.rollback();raise LeaseLost('stale rejected transport')
   conn.execute('UPDATE merge_transport SET document=? WHERE job_id=? AND call_key=?',(json.dumps({'state':'REJECTED','status':429}),self.job,self.transport_key))
   conn.commit()
 def fork(self,window):
  other=Checkpoint(self.store,{'job_id':self.job,'owner_token':self.owner})
  other.window_start(window)
  return other
 def window_start(self,window):self.check();self.window=window;self.ordinal=0;self.phase='primary';self.transport=0
 def journal_analyzer(self,inner):return JournalAnalyzer(inner,self)

class JournalAnalyzer:
 def __init__(self,inner,checkpoint):
  self.inner,self.cp=inner,checkpoint
  if callable(getattr(inner,'analyze_window',None)):self.analyze_window=self._many
  # Hook each transport request, including retries and fallback models.
  setattr(inner,'before_request',checkpoint.reserve_http)
  setattr(inner,'after_request',checkpoint.record_http)
  setattr(inner,'request_rejected',checkpoint.record_rejection)
  setattr(inner,'request_unknown',checkpoint.record_unknown)
 def __getattr__(self,k):return getattr(self.inner,k)
 def set_phase(self,phase):self.cp.phase=phase
 def analyze(self,path):return self._call([path],lambda:self.inner.analyze(path))
 def _many(self,paths):return self._call(paths,lambda:self.inner.analyze_window(paths))
 def restore(self,models,trace,state=None):
  if hasattr(self.inner,'used_models'):self.inner.used_models=list(models)
  if hasattr(self.inner,'last_trace'):self.inner.last_trace=dict(trace)
  for key,value in (state or {}).items():
   if hasattr(self.inner,key):setattr(self.inner,key,value)
 def _call(self,paths,invoke):
  self.cp.check();self.cp.ordinal+=1;self.cp.transport=0
  fingerprint=hashlib.sha256(b''.join(Path(p).read_bytes() for p in paths)).hexdigest()
  key=f'{self.cp.window}:{self.cp.phase}:{self.cp.ordinal}:{fingerprint}'
  with self.cp.store._connect() as conn:row=conn.execute('SELECT document FROM merge_response WHERE job_id=? AND call_key=?',(self.cp.job,key)).fetchone()
  if row:
   saved=json.loads(row['document']);data=saved['reading'];self.restore(saved.get('models',[]),saved.get('trace',{}),saved.get('state',{}));data['counts']=WindowCounts(**data['counts']) if data.get('counts') else None;reading=FrameReading(**data)
   return reading
  reading=invoke();self.cp.check()
  with self.cp.store._lock,self.cp.store._connect() as conn:
   conn.execute('BEGIN IMMEDIATE')
   owner=conn.execute('SELECT owner_token,lease_until FROM head_up_job WHERE job_id=?',(self.cp.job,)).fetchone()
   if owner['owner_token']!=self.cp.owner or owner['lease_until']<time.time():conn.rollback();raise LeaseLost('stale response')
   conn.execute('INSERT INTO merge_response(job_id,call_key,document) VALUES(?,?,?)',(self.cp.job,key,json.dumps({'reading':asdict(reading),'models':list(getattr(self.inner,'used_models',[])),'trace':dict(getattr(self.inner,'last_trace',{})),'state':{k:getattr(self.inner,k) for k in ('request_count','token_totals','request_notes') if hasattr(self.inner,k)}},ensure_ascii=False)))
   conn.commit()
  return reading

def signature(request,cfg,plans):
 # Exclude credentials and expiring URLs; retain all algorithmic settings.
 config=asdict(cfg)
 for k in ('bearer_tokens','vision_api_key','db_path','work_dir','server_port','server_host'):config.pop(k,None)
 content={'request':request.idempotency_fingerprint(),'config':config,'plans':[asdict(p) for p in plans],'checkpointVersion':1}
 return hashlib.sha256(json.dumps(content,sort_keys=True).encode()).hexdigest()
