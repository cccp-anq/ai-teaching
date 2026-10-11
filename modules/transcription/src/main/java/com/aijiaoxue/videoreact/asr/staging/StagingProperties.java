package com.aijiaoxue.videoreact.asr.staging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 音频暂存配置，前缀 {@code videoreact.staging}。
 *
 * <p><b>为什么单独一个前缀而不是塞进 {@code videoreact.asr.provider}</b>：
 * 暂存这件事与"用哪家识别引擎"无关——它的存在只因为阿里云那条路要求公网 URL。
 * 塞进 provider 会让 {@code tencent} / {@code wand} 两种配置对象里也带着一坨
 * OSS 字段，那些字段在那里永远是 null。独立前缀后，切换提供方不牵动暂存配置。
 *
 * <p><b>当前状态：只有 {@code inline} 一种实现</b>。
 * {@code oss} 的字段先占位，上传逻辑尚未实现（见 {@code docs/OSS暂存接入方案.md}）。
 * 之所以不把 oss 配成"看起来能用"：{@link #isOssConfigured()} 会如实回答，
 * 启动自检据此给出可操作提示，而不是让任务在提交阶段才失败。
 *
 * @param type                   暂存实现：{@code inline}（默认，明确失败）或 {@code oss}
 * @param accessKeyId            RAM 子账号 AccessKeyId；<b>不要用主账号</b>
 * @param accessKeySecret        RAM 子账号 AccessKeySecret
 * @param bucket                 存储桶名，只填桶名本身（不带 {@code .oss-cn-beijing.aliyuncs.com}）
 * @param region                 地域，如 {@code cn-beijing}；<b>必须与识别引擎同地域</b>
 * @param prefix                 对象前缀，如 {@code videoreact/staging/}；末尾斜杠自动补
 * @param publicEndpoint         写进预签名 URL、由识别引擎访问的端点；必须是 HTTPS 公网地址
 * @param internalEndpoint       模块部署在 ECS 上时的内网端点，用于上传省流量；留空用公网端点
 * @param urlExpirySeconds       预签名 URL 有效期（秒），须覆盖"排队 + 识别 + 轮询"全过程
 * @param multipartThresholdBytes 超过该字节数改用分片上传
 * @param multipartPartSizeBytes 分片大小（OSS 限制 5MB–5GB）
 * @param timeoutSeconds         单次上传/删除操作超时（秒）
 * @param maxRetries             OSS 侧 5xx 与网络抖动时的重试次数
 */
@ConfigurationProperties(prefix = "videoreact.staging")
public record StagingProperties(
        String type,
        String accessKeyId,
        String accessKeySecret,
        String bucket,
        String region,
        String prefix,
        String publicEndpoint,
        String internalEndpoint,
        long urlExpirySeconds,
        long multipartThresholdBytes,
        long multipartPartSizeBytes,
        int timeoutSeconds,
        int maxRetries) {

    /** 暂存实现：只明确失败（当前默认）。 */
    public static final String TYPE_INLINE = "inline";

    /** 暂存实现：阿里云 OSS（<b>字段已占位，实现未完成</b>）。 */
    public static final String TYPE_OSS = "oss";

    /** 预签名 URL 默认有效期：轮询上限 2 小时 × 1.5。 */
    public static final long DEFAULT_URL_EXPIRY_SECONDS = 10800L;

    /**
     * 归一化：把环境变量绑定出来的空串与格式问题收敛掉。
     *
     * <p>和 {@code AsrProperties.Aliyun} 里同样的道理：
     * 从环境变量绑定字符串很容易得到 {@code ""} 或带多余空白的值，
     * 而 {@code "  "} 这样的 bucket 名会在运行期变成一个很难查的 404。
     * 与其等到上传时报错，不如在绑定阶段就把它们变成 null。
     */
    public StagingProperties {
        type = normalize(type, TYPE_INLINE);
        accessKeyId = blankToNull(accessKeyId);
        accessKeySecret = blankToNull(accessKeySecret);
        bucket = blankToNull(bucket);
        region = normalize(region, "cn-beijing");
        prefix = normalizePrefix(prefix);
        publicEndpoint = stripTrailingSlash(blankToNull(publicEndpoint));
        internalEndpoint = stripTrailingSlash(blankToNull(internalEndpoint));
        if (urlExpirySeconds <= 0) {
            urlExpirySeconds = DEFAULT_URL_EXPIRY_SECONDS;
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 300;
        }
        if (maxRetries < 0) {
            maxRetries = 0;
        }
    }

    /** 是否选择了 OSS 暂存。 */
    public boolean isOss() {
        return TYPE_OSS.equals(type);
    }

    /**
     * OSS 的必填项是否齐全。
     *
     * <p>用于启动自检：选了 {@code oss} 但没填凭证时，应当<b>明确告知缺哪一项</b>，
     * 而不是等第一个任务跑到上传阶段才失败。
     *
     * @return true 表示四项凭证/定位信息都已配置
     */
    public boolean isOssConfigured() {
        return accessKeyId != null && accessKeySecret != null && bucket != null && region != null;
    }

    /**
     * 解析出写进预签名 URL 的端点。
     *
     * <p><b>为什么必须能推导而不是强制手填</b>：端点格式是固定的
     * （{@code https://oss-{region}.aliyuncs.com}），强制手填只会多一个填错的机会。
     * 但又不写死——有些部署走自定义域名或加速域名，那时必须能覆盖。
     *
     * <p>⚠️ 这个地址会被<b>识别引擎的服务器</b>访问，所以必须是公网可达的 HTTPS。
     * 不要把内网端点填到这里，否则引擎拉不到音频，而错误信息会是一句含糊的下载失败。
     *
     * @return 公网 HTTPS 端点，末尾不带斜杠
     */
    public String resolvedPublicEndpoint() {
        if (publicEndpoint != null) {
            return publicEndpoint;
        }
        return "https://oss-" + region + ".aliyuncs.com";
    }

    /**
     * 解析出用于上传/删除的端点。
     *
     * <p>模块部署在阿里云 ECS 上时填内网端点可以省流量费，
     * 且上传速度更快——但生成预签名 URL 仍要用 {@link #resolvedPublicEndpoint()}，
     * 因为那个地址是给百炼的服务器用的。
     *
     * @return 上传用端点，末尾不带斜杠
     */
    public String resolvedUploadEndpoint() {
        return internalEndpoint != null ? internalEndpoint : resolvedPublicEndpoint();
    }

    /** 对象键：{@code prefix + 传入的文件名}。 */
    public String objectKey(String fileName) {
        return prefix + fileName;
    }

    /**
     * 人类可读的配置摘要，用于启动自检。
     *
     * <p><b>只输出定位信息，绝不输出凭证</b>——连掩码形式都不输出。
     * 凭证有没有配由 {@link #isOssConfigured()} 回答，那是布尔值。
     *
     * @return 形如 {@code oss（bucket=xxx, region=cn-beijing, ...）}
     */
    public String describe() {
        if (!isOss()) {
            return "inline-only（不支持暂存为公网 URL）";
        }
        if (!isOssConfigured()) {
            return "oss（配置不完整：" + String.join("、", missingOssFields()) + " 未配置）";
        }
        return "oss（bucket=%s, region=%s, prefix=%s, 端点=%s, URL 有效期=%ds）"
                .formatted(bucket, region, prefix, resolvedPublicEndpoint(), urlExpirySeconds);
    }

    /** 列出缺失的 OSS 必填项，供启动自检提示。 */
    public java.util.List<String> missingOssFields() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (accessKeyId == null) {
            missing.add("OSS_ACCESS_KEY_ID");
        }
        if (accessKeySecret == null) {
            missing.add("OSS_ACCESS_KEY_SECRET");
        }
        if (bucket == null) {
            missing.add("OSS_BUCKET");
        }
        if (region == null) {
            missing.add("OSS_REGION");
        }
        return java.util.List.copyOf(missing);
    }

    private static String normalize(String value, String fallback) {
        String trimmed = blankToNull(value);
        return trimmed == null ? fallback : trimmed.toLowerCase();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() ? null : result;
    }

    /** 前缀统一为"要么为空，要么以 / 结尾"，避免拼出 {@code a/b} 或 {@code a//b}。 */
    private static String normalizePrefix(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return "";
        }
        String result = trimmed;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        if (result.isEmpty()) {
            return "";
        }
        return result.endsWith("/") ? result : result + "/";
    }
}
