package com.aijiaoxue.classroom;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class ClassroomModuleWorker {
    private static final Logger LOG=LoggerFactory.getLogger(ClassroomModuleWorker.class);
    private final ClassroomAnalysisProperties properties;
    private final ClassroomAnalysisRepository repository;
    private final ClassroomModuleHttpClient client;
    private final ClassroomModuleResultService results;
    private final String owner="classroom-worker-"+UUID.randomUUID();

    ClassroomModuleWorker(ClassroomAnalysisProperties properties,ClassroomAnalysisRepository repository,
                          ClassroomModuleHttpClient client,ClassroomModuleResultService results) {
        this.properties=properties; this.repository=repository; this.client=client; this.results=results;
    }

    @Scheduled(fixedDelayString="${app.classroom-analysis.worker-delay-ms:2000}",
            initialDelayString="${app.classroom-analysis.worker-initial-delay-ms:60000}")
    void scheduledRun() { runOnce(); }

    void runOnce() {
        if(!properties.integrationReady()||properties.isMockModulesEnabled()) return;
        for(long jobId:repository.waitingExternalJobs(properties.getBatchSize())) {
            var waiting=repository.moduleJob(jobId);
            if(waiting!=null&&"WAITING_EXTERNAL".equals(waiting.status()))
                repository.promoteWaitingExternal(jobId,properties.module(waiting.moduleType()).getProviderId(),
                        LocalDateTime.now().plusSeconds(properties.getJobDeadlineSeconds()));
        }
        for(var job:repository.dueJobs(properties.getBatchSize())) {
            LocalDateTime now=LocalDateTime.now();
            if(!repository.claim(job.id(),owner,now.plusSeconds(properties.getLeaseSeconds()),
                    now.plusSeconds(properties.getJobDeadlineSeconds()))) continue;
            // Claim starts the processing clock for a never-submitted queued job.
            // Reload it so the old time spent waiting through a shutdown cannot fail preflight.
            process(repository.moduleJob(job.id()),now);
        }
    }

    private void process(ClassroomAnalysisRepository.ModuleJobRow job,LocalDateTime now) {
        if(job.deadlineAt()!=null&&!now.isBefore(job.deadlineAt())) {
            results.failIntegration(job,"MODULE_DEADLINE_EXCEEDED","模块任务超过处理期限");
            return;
        }
        try {
            var envelope=job.providerJobId()==null?client.submit(job):client.poll(job);
            results.accept(job,envelope,now.plus(Duration.ofMillis(properties.getPollIntervalMs())));
        } catch(ClassroomModuleContract.ContractViolation failure) {
            results.failIntegration(job,"MODULE_CONTRACT_VIOLATION",safe(failure.getMessage()));
            LOG.warn("Classroom module contract rejected: localJobId={}, moduleType={}",job.id(),job.moduleType());
        } catch(ClassroomModuleHttpClient.ModuleCallException failure) {
            int failures=job.consecutiveFailures()+1;
            if(!failure.retryable()||failure.notFound()||failures>=properties.getMaxConsecutiveFailures()) {
                String code=failure.notFound()?"MODULE_JOB_NOT_FOUND":failure.errorCode()!=null
                        ?failure.errorCode():"MODULE_INTEGRATION_FAILED";
                results.failIntegration(job,code,safe(failure.getMessage()));
            } else {
                Duration delay=failure.retryAfter()==null?Duration.ofMillis(properties.getPollIntervalMs())
                        :failure.retryAfter();
                repository.deferAfterIntegrationFailure(job.id(),safe(failure.getMessage()),now.plus(delay));
            }
            LOG.warn("Classroom module call failed: localJobId={}, moduleType={}, retryable={}",
                    job.id(),job.moduleType(),failure.retryable());
        } catch(RuntimeException failure) {
            int failures=job.consecutiveFailures()+1;
            if(failures>=properties.getMaxConsecutiveFailures())
                results.failIntegration(job,"MODULE_INTEGRATION_FAILED","模块编排连续失败");
            else repository.deferAfterIntegrationFailure(job.id(),"模块编排内部失败",
                    now.plus(Duration.ofMillis(properties.getPollIntervalMs())));
            LOG.error("Classroom module orchestration failed: localJobId={}, moduleType={}",
                    job.id(),job.moduleType(),failure);
        }
    }

    private static String safe(String value) {
        if(value==null||value.isBlank()) return "模块调用失败";
        return value.length()<=500?value:value.substring(0,500);
    }
}
