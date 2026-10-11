package com.aijiaoxue.videoreact.job.support;

import com.aijiaoxue.videoreact.job.domain.TranscriptionJob;
import com.aijiaoxue.videoreact.job.repository.TranscriptionJobRepository;
import com.aijiaoxue.videoreact.job.wire.JobStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存版任务仓储，用于契约行为测试。
 *
 * <p>刻意<b>复刻数据库的唯一约束</b>（{@code job_id} 与 {@code client_request_id} 唯一）：
 * 如果内存实现不拒绝重复键，幂等测试就会"通过"却掩盖真实数据库会报错的场景。
 */
public class InMemoryTranscriptionJobRepository implements TranscriptionJobRepository {

    private final Map<Long, TranscriptionJob> store = new LinkedHashMap<>();

    private long sequence = 0;

    /** 插入次数，用于验证"幂等复用"确实没有重复落库。 */
    public int insertCount = 0;

    /** 更新次数，用于验证凭据续期等写操作。 */
    public int updateCount = 0;

    @Override
    public TranscriptionJob insert(TranscriptionJob job) {
        for (TranscriptionJob existing : store.values()) {
            if (existing.jobId().equals(job.jobId())) {
                throw new IllegalStateException("违反唯一约束: job_id 重复 " + job.jobId());
            }
            if (existing.clientRequestId().equals(job.clientRequestId())) {
                throw new IllegalStateException(
                        "违反唯一约束: client_request_id 重复 " + job.clientRequestId());
            }
        }
        insertCount++;
        long id = ++sequence;
        TranscriptionJob saved = copy(job, id);
        store.put(id, saved);
        return saved;
    }

    @Override
    public int update(TranscriptionJob job) {
        if (!store.containsKey(job.id())) {
            return 0;
        }
        updateCount++;
        store.put(job.id(), copy(job, job.id()));
        return 1;
    }

    @Override
    public Optional<TranscriptionJob> findByJobId(String jobId) {
        return store.values().stream().filter(j -> j.jobId().equals(jobId)).findFirst();
    }

    @Override
    public Optional<TranscriptionJob> findByClientRequestId(String clientRequestId) {
        return store.values().stream()
                .filter(j -> j.clientRequestId().equals(clientRequestId))
                .findFirst();
    }

    @Override
    public List<TranscriptionJob> findNonTerminal(int limit) {
        List<TranscriptionJob> result = new ArrayList<>();
        for (TranscriptionJob job : store.values()) {
            if (!job.isTerminal()) {
                result.add(job);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public int countByClassSessionId(String classSessionId) {
        return (int) store.values().stream()
                .filter(j -> j.classSessionId().equals(classSessionId))
                .count();
    }

    /** 直接放入一个任务，用于构造"已终态"等场景。 */
    public TranscriptionJob seed(TranscriptionJob job) {
        long id = job.id() == null ? ++sequence : job.id();
        TranscriptionJob saved = copy(job, id);
        store.put(id, saved);
        return saved;
    }

    /** 全部任务（按 id）。 */
    public List<TranscriptionJob> all() {
        return store.values().stream()
                .sorted(Comparator.comparing(TranscriptionJob::id))
                .toList();
    }

    public int size() {
        return store.size();
    }

    private static TranscriptionJob copy(TranscriptionJob job, long id) {
        return new TranscriptionJob(
                id, job.jobId(), job.clientRequestId(), job.requestHash(), job.schemaVersion(),
                job.providerId(), job.moduleVersion(), job.classSessionId(), job.mediaId(),
                job.mediaSha256(), job.durationMs(), job.mimeType(), job.sourceUrl(),
                job.urlExpiresAt(), job.analysisStartMs(), job.analysisEndMs(), job.language(),
                job.status(), job.revision(), job.payloadJson(), job.errorCode(),
                job.errorMessage(), job.errorRetryable(), job.providerTaskId(),
                job.engineVersion(), job.attemptCount(), job.createdAt(), job.updatedAt());
    }

    /** 便于断言：非终态任务数。 */
    public long nonTerminalCount() {
        return store.values().stream().filter(j -> !j.isTerminal()).count();
    }

    /** 便于断言：某个状态的任务数。 */
    public long countByStatus(JobStatus status) {
        return store.values().stream().filter(j -> j.status() == status).count();
    }
}
