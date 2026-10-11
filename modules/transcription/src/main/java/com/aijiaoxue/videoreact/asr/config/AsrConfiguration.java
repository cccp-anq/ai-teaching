package com.aijiaoxue.videoreact.asr.config;

import com.aijiaoxue.videoreact.asr.client.AliyunAsrClient;
import com.aijiaoxue.videoreact.asr.client.AsrProviderClient;
import com.aijiaoxue.videoreact.asr.client.TencentAsrClient;
import com.aijiaoxue.videoreact.asr.client.WandAsrClient;
import com.aijiaoxue.videoreact.asr.service.AsrStartupCheck;
import com.aijiaoxue.videoreact.asr.staging.AudioStaging;
import com.aijiaoxue.videoreact.asr.staging.InlineAudioStaging;
import com.aijiaoxue.videoreact.asr.staging.StagingProperties;
import org.springframework.beans.factory.ObjectProvider;
import com.aijiaoxue.videoreact.asr.converter.ProviderSentenceConverter;
import com.aijiaoxue.videoreact.asr.service.HotwordListBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ASR 模块装配。
 *
 * <p>客户端与转换器刻意不用 {@code @Component} 标注，而是在这里显式 new：
 * 依赖了什么配置一目了然，也让单元测试可以直接构造而不启动 Spring 容器。
 */
@Configuration
public class AsrConfiguration {

    /**
     * 提供方客户端，按 {@code videoreact.asr.provider.type} 选择。
     *
     * <p>三个实现走的是<b>完全不同的协议</b>，所以是三个类而不是一套参数分支：
     * <ul>
     *   <li>{@code tencent} —— 腾讯云标准 ASR，SecretId/SecretKey + TC3 签名 + 十几个引擎参数；</li>
     *   <li>{@code wand} —— TokenHub {@code wand-asr-v1}，Bearer Key + 只有 model/input_url，
     *       <b>支持内联 base64 音频</b>；</li>
     *   <li>{@code aliyun-filetrans} —— 阿里云百炼非实时语音识别，Bearer Key + 业务空间专属域名 +
     *       提交/轮询 + 结果 JSON 二次下载，<b>只接受公网 URL</b>。</li>
     * </ul>
     * 切换只需改 {@code .env} 里的一行，业务层、数据库、对外接口都不用动。
     *
     * <p><b>Jackson 为什么用 {@code ObjectProvider} 延迟获取</b>：只有 wand 与 aliyun 客户端需要 JSON 解析。
     * 延迟获取能让 tencent 路径完全不依赖 Jackson，也避免"容器里没有 ObjectMapper 就整个应用起不来"。
     * 另注意必须用 <b>Jackson 3（{@code tools.jackson}）</b>——Spring Boot 4 自动配置的是它；
     * 用 Jackson 2 的 {@code com.fasterxml.jackson} 会报 NoSuchBeanDefinition。
     *
     * @param properties           模块配置
     * @param objectMapperProvider Jackson 3 的 ObjectMapper（延迟获取）
     * @return 提供方客户端
     */
    @Bean
    public AsrProviderClient asrProviderClient(
            AsrProperties properties,
            ObjectProvider<tools.jackson.databind.ObjectMapper> objectMapperProvider) {
        String type = properties.provider().type() == null
                ? "tencent"
                : properties.provider().type().trim().toLowerCase();
        return switch (type) {
            case "tencent" -> new TencentAsrClient(properties);
            case "wand" -> new WandAsrClient(properties, objectMapperProvider.getObject());
            case "aliyun-filetrans", "aliyun" ->
                    new AliyunAsrClient(properties, objectMapperProvider.getObject());
            default -> throw new IllegalStateException(
                    "不支持的 ASR 提供方: " + properties.provider().type()
                            + "（可选：tencent = 腾讯云标准 ASR，wand = TokenHub wand-asr-v1，"
                            + "aliyun-filetrans = 阿里云百炼非实时语音识别）");
        };
    }

    /**
     * 音频暂存实现。
     *
     * <p>当前固定返回 {@link InlineAudioStaging}，它对暂存请求明确失败。
     * 这是有意为之的<b>显式缺席</b>：主项目尚未确认课程媒体是"公网匿名可读"还是
     * "需要签名的对象存储"，而这决定了暂存该怎么做。在确认之前，
     * 选了 URL-only 的提供方就会得到一条可读的启动告警与一条明确的失败原因，
     * 而不会退化成"把 sourceUrl 转出去、sha256 核验形同虚设"。
     *
     * <p>接入真实实现时只改这一个方法（换成基于对象存储的 {@code AudioStaging}），
     * {@code JobRunner} 与提供方客户端都不需要改动。
     *
     * @return 暂存实现
     */
    @Bean
    public AudioStaging audioStaging(StagingProperties properties) {
        return properties.isOss() ? new com.aijiaoxue.videoreact.asr.staging.OssAudioStaging(properties) : new InlineAudioStaging();
    }

    /**
     * 启动自检：把"配置缺口"在启动日志里说清楚，而不是等第一个任务失败。
     *
     * @param providerClient    提供方客户端
     * @param audioStaging      暂存实现
     * @param properties        模块配置
     * @param stagingProperties 暂存配置（用于识别"选了 OSS 但实现未完成"这种状态）
     * @return 自检组件
     */
    @Bean
    public AsrStartupCheck asrStartupCheck(
            AsrProviderClient providerClient,
            AudioStaging audioStaging,
            AsrProperties properties,
            StagingProperties stagingProperties) {
        return new AsrStartupCheck(providerClient, audioStaging, properties, stagingProperties);
    }

    /**
     * 提供方分段 → 领域分段的转换器。
     *
     * @return 转换器
     */
    @Bean
    public ProviderSentenceConverter providerSentenceConverter() {
        return new ProviderSentenceConverter();
    }

    /**
     * 课程热词构建器。
     *
     * @param properties 热词配置
     * @return 构建器
     */
    @Bean
    public HotwordListBuilder hotwordListBuilder(CourseHotwordProperties properties) {
        return new HotwordListBuilder(properties);
    }

}
