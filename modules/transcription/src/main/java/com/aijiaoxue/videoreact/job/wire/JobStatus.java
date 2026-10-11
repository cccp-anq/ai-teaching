package com.aijiaoxue.videoreact.job.wire;

/**
 * 契约里的任务状态。
 *
 * <p>契约 §4：{@code QUEUED / RUNNING / SUCCEEDED / FAILED}，
 * 「远端任务状态，不等于主项目分析报告状态」。
 *
 * <p>刻意与 ASR 提供方的状态解耦：提供方用的是 {@code queued/in_progress/completed/failed}，
 * 这里只暴露契约的四个值。
 */
public enum JobStatus {

    /** 已受理，尚未开始处理。 */
    QUEUED,

    /** 处理中。 */
    RUNNING,

    /** 成功终态，必须有 payload。 */
    SUCCEEDED,

    /** 失败终态，必须有 error。 */
    FAILED;

    /** 是否终态（终态不可变，见契约 §3/§4）。 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }

    /**
     * 契约要求的 revision：非终态为 0，终态为 1。
     *
     * @return 0 或 1
     */
    public int revision() {
        return isTerminal() ? 1 : 0;
    }
}
