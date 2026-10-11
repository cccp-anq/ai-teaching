package com.aijiaoxue.videoreact.job.repository;

import com.aijiaoxue.videoreact.job.domain.TranscriptionJob;

import java.util.List;
import java.util.Optional;

/**
 * 转写任务仓储。
 *
 * <p>刻意不引入 ORM：字段与 SQL 的对应关系是契约落地的一部分，
 * 显式写出来便于与契约字段逐一核对。
 */
public interface TranscriptionJobRepository {
    default void setSpeakerLabels(String jobId,boolean enabled) {}
    default boolean speakerLabels(String jobId){return false;}
    default Optional<String> stagingObjectKey(String jobId) { return Optional.empty(); }
    default void setStagingObjectKey(String jobId, String objectKey) { }
    default void clearStagingObjectKey(String jobId, String objectKey) { }

    /**
     * 插入任务。
     *
     * @param job 待插入任务（id 可为 null）
     * @return 带自增主键的任务
     */
    TranscriptionJob insert(TranscriptionJob job);

    /**
     * 更新任务的运行/终态字段。
     *
     * <p>不更新身份字段与 request_hash：那些在插入后永不改变。
     *
     * @param job 含 id 的任务
     * @return 受影响行数
     */
    int update(TranscriptionJob job);

    Optional<TranscriptionJob> findByJobId(String jobId);

    Optional<TranscriptionJob> findByClientRequestId(String clientRequestId);

    /**
     * 取非终态任务，供后台推进。
     *
     * @param limit 单批上限
     * @return 待推进任务
     */
    List<TranscriptionJob> findNonTerminal(int limit);

    /**
     * 统计某个课堂会话下已存在的任务数（排错与容量用）。
     *
     * @param classSessionId 课堂会话 ID
     * @return 任务数
     */
    int countByClassSessionId(String classSessionId);
}
