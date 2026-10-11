package com.aijiaoxue.videoreact.job.service;

import com.aijiaoxue.videoreact.job.web.ContractErrors;
import com.aijiaoxue.videoreact.job.web.ContractRejectionException;
import com.aijiaoxue.videoreact.job.web.RejectionBody;
import com.aijiaoxue.videoreact.job.wire.JobRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JobRequest 字段校验测试，逐条覆盖契约 §3 与转写规范 §2 的约束。
 *
 * <p>这些约束是<b>协议的一部分</b>：主项目按同样的规则造请求，并按返回的
 * {@code code} 决定"改请求还是重试"。两个语义必须严格区分（契约 §5）：
 * <ul>
 *   <li>{@code 422 UNSUPPORTED_SCHEMA}：wire 版本不支持——主项目要改协议版本；</li>
 *   <li>{@code 422 VALIDATION_FAILED}：字段取值非法——主项目改请求内容；</li>
 *   <li>{@code 403 FORBIDDEN}：凭证/服务与 {@code moduleType} 不匹配——
 *       说明请求发错了模块，不是"字段写错了"，更不是可重试的临时故障；</li>
 *   <li>{@code enableSpeakerLabels=true} 必须 422：契约明确"不支持时不得静默冒充"，
 *       若返回 200 并丢掉说话人信息，主项目会以为拿到了说话人结果。</li>
 * </ul>
 *
 * <p>全部用例都是纯内存调用：不启动 Spring、不连数据库、不需要网络或 ffmpeg。
 */
class JobRequestValidatorTest {

    /** 与 application.yml 的 {@code videoreact.media.max-duration-ms} 默认值一致（2 小时）。 */
    private static final long MAX_DURATION_MS = 7_200_000L;

    private static final String SHA256 = "a".repeat(64);

    private static final String SOURCE_URL =
            "https://media.example.com/classroom/001.mp4?signature=aaa";

    private static final String URL_EXPIRES_AT = "2026-09-13T10:00:00Z";

    private final JobRequestValidator validator = new JobRequestValidator();

    /**
     * 合法请求：校验通过并原样返回，供调用方继续处理。
     */
    @Test
    void valid_request_passes_and_is_returned_unchanged() {
        JobRequest request = valid();

        assertThat(validator.validate(request, MAX_DURATION_MS)).isSameAs(request);
        assertThat(JobRequestValidator.SUPPORTED_SCHEMA_VERSION).isEqualTo("1.0");
        assertThat(JobRequestValidator.isValidationFailure(ContractErrors.VALIDATION_FAILED)).isTrue();
        assertThat(JobRequestValidator.isValidationFailure(ContractErrors.UNSUPPORTED_SCHEMA)).isFalse();
        assertThat(JobRequestValidator.isValidationFailure(ContractErrors.FORBIDDEN)).isFalse();
    }

    /**
     * schemaVersion 不是 "1.0" → {@code UNSUPPORTED_SCHEMA} + HTTP 422，
     * <b>不能</b>混成 {@code VALIDATION_FAILED}：前者要求主项目改协议版本，后者只要求改字段。
     */
    @Test
    void unsupported_schema_version_uses_unsupported_schema_code() {
        for (String bad : List.of("2.0", "1.1", "1.0.0", "v1.0")) {
            ContractRejectionException rejection = reject(withSchemaVersion(bad),
                    ContractErrors.UNSUPPORTED_SCHEMA, HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(rejection.status().value()).isEqualTo(422);
            assertThat(rejection.code()).isNotEqualTo(ContractErrors.VALIDATION_FAILED);
            assertThat(rejection.details()).isEmpty();
            assertThat(rejection.retryable()).isFalse();
        }

        // schemaVersion 缺失属于"字段没填"，而不是"版本不支持"
        assertValidationFailed(withSchemaVersion(null), "schemaVersion");
        assertValidationFailed(withSchemaVersion("   "), "schemaVersion");
    }

    /**
     * 缺失或超长（&gt;128）的 ID → {@code VALIDATION_FAILED}，且 details 能定位到字段路径。
     */
    @Test
    void missing_or_oversized_ids_are_field_level_errors() {
        assertThat(validator.validate(withIds("c".repeat(128), "cs-001", "media-001"),
                MAX_DURATION_MS)).isNotNull();
        assertThat(validator.validate(withIds("req-001", "c".repeat(128), "media-001"),
                MAX_DURATION_MS)).isNotNull();

        assertValidationFailed(withIds(null, "cs-001", "media-001"), "clientRequestId");
        assertValidationFailed(withIds("", "cs-001", "media-001"), "clientRequestId");
        assertValidationFailed(withIds("   ", "cs-001", "media-001"), "clientRequestId");
        assertValidationFailed(withIds("c".repeat(129), "cs-001", "media-001"), "clientRequestId");
        assertValidationFailed(withIds("req-001", null, "media-001"), "classSessionId");
        assertValidationFailed(withIds("req-001", "cs-001", "c".repeat(129)), "mediaId");
    }

    /**
     * moduleType=HEAD_UP → HTTP 403 {@code FORBIDDEN}（服务与凭证不匹配）；
     * 未知值 → HTTP 422 {@code VALIDATION_FAILED}。
     *
     * <p>两者不能用同一个状态码：403 表示"这个模块不提供该类型"，让主项目去改路由；
     * 422 表示"类型字符串本身写错了"。
     */
    @Test
    void head_up_module_type_is_forbidden_and_unknown_value_is_validation_failed() {
        ContractRejectionException forbidden = reject(withModuleType("HEAD_UP"),
                ContractErrors.FORBIDDEN, HttpStatus.FORBIDDEN);
        assertThat(forbidden.status().value()).isEqualTo(403);
        assertThat(forbidden.code()).isNotEqualTo(ContractErrors.VALIDATION_FAILED);
        assertThat(forbidden.details()).isEmpty();

        assertValidationFailed(withModuleType("SUMMARY"), "moduleType");
        assertValidationFailed(withModuleType("transcription"), "moduleType");
        assertValidationFailed(withModuleType(null), "moduleType");
        assertValidationFailed(withModuleType(""), "moduleType");
    }

    /**
     * 校验顺序稳定：先版本 → 再类型 → 最后取值。
     *
     * <p>顺序被测试钉住，是因为主项目要根据稳定的报错做决策：一个"版本不对 +
     * 类型不对 + 字段全错"的请求，如果先报字段错误，主项目会白改一轮字段。
     */
    @Test
    void schema_and_module_type_are_checked_before_field_values() {
        JobRequest everythingWrong = new JobRequest("2.0", "", "HEAD_UP", "", "",
                media("zz", 0L, " ", "not a url", "no-zone"),
                new JobRequest.AnalysisRange(-1L, -1L),
                new JobRequest.TranscriptionParameters("fr", Boolean.TRUE));

        ContractRejectionException schemaFirst = reject(everythingWrong,
                ContractErrors.UNSUPPORTED_SCHEMA, HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(schemaFirst.details()).isEmpty();

        JobRequest typeMismatch = new JobRequest("1.0", "", "HEAD_UP", "", "",
                media("zz", 0L, " ", "not a url", "no-zone"),
                new JobRequest.AnalysisRange(-1L, -1L),
                new JobRequest.TranscriptionParameters("fr", Boolean.TRUE));
        ContractRejectionException typeFirst = reject(typeMismatch,
                ContractErrors.FORBIDDEN, HttpStatus.FORBIDDEN);
        assertThat(typeFirst.details()).isEmpty();
    }

    /**
     * sha256 必须是 64 位小写十六进制（与 {@code verify_contract.py} 的断言一致）。
     */
    @Test
    void sha256_must_be_64_lowercase_hex() {
        assertThat(validator.validate(
                withMedia(media("0123456789abcdef".repeat(4), 60_000L, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT)),
                MAX_DURATION_MS)).isNotNull();

        assertValidationFailed(withMedia(media("A".repeat(64), 60_000L, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT)),
                "media.sha256");
        assertValidationFailed(withMedia(media("a".repeat(63), 60_000L, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT)),
                "media.sha256");
        assertValidationFailed(withMedia(media("a".repeat(65), 60_000L, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT)),
                "media.sha256");
        assertValidationFailed(withMedia(media("g".repeat(64), 60_000L, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT)),
                "media.sha256");
        assertValidationFailed(withMedia(media(null, 60_000L, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT)),
                "media.sha256");
    }

    /**
     * durationMs 必须为正整数，且不超过本部署上限（超限是"取值非法"→422，
     * 不是"临时不可用"→503，否则主项目会无限重试一个永远不会成功的请求）。
     */
    @Test
    void duration_must_be_positive_and_within_deployment_limit() {
        // 1ms 的极短媒体：范围也必须相应缩小，否则会先撞上 analysisRange.endMs > durationMs
        assertThat(validator.validate(withMediaAndRange(
                media(SHA256, 1L, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT),
                new JobRequest.AnalysisRange(0L, 1L)), MAX_DURATION_MS)).isNotNull();
        // 恰好等于部署上限是合法的（上限判定是 "> max" 而不是 ">= max"）
        assertThat(validator.validate(
                withMedia(media(SHA256, MAX_DURATION_MS, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT)),
                MAX_DURATION_MS)).isNotNull();

        assertValidationFailed(withMedia(media(SHA256, 0L, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT)),
                "media.durationMs");
        assertValidationFailed(withMedia(media(SHA256, -1L, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT)),
                "media.durationMs");
        assertValidationFailed(
                withMedia(media(SHA256, MAX_DURATION_MS + 1, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT)),
                "media.durationMs");
        assertValidationFailed(withMedia(media(SHA256, null, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT)),
                "media.durationMs");
    }

    /**
     * analysisRange：{@code start < 0}、{@code end <= start}、{@code end > durationMs} 都必须被拒。
     */
    @Test
    void analysis_range_must_be_half_open_and_inside_media() {
        assertThat(validator.validate(withRange(new JobRequest.AnalysisRange(0L, 60_000L)),
                MAX_DURATION_MS)).isNotNull();
        assertThat(validator.validate(withRange(new JobRequest.AnalysisRange(0L, 1L)),
                MAX_DURATION_MS)).isNotNull();
        assertThat(validator.validate(withRange(new JobRequest.AnalysisRange(59_999L, 60_000L)),
                MAX_DURATION_MS)).isNotNull();

        assertValidationFailed(withRange(new JobRequest.AnalysisRange(-1L, 60_000L)),
                "analysisRange.startMs");
        assertValidationFailed(withRange(new JobRequest.AnalysisRange(1000L, 1000L)), "analysisRange");
        assertValidationFailed(withRange(new JobRequest.AnalysisRange(2000L, 1000L)), "analysisRange");
        assertValidationFailed(withRange(new JobRequest.AnalysisRange(0L, 60_001L)), "analysisRange.endMs");
        assertValidationFailed(withRange(null), "analysisRange");
        assertValidationFailed(withRange(new JobRequest.AnalysisRange(null, 60_000L)), "analysisRange");
        assertValidationFailed(withRange(new JobRequest.AnalysisRange(0L, null)), "analysisRange");
    }

    /**
     * urlExpiresAt 必须以 "Z" 结尾且能被解析为 UTC 时间。
     */
    @Test
    void url_expires_at_must_be_utc_with_z_suffix() {
        assertThat(validator.validate(
                withMedia(media(SHA256, 60_000L, "audio/mpeg", SOURCE_URL, "2026-09-13T10:00:00.500Z")),
                MAX_DURATION_MS)).isNotNull();

        assertValidationFailed(
                withMedia(media(SHA256, 60_000L, "audio/mpeg", SOURCE_URL, "2026-09-13T18:00:00+08:00")),
                "media.urlExpiresAt");
        assertValidationFailed(
                withMedia(media(SHA256, 60_000L, "audio/mpeg", SOURCE_URL, "2026-09-13T10:00:00")),
                "media.urlExpiresAt");
        assertValidationFailed(
                withMedia(media(SHA256, 60_000L, "audio/mpeg", SOURCE_URL, "2026-09-13T10:00:00z")),
                "media.urlExpiresAt");
        assertValidationFailed(
                withMedia(media(SHA256, 60_000L, "audio/mpeg", SOURCE_URL, "2026-13-45T99:99:99Z")),
                "media.urlExpiresAt");
        assertValidationFailed(
                withMedia(media(SHA256, 60_000L, "audio/mpeg", SOURCE_URL, "not-a-timeZ")),
                "media.urlExpiresAt");
        assertValidationFailed(
                withMedia(media(SHA256, 60_000L, "audio/mpeg", SOURCE_URL, null)),
                "media.urlExpiresAt");
        assertValidationFailed(
                withMedia(media(SHA256, 60_000L, "audio/mpeg", SOURCE_URL, "  ")),
                "media.urlExpiresAt");
    }

    /**
     * {@code enableSpeakerLabels=true} 必须 422：契约"不支持时不得静默冒充"。
     *
     * <p>若这里返回 200，主项目会以为拿到了说话人分离结果，进而按说话人做分析——
     * 这是比"报错"严重得多的失败模式。
     */
    @Test
    void speaker_labels_true_must_be_rejected_not_silently_dropped() {
        assertThat(validator.validate(
                withParameters(new JobRequest.TranscriptionParameters("zh-CN", Boolean.FALSE)),
                MAX_DURATION_MS)).isNotNull();

        ContractRejectionException rejection = reject(
                withParameters(new JobRequest.TranscriptionParameters("zh-CN", Boolean.TRUE)),
                ContractErrors.VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rejection.status().value()).isEqualTo(422);
        assertDetailPath(rejection, "parameters.enableSpeakerLabels");

        // 缺失同样不接受：静默默认成 false 会让主项目无法确认模块读到了它的意图
        assertValidationFailed(withParameters(new JobRequest.TranscriptionParameters("zh-CN", null)),
                "parameters.enableSpeakerLabels");
        assertValidationFailed(withParameters(null), "parameters");
    }

    /**
     * language：支持的标签通过，不支持的返回 {@code VALIDATION_FAILED}。
     */
    @Test
    void unsupported_language_is_rejected() {
        for (String supported : List.of("zh-CN", "zh", "en", "auto", "ZH-CN", "en-US", "zh-Hans", "zh-en")) {
            assertThat(validator.validate(
                    withParameters(new JobRequest.TranscriptionParameters(supported, Boolean.FALSE)),
                    MAX_DURATION_MS)).as("支持的语言: %s", supported).isNotNull();
        }

        for (String unsupported : List.of("fr", "ja", "zh-TW", "en-GB", "english")) {
            assertValidationFailed(
                    withParameters(new JobRequest.TranscriptionParameters(unsupported, Boolean.FALSE)),
                    "parameters.language");
        }
        assertValidationFailed(withParameters(new JobRequest.TranscriptionParameters(null, Boolean.FALSE)),
                "parameters.language");
        assertValidationFailed(withParameters(new JobRequest.TranscriptionParameters("  ", Boolean.FALSE)),
                "parameters.language");
    }

    /**
     * media 必填与其子字段：mimeType、sourceUrl（必须是带主机名的 URL）。
     *
     * <p>顺带验证拒绝信息不泄漏媒体下载地址（契约 §5：message 不含密钥或媒体 URL）。
     */
    @Test
    void media_must_be_present_and_source_url_must_be_a_real_url() {
        assertThat(validator.validate(valid(), MAX_DURATION_MS)).isNotNull();

        assertValidationFailed(withMedia(null), "media");
        assertValidationFailed(withMedia(media(SHA256, 60_000L, "  ", SOURCE_URL, URL_EXPIRES_AT)),
                "media.mimeType");
        assertValidationFailed(withMedia(media(SHA256, 60_000L, "audio/mpeg", "  ", URL_EXPIRES_AT)),
                "media.sourceUrl");
        assertValidationFailed(withMedia(media(SHA256, 60_000L, "audio/mpeg", "不是合法 URL", URL_EXPIRES_AT)),
                "media.sourceUrl");
        assertValidationFailed(
                withMedia(media(SHA256, 60_000L, "audio/mpeg", "https:///no-host", URL_EXPIRES_AT)),
                "media.sourceUrl");

        ContractRejectionException rejection = reject(
                withMedia(media(SHA256, 60_000L, "audio/mpeg", "  ", URL_EXPIRES_AT)),
                ContractErrors.VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rejection.getMessage()).doesNotContain(SOURCE_URL).doesNotContain("signature");
    }

    /**
     * 一次请求的多个问题都要报出来，且 details 覆盖全部出错字段（禁止静默丢行）。
     */
    @Test
    void all_problems_are_reported_in_one_rejection() {
        JobRequest messy = new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001", "media-001",
                media("not-a-hash", 0L, " ", SOURCE_URL, "no-zone"),
                new JobRequest.AnalysisRange(-1L, -1L),
                new JobRequest.TranscriptionParameters("fr", Boolean.TRUE));

        ContractRejectionException rejection = reject(messy,
                ContractErrors.VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_CONTENT);

        // 8 处：sha256、durationMs、mimeType、urlExpiresAt、analysisRange.startMs、
        // analysisRange（end<=start）、language、enableSpeakerLabels
        assertThat(rejection.details()).hasSize(8);
        assertThat(rejection.details()).extracting(RejectionBody.Detail::path)
                .contains("media.sha256", "media.durationMs", "media.mimeType", "media.urlExpiresAt",
                        "analysisRange.startMs", "analysisRange", "parameters.language",
                        "parameters.enableSpeakerLabels");
        assertThat(rejection.details()).allSatisfy(detail ->
                assertThat(detail.code()).isEqualTo(ContractErrors.VALIDATION_FAILED));
    }

    /**
     * 空请求体：422 {@code VALIDATION_FAILED}，没有字段级 details 可给。
     */
    @Test
    void null_request_body_is_validation_failed() {
        ContractRejectionException rejection = reject(null,
                ContractErrors.VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_CONTENT);

        assertThat(rejection.details()).isEmpty();
        assertThat(rejection.getMessage()).contains("请求体");
    }

    /**
     * 本部署上限本身可配置：同一请求在不同上限下结论不同。
     */
    @Test
    void deployment_limit_is_a_parameter_not_a_constant() {
        JobRequest request = withMedia(media(SHA256, 60_000L, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT));

        assertThat(validator.validate(request, 60_000L)).isNotNull();

        try {
            validator.validate(request, 59_999L);
            throw new AssertionError("超过上限的时长本应被拒绝");
        } catch (ContractRejectionException e) {
            assertThat(e.code()).isEqualTo(ContractErrors.VALIDATION_FAILED);
            assertDetailPath(e, "media.durationMs");
        }
    }

    // ==================================================================
    //  夹具与断言辅助
    // ==================================================================

    /** 默认合法请求：与契约 fixture 同形（zh-CN、整条媒体、不要说话人标签）。 */
    private static JobRequest valid() {
        return new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001", "media-001",
                media(), new JobRequest.AnalysisRange(0L, 60_000L),
                new JobRequest.TranscriptionParameters("zh-CN", Boolean.FALSE));
    }

    private static JobRequest withSchemaVersion(String schemaVersion) {
        return new JobRequest(schemaVersion, "req-001", "TRANSCRIPTION", "cs-001", "media-001",
                media(), new JobRequest.AnalysisRange(0L, 60_000L),
                new JobRequest.TranscriptionParameters("zh-CN", Boolean.FALSE));
    }

    private static JobRequest withModuleType(String moduleType) {
        return new JobRequest("1.0", "req-001", moduleType, "cs-001", "media-001",
                media(), new JobRequest.AnalysisRange(0L, 60_000L),
                new JobRequest.TranscriptionParameters("zh-CN", Boolean.FALSE));
    }

    private static JobRequest withIds(String clientRequestId, String classSessionId, String mediaId) {
        return new JobRequest("1.0", clientRequestId, "TRANSCRIPTION", classSessionId, mediaId,
                media(), new JobRequest.AnalysisRange(0L, 60_000L),
                new JobRequest.TranscriptionParameters("zh-CN", Boolean.FALSE));
    }

    private static JobRequest withMedia(JobRequest.MediaRef mediaRef) {
        return withMediaAndRange(mediaRef, new JobRequest.AnalysisRange(0L, 60_000L));
    }

    private static JobRequest withMediaAndRange(JobRequest.MediaRef mediaRef,
                                                JobRequest.AnalysisRange range) {
        return new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001", "media-001",
                mediaRef, range, new JobRequest.TranscriptionParameters("zh-CN", Boolean.FALSE));
    }

    private static JobRequest withRange(JobRequest.AnalysisRange range) {
        return new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001", "media-001",
                media(), range, new JobRequest.TranscriptionParameters("zh-CN", Boolean.FALSE));
    }

    private static JobRequest withParameters(JobRequest.TranscriptionParameters parameters) {
        return new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001", "media-001",
                media(), new JobRequest.AnalysisRange(0L, 60_000L), parameters);
    }

    private static JobRequest.MediaRef media() {
        return media(SHA256, 60_000L, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT);
    }

    private static JobRequest.MediaRef media(String sha256, Long durationMs, String mimeType,
                                            String sourceUrl, String urlExpiresAt) {
        return new JobRequest.MediaRef(sha256, durationMs, mimeType, sourceUrl, urlExpiresAt);
    }

    /** 直接调用校验器并断言异常类型，避免用例里到处 try/catch。 */
    private ContractRejectionException reject(JobRequest request) {
        try {
            validator.validate(request, MAX_DURATION_MS);
        } catch (ContractRejectionException e) {
            return e;
        }
        throw new AssertionError("请求本应被拒绝，但校验通过了");
    }

    private ContractRejectionException reject(JobRequest request, String expectedCode,
                                              HttpStatus expectedStatus) {
        ContractRejectionException rejection = reject(request);
        assertThat(rejection.code()).isEqualTo(expectedCode);
        assertThat(rejection.status()).isEqualTo(expectedStatus);
        return rejection;
    }

    /** 断言 422 VALIDATION_FAILED，且 details 能定位到字段路径。 */
    private void assertValidationFailed(JobRequest request, String expectedPath) {
        ContractRejectionException rejection = reject(request,
                ContractErrors.VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_CONTENT);
        assertDetailPath(rejection, expectedPath);
    }

    private static void assertDetailPath(ContractRejectionException rejection, String expectedPath) {
        assertThat(rejection.details()).extracting(RejectionBody.Detail::path).contains(expectedPath);
        assertThat(rejection.details()).allSatisfy(detail ->
                assertThat(detail.code()).isEqualTo(ContractErrors.VALIDATION_FAILED));
    }
}
