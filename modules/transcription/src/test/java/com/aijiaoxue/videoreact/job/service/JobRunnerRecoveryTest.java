package com.aijiaoxue.videoreact.job.service;

import com.aijiaoxue.videoreact.asr.client.AsrProviderClient;
import com.aijiaoxue.videoreact.asr.client.AsrProviderException;
import com.aijiaoxue.videoreact.asr.client.AsrProviderStatus;
import com.aijiaoxue.videoreact.asr.client.ProviderTaskStatus;
import com.aijiaoxue.videoreact.asr.config.AsrProperties;
import com.aijiaoxue.videoreact.asr.staging.AudioStaging;
import com.aijiaoxue.videoreact.job.repository.TranscriptionJobRepository;
import com.aijiaoxue.videoreact.media.FfmpegMediaTool;
import com.aijiaoxue.videoreact.media.MediaDownloader;
import com.aijiaoxue.videoreact.media.config.MediaProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class JobRunnerRecoveryTest {
    @Test
    void retryableDescribeFailureDoesNotBecomeTerminal() throws Exception {
        AsrProviderClient provider = mock(AsrProviderClient.class);
        when(provider.describe("provider-1"))
                .thenThrow(new AsrProviderException("TEST", "timeout", "temporary", true))
                .thenReturn(new AsrProviderStatus("provider-1", ProviderTaskStatus.SUCCESS,
                        "SUCCESS", "", List.of(), 1.0, null));
        JobRunner runner = runner(mock(TranscriptionJobRepository.class), provider, mock(AudioStaging.class));

        Method poll = JobRunner.class.getDeclaredMethod("pollUntilTerminal", String.class, Instant.class);
        poll.setAccessible(true);
        AsrProviderStatus result = (AsrProviderStatus) poll.invoke(runner, "provider-1", Instant.now().plusSeconds(3));

        assertEquals(ProviderTaskStatus.SUCCESS, result.status());
        verify(provider, times(2)).describe("provider-1");
    }

    @Test
    void recoveredJobDeletesPersistedStagingKeyAndClearsItOnlyAfterSuccess() throws Exception {
        TranscriptionJobRepository repository = mock(TranscriptionJobRepository.class);
        AudioStaging staging = mock(AudioStaging.class);
        when(repository.stagingObjectKey("job-1")).thenReturn(Optional.of("classroom/staging/a.wav"));
        when(staging.delete("classroom/staging/a.wav")).thenReturn(true);
        JobRunner runner = runner(repository, mock(AsrProviderClient.class), staging);

        Method cleanup = JobRunner.class.getDeclaredMethod(
                "cleanupStagedAudio", String.class, AudioStaging.StagedAudio.class);
        cleanup.setAccessible(true);
        cleanup.invoke(runner, "job-1", null);

        verify(staging).delete("classroom/staging/a.wav");
        verify(repository).clearStagingObjectKey("job-1", "classroom/staging/a.wav");
    }

    private static JobRunner runner(TranscriptionJobRepository repository,
                                    AsrProviderClient provider, AudioStaging staging) {
        AsrProperties properties = new AsrProperties(null, null,
                new AsrProperties.Poll(true, Duration.ofMillis(1), Duration.ZERO, 10, Duration.ofHours(2)),
                null, null, null);
        return new JobRunner(repository, mock(MediaDownloader.class), mock(FfmpegMediaTool.class),
                provider, mock(JobEnvelopeAssembler.class), mock(TranscriptTimeMapper.class),
                mock(MediaProperties.class), properties, mock(ResultSizeGuard.class), staging);
    }
}
