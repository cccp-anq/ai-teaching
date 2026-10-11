package com.aijiaoxue.videoreact.asr.staging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link InlineAudioStaging} 的行为。
 *
 * <p>只有一个要点，但它值得单独一个测试类守着：<b>它必须响亮地失败</b>。
 * 如果哪天有人为了"先能跑起来"把它改成回退到 {@code sourceUrl}，
 * 契约的 sha256 核验就会静默失效——不报错、只失真，
 * 而且失真发生在"我们下载到的字节"与"引擎读到的字节"之间，
 * 从外部完全看不出来。这个测试就是那道闸。
 */
class InlineAudioStagingTest {

    private final InlineAudioStaging staging = new InlineAudioStaging();

    @Test
    @DisplayName("任何暂存请求都必须失败，且用户能照着提示解决问题")
    void stageFailsLoudly() {
        assertThatThrownBy(() -> staging.stage(
                Path.of("audio.wav"), "audio/wav", "audio.wav", Duration.ofHours(1)))
                .isInstanceOf(AudioStagingException.class)
                .hasMessageContaining("对象存储")
                .hasMessageContaining("ASR_PROVIDER=wand")
                .satisfies(e -> {
                    AudioStagingException ex = (AudioStagingException) e;
                    assertThat(ex.code()).isEqualTo("AudioStagingNotConfigured");
                    // 重试一次不会变好：这是配置缺失，不是瞬时故障
                    assertThat(ex.retryable()).isFalse();
                });
    }

    @Test
    @DisplayName("自检接口如实回答\"不能产出 URL\"")
    void reportsItselfUnavailable() {
        assertThat(staging.urlStagingAvailable()).isFalse();
        assertThat(staging.description()).contains("inline");
    }
}
