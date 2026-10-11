package com.aijiaoxue.videoreact.job.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 契约身份与鉴权配置，前缀 {@code videoreact.contract}。
 *
 * <p>这些值会出现在每个 JobEnvelope 里，也是<b>部署交接表</b>的必填项
 * （契约 §7：baseUrl、providerId/moduleVersion、支持编码/语言、最大媒体/结果…）。
 * 放进配置而不是写死在代码里，是因为它们随部署环境变化，且主项目需要按值登记。
 *
 * @param providerId     本部署的供应者标识，必须与凭证绑定
 * @param moduleVersion  实现版本，标识实际算法/配置组合
 * @param bearerTokens   允许的机器凭证列表（契约要求每部署独立、可轮换）
 * @param maxBodyBytes   请求体上限，超过返回 413
 * @param maxSegments    单个结果允许的最大片段数，超过返回 422
 * @param maxPollSeconds 建议主项目使用的最大轮询时长（供 /health 与交接表）
 */
@ConfigurationProperties(prefix = "videoreact.contract")
public record ContractProperties(
        String providerId,
        String moduleVersion,
        List<String> bearerTokens,
        long maxBodyBytes,
        int maxSegments,
        int maxPollSeconds) {
}
