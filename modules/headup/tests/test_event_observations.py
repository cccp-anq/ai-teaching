import base64
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from PIL import Image
from headup.config import Config
from headup.event_observations import EventObservations
from headup.vision.schema import FrameReading
from headup.counts import WindowCounts
from headup.wire import ApiError

class EventObservationTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.cfg=Config(work_dir=self.temp.name,vision_api_key='fake',vision_model='qwen3-vl-flash')
        self.events=EventObservations(self.cfg)
        image=Image.new('RGB',(640,480),'white');b=io.BytesIO();image.save(b,format='JPEG')
        self.body={'requestKey':'a'*64,'taskId':'12345678-1234-1234-1234-123456789012','sampleAtMs':2000,'durationMs':10000,'jpeg':base64.b64encode(b.getvalue()).decode()}
        self.env=patch.dict(os.environ,{'MERGE_RECOGNITION_ENABLED':'true'});self.env.start()
    def tearDown(self):
        self.env.stop();self.temp.cleanup()
    def fake(self,path):
        return FrameReading(head_up=2,head_down=2,unknown=0,legible_ratio=1,confidence=.9,model_used='qwen3-vl-flash')
    def test_cached_idempotent_single_call_and_base_store_untouched(self):
        with patch('headup.event_observations.QwenVlAnalyzer.analyze',side_effect=self.fake) as model:
            first=self.events.observe(self.body);second=self.events.observe(self.body)
            self.assertEqual(first['state'],'SUCCEEDED');self.assertEqual(first['headUpRate'],.5)
            self.assertEqual(first,second);self.assertEqual(model.call_count,1)
            self.assertFalse(first['includedInBaseStatistics'])
            self.assertEqual(list(self.events.root.glob('*.jpg')),[])
    def test_unknown_not_retried_and_failed_calls_keep_cap(self):
        with patch('headup.event_observations.QwenVlAnalyzer.analyze',side_effect=TimeoutError) as model:
            self.assertEqual(self.events.observe(self.body)['state'],'UNKNOWN')
            restarted=EventObservations(self.cfg)
            self.assertEqual(restarted.observe(self.body)['state'],'UNKNOWN')
            self.assertEqual(model.call_count,1)
            for i in range(11):self.events.observe({**self.body,'requestKey':f'{i:064x}'})
            with self.assertRaises(ApiError):self.events.observe({**self.body,'requestKey':'f'*64})
            self.assertEqual(model.call_count,12)
    def test_inflight_restart_becomes_unknown_without_resubmission(self):
        with self.events.connect() as db:db.execute('INSERT INTO observation VALUES(?,?,?,?,NULL)',('b'*64,self.body['taskId'],'hash','PENDING'))
        restarted=EventObservations(self.cfg)
        self.assertEqual(restarted.get('b'*64)['state'],'UNKNOWN')
    def test_same_key_different_frame_time_conflicts(self):
        with patch('headup.event_observations.QwenVlAnalyzer.analyze',side_effect=self.fake):
            self.events.observe(self.body)
            with self.assertRaises(ApiError):self.events.observe({**self.body,'sampleAtMs':3000})
    def test_forces_single_request_no_fallback_and_independent_state(self):
        captures=[]
        def factory(cfg):
            captures.append(cfg)
            class Fake:
                prompt_version='p';token_totals={}
                def analyze(inner,path):return self.fake(path)
            return Fake()
        with patch('headup.event_observations.QwenVlAnalyzer',side_effect=factory):self.events.observe(self.body)
        self.assertEqual(captures[0].vision_max_retries,0);self.assertEqual(captures[0].counts_retry_extra,0);self.assertEqual(captures[0].vision_fallback_model,'');self.assertEqual(captures[0].frame_tile_grid,'1x1')
    def test_invalid_sample_time_and_disabled_mode_rejected(self):
        with self.assertRaises(ApiError):self.events.observe({**self.body,'sampleAtMs':10000})
        with patch.dict(os.environ,{'MERGE_RECOGNITION_ENABLED':'false'}):
            with self.assertRaises(ApiError):self.events.observe(self.body)

if __name__=='__main__':unittest.main()
