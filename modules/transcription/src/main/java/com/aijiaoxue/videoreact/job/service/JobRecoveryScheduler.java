package com.aijiaoxue.videoreact.job.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 未完成任务恢复器。
 *
 * <p><b>为什么必须有它</b>：契约 §4 要求「主项目连接错误、轮询超时记录在自身 integration job，
 * 不伪造模块结果」，反过来模块也必须保证"受理过的任务最终一定会到达终态"。
 * 但提交线程只负责把任务交给工作队列——如果进程在处理中途重启，
 * 数据库里就会留下永远不会被推进的 QUEUED/RUNNING 任务，
 * 主项目会一直轮询到超时，且拿不到任何失败原因。
 *
 * <p>审计时发现 {@code JobService.resumeNonTerminal()} <b>没有任何调用点</b>，
 * 也就是说"重启可恢复"只是声明、并未生效。本类补上这个调用点：
 * <ul>
 *   <li>应用启动就绪后立即扫一次（覆盖"重启前残留"这一最常见场景）；</li>
 *   <li>之后按固定间隔继续扫（覆盖"worker 线程意外死掉"这类残留）。</li>
 * </ul>
 *
 * <p>幂等性由 {@code JobRunner} 保证：它只处理非终态任务，且终态一旦写入就不再改写，
 * 因此重复入队不会产生重复结果。
 */
@Component
public class JobRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobRecoveryScheduler.class);

    private final JobService jobService;
    private final int batchSize;

    public JobRecoveryScheduler(
            JobService jobService,
            com.aijiaoxue.videoreact.asr.config.AsrProperties asrProperties) {
        this.jobService = jobService;
        this.batchSize = Math.max(1, asrProperties.poll().batchSize());
    }

    /**
     * 启动就绪后的第一次恢复。
     *
     * <p>用 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
     * 必须等容器完全就绪（数据源、Flyway 都完成）之后再动数据库。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recover("启动");
    }

    /**
     * 周期性恢复。
     *
     * <p>间隔取识别轮询间隔的 4 倍：太频繁会与正常处理争抢同一个 worker 线程，
     * 太稀疏则残留任务要等很久才被发现。
     */
    @Scheduled(
            fixedDelayString = "${videoreact.job.recovery-interval:60s}",
            initialDelayString = "${videoreact.job.recovery-initial-delay:60s}")
    public void recoverPeriodically() {
        recover("周期");
    }

    private void recover(String trigger) {
        try {
            int resumed = jobService.resumeNonTerminal(batchSize);
            if (resumed > 0) {
                log.warn("{}恢复：发现 {} 个未完成任务，已重新入队", trigger, resumed);
            }
        } catch (RuntimeException e) {
            // 调度线程抛异常会让后续调度被吞掉，这里必须兜住
            log.error("{}恢复未完成任务失败", trigger, e);
        }
    }
}
