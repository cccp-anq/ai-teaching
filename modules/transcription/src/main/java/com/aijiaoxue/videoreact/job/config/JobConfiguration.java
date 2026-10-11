package com.aijiaoxue.videoreact.job.config;

import com.aijiaoxue.videoreact.job.service.JobEnvelopeAssembler;
import com.aijiaoxue.videoreact.job.service.JobRequestValidator;
import com.aijiaoxue.videoreact.job.service.RequestFingerprint;
import com.aijiaoxue.videoreact.job.service.TranscriptTimeMapper;
import com.aijiaoxue.videoreact.media.FfmpegMediaTool;
import com.aijiaoxue.videoreact.media.MediaDownloader;
import com.aijiaoxue.videoreact.media.config.MediaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 转写模块装配。
 *
 * <p>与 {@code AsrConfiguration} 的分工：那边是"ASR 提供方适配"（腾讯云 / TokenHub），
 * 这边是"契约层"（媒体、校验、指纹、信封、时间映射）。
 * 两者通过 {@code AsrProviderClient} 接口衔接，因此换识别引擎不影响契约行为。
 */
@Configuration
public class JobConfiguration {

    /**
     * 请求校验器：契约 §3 的字段约束。
     *
     * @return 校验器
     */
    @Bean
    public JobRequestValidator jobRequestValidator(com.aijiaoxue.videoreact.asr.client.AsrProviderClient provider) {
        return new JobRequestValidator(provider.supportsSpeakerLabels());
    }

    /**
     * 幂等指纹：不含可续期的下载凭据。
     *
     * @param objectMapper Jackson 3
     * @return 指纹计算器
     */
    @Bean
    public RequestFingerprint requestFingerprint(ObjectMapper objectMapper) {
        return new RequestFingerprint(objectMapper);
    }

    /**
     * 信封装配器：终态载荷原样存取，保证终态不可变。
     *
     * @param objectMapper Jackson 3
     * @return 装配器
     */
    @Bean
    public JobEnvelopeAssembler jobEnvelopeAssembler(ObjectMapper objectMapper) {
        return new JobEnvelopeAssembler(objectMapper);
    }

    /**
     * 时间映射器：把音频轴片段映射回原媒体轴并裁剪到范围内。
     *
     * @return 映射器
     */
    @Bean
    public TranscriptTimeMapper transcriptTimeMapper() {
        return new TranscriptTimeMapper();
    }

    /**
     * 受控媒体下载器：拒绝内网/非 HTTPS/超限地址。
     *
     * @param mediaProperties 媒体配置
     * @return 下载器
     */
    @Bean
    public MediaDownloader mediaDownloader(MediaProperties mediaProperties) {
        return new MediaDownloader(mediaProperties);
    }

    /**
     * ffmpeg/ffprobe 封装：探测与音轨提取。
     *
     * @param mediaProperties 媒体配置
     * @param objectMapper    Jackson 3（解析 ffprobe 的 JSON 输出）
     * @return 工具
     */
    @Bean
    public FfmpegMediaTool ffmpegMediaTool(MediaProperties mediaProperties, ObjectMapper objectMapper) {
        return new FfmpegMediaTool(mediaProperties, objectMapper);
    }
}
