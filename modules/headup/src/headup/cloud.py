"""Bounded cloud prefetch; final decisions and review remain chronological."""
from __future__ import annotations
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
import threading
import time
from . import perf
from .recovery import BudgetStop, LeaseLost
from .wire import TaskError

class CloudGate:
    """Shared HTTP admission, smooth bursts, reduce concurrency after 429."""
    def __init__(self, maximum):
        self.maximum = max(1, maximum)
        self.limit = self.maximum
        self.active = 0
        self.next_start = 0.0
        self.cooldown = 0.0
        self.stopped = threading.Event()
        self.condition = threading.Condition()

    def enter(self, check=lambda: None):
        while True:
            check()
            if self.stopped.is_set():
                raise BudgetStop('并发请求已停止，保留已返回结果供核查')
            with self.condition:
                now = time.monotonic()
                if self.limit < self.maximum and now >= self.cooldown:
                    self.limit = self.maximum
                if self.active < self.limit and now >= max(self.next_start, self.cooldown):
                    self.active += 1
                    self.next_start = now + .25
                    return
                self.condition.wait(.1)

    def leave(self):
        with self.condition:
            self.active -= 1
            self.condition.notify_all()

    def rejected(self, retry_after):
        try:
            delay = float(retry_after)
        except (ValueError, TypeError):
            delay = 5.0
        # Respect long supplier cooldowns by stopping, never truncate and retry early.
        if delay > 30:
            self.stopped.set()
            raise BudgetStop('供应方限流等待超过三十秒，已停止新增调用')
        with self.condition:
            self.limit = 1
            self.cooldown = max(self.cooldown, time.monotonic() + max(5.0, delay))
            self.condition.notify_all()

    def attach(self, analyzer, checkpoint=None):
        reserve = getattr(analyzer, 'before_request', None)
        rejected = getattr(analyzer, 'request_rejected', None)
        held = False
        def before(body):
            nonlocal held
            self.enter(checkpoint.check if checkpoint else lambda: None)
            held = True
            return reserve(body) if reserve else None
        def finish():
            nonlocal held
            if held:
                held = False
                self.leave()
        def reject(status, retry_after):
            try:
                if rejected:
                    rejected(status, retry_after)
            finally:
                if status == 429:
                    self.rejected(retry_after)
        analyzer.before_request = before
        analyzer.request_finished = finish
        analyzer.request_rejected = reject

@dataclass
class PrimaryResult:
    reading: object
    analyzer: object
    milliseconds: float

class CloudBatcher:
    """At most one bounded batch ahead. Workers never make cross-window decisions.

    Completed raw responses are journaled by each private checkpoint. No frame
    cleanup or next batch starts while an already submitted request is in flight.
    """
    def __init__(self, analyzer, concurrency, checkpoint=None):
        self.analyzer = analyzer
        self.concurrency = max(1, min(4, concurrency))
        self.checkpoint = checkpoint
        self.gate = CloudGate(self.concurrency)
        self.pool = ThreadPoolExecutor(max_workers=self.concurrency,
                                       thread_name_prefix='cloud-primary')
        self.gate.attach(analyzer, checkpoint)

    def _analyze(self, plan, path):
        inner = self.analyzer.fork()
        cp = self.checkpoint.fork(plan.window_id) if self.checkpoint else None
        wrapped = cp.journal_analyzer(inner) if cp else inner
        self.gate.attach(inner, cp)
        started = time.perf_counter()
        try:
            reading = wrapped.analyze(path)
        except (BudgetStop, LeaseLost):
            self.gate.stopped.set()
            raise
        except TaskError as exc:
            reading = exc
        return PrimaryResult(reading, inner, (time.perf_counter()-started)*1000)

    def batches(self, plans, frames, completed):
        for offset in range(completed, len(plans), self.concurrency):
            batch = plans[offset:offset+self.concurrency]
            futures = {self.pool.submit(self._analyze, plan, frames[plan.window_id]): plan
                       for plan in batch if plan.window_id in frames}
            results = {}
            try:
                for future in as_completed(futures):
                    results[futures[future].window_id] = future.result()
            except BaseException:
                self.gate.stopped.set()
                for future in futures:
                    future.cancel()
                raise
            for index, plan in enumerate(batch, offset):
                yield index, plan, results.get(plan.window_id)

    def close(self):
        self.gate.stopped.set()
        self.pool.shutdown(wait=True, cancel_futures=True)


def absorb_primary(target, result, metrics):
    """Only the ordered consumer mutates global state/metrics, once per window."""
    source = result.analyzer
    for key in ('request_count',):
        if hasattr(source, key):
            setattr(target, key, getattr(target, key, 0) + getattr(source, key))
    for key in ('used_models', 'request_notes'):
        if hasattr(source, key):
            getattr(target, key).extend(getattr(source, key))
    if hasattr(source, 'token_totals'):
        for key, value in source.token_totals.items():
            target.token_totals[key] = target.token_totals.get(key, 0) + value
    if getattr(source, 'last_trace', None):
        target.last_trace = dict(source.last_trace)
    metrics.record_stage(perf.STAGE_VISION_PRIMARY, result.milliseconds)
    if not isinstance(result.reading, TaskError):
        metrics.bump(perf.COUNTER_VISION_CALLS)
        metrics.bump(perf.COUNTER_VISION_IMAGES)
        metrics.count_model(getattr(result.reading, 'model_used', None))
        metrics.add_usage(getattr(result.reading, 'trace', None))
    metrics.note('visionRequests', getattr(target, 'request_count', 0))
