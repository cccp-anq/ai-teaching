package com.aijiaoxue.videoreact.job.service;

import com.aijiaoxue.videoreact.job.web.ContractErrors;
import com.aijiaoxue.videoreact.job.web.ContractRejectionException;
import com.aijiaoxue.videoreact.job.web.RejectionBody;
import com.aijiaoxue.videoreact.job.wire.JobRequest;
import com.aijiaoxue.videoreact.job.wire.ModuleType;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * JobRequest 校验器，实现契约 §3 的全部字段约束。
 *
 * <p><b>为什么把校验独立成类</b>：契约对这些字段的约束是<b>协议的一部分</b>
 * （主项目会按同样的规则造请求），分散在 service 里迟早会与契约漂移。
 * 这里集中实现，并由契约测试逐条覆盖。
 *
 * <p>校验顺序刻意固定：先版本、再必填、最后取值。
 * 这样当请求有多个问题时，报错信息稳定可复现，而不是随字段遍历顺序变化。
 */
public class JobRequestValidator {
    private final boolean speakerLabelsSupported;
    public JobRequestValidator(){this(false);}
    public JobRequestValidator(boolean supported){this.speakerLabelsSupported=supported;}
    public boolean supportsSpeakerLabels(){return speakerLabelsSupported;}

    /** 契约固定 wire 版本。 */
    public static final String SUPPORTED_SCHEMA_VERSION = "1.0";

    /** 契约要求的 ID 长度上限。 */
    private static final int MAX_ID_LENGTH = 128;


    /**
     * 逐项校验并返回归一化后的请求。
     *
     * @param request 反序列化后的请求（可能含 null 字段）
     * @param maxDurationMs 本部署允许的最大媒体时长
     * @return 原请求（校验通过）
     * @throws ContractRejectionException 校验失败，携带字段级 details
     */
    public JobRequest validate(JobRequest request, long maxDurationMs) {
        List<RejectionBody.Detail> errors = new ArrayList<>();

        if (request == null) {
            throw ContractRejectionException.validation("请求体为空", List.of());
        }

        // ---- schemaVersion：不支持的大版本用 UNSUPPORTED_SCHEMA（区别于字段校验失败） ----
        if (isBlank(request.schemaVersion())) {
            throw ContractRejectionException.validation("schemaVersion 缺失",
                    List.of(ContractRejectionException.detail("schemaVersion", "必填")));
        }
        if (!SUPPORTED_SCHEMA_VERSION.equals(request.schemaVersion())) {
            throw ContractRejectionException.unsupportedSchema(
                    "不支持的 schemaVersion: " + request.schemaVersion()
                            + "（本部署支持 " + SUPPORTED_SCHEMA_VERSION + "）");
        }

        // ---- 必填标识 ----
        requireId(errors, "clientRequestId", request.clientRequestId());
        requireId(errors, "classSessionId", request.classSessionId());
        requireId(errors, "mediaId", request.mediaId());

        // ---- moduleType 必须与凭证/服务匹配 ----
        ModuleType moduleType = ModuleType.parse(request.moduleType());
        if (moduleType == null) {
            errors.add(ContractRejectionException.detail("moduleType",
                    "必须为 TRANSCRIPTION 或 HEAD_UP"));
        } else if (moduleType != ModuleType.TRANSCRIPTION) {
            // 本部署只提供转写；用 403 而不是 422，因为这是"服务不匹配凭证"
            throw ContractRejectionException.forbidden(
                    "本部署为转写模块，不接受 moduleType=" + moduleType);
        }

        // ---- media ----
        JobRequest.MediaRef media = request.media();
        if (media == null) {
            errors.add(ContractRejectionException.detail("media", "必填"));
        } else {
            validateMedia(errors, media, maxDurationMs);
        }

        // ---- analysisRange ----
        Long durationMs = media == null ? null : media.durationMs();
        validateRange(errors, request.analysisRange(), durationMs);

        // ---- parameters ----
        validateParameters(errors, request.parameters());

        if (!errors.isEmpty()) {
            throw ContractRejectionException.validation(
                    "请求校验未通过，共 " + errors.size() + " 处问题", errors);
        }
        return request;
    }

    private void requireId(List<RejectionBody.Detail> errors, String path, String value) {
        if (isBlank(value)) {
            errors.add(ContractRejectionException.detail(path, "必填且不能为空"));
        } else if (value.length() > MAX_ID_LENGTH) {
            errors.add(ContractRejectionException.detail(path,
                    "长度不能超过 " + MAX_ID_LENGTH + " 字符"));
        }
    }

    private void validateMedia(List<RejectionBody.Detail> errors, JobRequest.MediaRef media, long maxDurationMs) {
        // sha256 必须是 64 位小写十六进制（契约与校验脚本一致）
        String hash = media.sha256();
        if (isBlank(hash)) {
            errors.add(ContractRejectionException.detail("media.sha256", "必填"));
        } else if (!hash.matches("[0-9a-f]{64}")) {
            errors.add(ContractRejectionException.detail("media.sha256",
                    "必须是 64 位小写十六进制 SHA-256"));
        }

        Long durationMs = media.durationMs();
        if (durationMs == null) {
            errors.add(ContractRejectionException.detail("media.durationMs", "必填"));
        } else if (durationMs <= 0) {
            errors.add(ContractRejectionException.detail("media.durationMs", "必须为正整数毫秒"));
        } else if (durationMs > maxDurationMs) {
            // 超过本部署能力：属于取值非法，不是服务临时故障
            errors.add(ContractRejectionException.detail("media.durationMs",
                    "超过本部署上限 " + maxDurationMs + "ms"));
        }

        if (isBlank(media.mimeType())) {
            errors.add(ContractRejectionException.detail("media.mimeType", "必填"));
        }

        if (isBlank(media.sourceUrl())) {
            errors.add(ContractRejectionException.detail("media.sourceUrl", "必填"));
        } else {
            try {
                URI uri = URI.create(media.sourceUrl());
                if (uri.getHost() == null) {
                    errors.add(ContractRejectionException.detail("media.sourceUrl", "缺少主机名"));
                }
            } catch (RuntimeException e) {
                errors.add(ContractRejectionException.detail("media.sourceUrl", "不是合法 URL"));
            }
        }

        if (isBlank(media.urlExpiresAt())) {
            errors.add(ContractRejectionException.detail("media.urlExpiresAt", "必填"));
        } else {
            if (!media.urlExpiresAt().endsWith("Z")) {
                errors.add(ContractRejectionException.detail("media.urlExpiresAt",
                        "必须是 UTC 带 Z 的时间串"));
            } else {
                try {
                    Instant.parse(media.urlExpiresAt());
                } catch (DateTimeParseException e) {
                    errors.add(ContractRejectionException.detail("media.urlExpiresAt",
                            "不是合法的 ISO-8601 UTC 时间"));
                }
            }
        }
    }

    private void validateRange(List<RejectionBody.Detail> errors, JobRequest.AnalysisRange range, Long durationMs) {
        if (range == null) {
            errors.add(ContractRejectionException.detail("analysisRange", "必填"));
            return;
        }
        Long start = range.startMs();
        Long end = range.endMs();
        if (start == null || end == null) {
            errors.add(ContractRejectionException.detail("analysisRange", "startMs 与 endMs 都必填"));
            return;
        }
        if (start < 0) {
            errors.add(ContractRejectionException.detail("analysisRange.startMs", "不能为负"));
        }
        if (end <= start) {
            errors.add(ContractRejectionException.detail("analysisRange",
                    "必须满足 startMs < endMs（左闭右开）"));
        }
        if (durationMs != null && durationMs > 0 && end > durationMs) {
            errors.add(ContractRejectionException.detail("analysisRange.endMs",
                    "不能超过 media.durationMs"));
        }
    }

    private void validateParameters(List<RejectionBody.Detail> errors, JobRequest.TranscriptionParameters parameters) {
        if (parameters == null) {
            errors.add(ContractRejectionException.detail("parameters", "必填"));
            return;
        }
        String language = parameters.language();
        if (isBlank(language)) {
            errors.add(ContractRejectionException.detail("parameters.language", "必填"));
        } else if (!com.aijiaoxue.videoreact.job.wire.SupportedLanguages.accepts(language)) {
            errors.add(ContractRejectionException.detail("parameters.language",
                    "本部署不支持的语言标签: " + language));
        }

        Boolean speakerLabels = parameters.enableSpeakerLabels();
        if (speakerLabels == null) {
            errors.add(ContractRejectionException.detail("parameters.enableSpeakerLabels", "必填"));
        } else if (speakerLabels && !speakerLabelsSupported) {
            // 契约 §2：true 但不支持时必须 422，不得静默冒充
            errors.add(ContractRejectionException.detail("parameters.enableSpeakerLabels",
                    "首版不支持说话人标签，请传 false（不能静默忽略）"));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 判断一个校验失败是否属于"字段缺失/非法"而非"服务不支持"。
     *
     * @param code 错误码
     * @return true 表示 422 VALIDATION_FAILED
     */
    public static boolean isValidationFailure(String code) {
        return ContractErrors.VALIDATION_FAILED.equals(code);
    }
}
