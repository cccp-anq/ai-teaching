package com.aijiaoxue.videoreact.job.service;

import com.aijiaoxue.videoreact.job.wire.JobRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务幂等指纹测试，对应契约 §3 的幂等规则。
 *
 * <p>契约原文：「重复请求比较身份、hash、duration、range、parameters，
 * <b>不把临时 sourceUrl/urlExpiresAt 纳入业务幂等 hash</b>；
 * 同键仅更新下载凭据且尚未终态可续处理」。
 *
 * <p>这条规则有一个非常具体的业务后果，也是本类最重要的用例：
 * 主项目的受控下载链接是<b>短期</b>的，过期后会用<b>同一个 clientRequestId</b>
 * 重新提交并只换掉 {@code sourceUrl}/{@code urlExpiresAt}。
 * 如果指纹把这些字段算进去，续期重提就会变成 409 IDEMPOTENCY_CONFLICT，
 * 主项目只能换新键 → 重复计费/重复识别。所以"改 URL 不改指纹"必须由测试钉住。
 *
 * <p>全部用例都是纯内存计算：不需要网络、凭证、数据库或 ffmpeg。
 */
class RequestFingerprintTest {

    /** 与沙盒配置文件一致的部署上限，仅用于构造请求。 */
    private static final long MAX_DURATION_MS = 7_200_000L;

    /** 合法 hash。 */
    private static final String SHA256 = "a".repeat(64);

    /** 首次下发的受控下载地址。 */
    private static final String SOURCE_URL =
            "https://media.example.com/classroom/001.mp4?signature=aaa&expires=1";

    /** 首次下发的地址过期时间。 */
    private static final String URL_EXPIRES_AT = "2026-09-13T10:00:00Z";

    private final RequestFingerprint fingerprint = new RequestFingerprint(new ObjectMapper());

    /**
     * 同样的身份/hash/duration/range/parameters → 指纹相同（幂等的基础）。
     */
    @Test
    void identical_requests_produce_identical_fingerprints() {
        JobRequest first = baseRequest();
        JobRequest second = baseRequest();

        assertThat(fingerprint.of(first)).isEqualTo(fingerprint.of(first));
        assertThat(fingerprint.of(first)).isEqualTo(fingerprint.of(second));
    }

    /**
     * <b>只改 media.sourceUrl 或 media.urlExpiresAt → 指纹必须相同。</b>
     *
     * <p>这是契约明确要求的"续期不换键"：主项目续期下载链接后用同键重提，
     * 必须命中同一个任务，而不是被判成内容冲突。
     */
    @Test
    void source_url_and_expiry_must_not_affect_the_fingerprint() {
        String expected = fingerprint.of(baseRequest());

        JobRequest renewedUrl = withMedia(SHA256, 60_000L, "audio/mpeg",
                "https://media.example.com/classroom/001.mp4?signature=bbb&expires=2", URL_EXPIRES_AT);
        JobRequest renewedExpiry = withMedia(SHA256, 60_000L, "audio/mpeg",
                SOURCE_URL, "2026-09-13T11:30:00Z");
        JobRequest bothRenewed = withMedia(SHA256, 60_000L, "audio/mpeg",
                "https://cdn.example.com/other/path/001.mp4?signature=ccc&expires=3",
                "2026-09-14T00:00:00Z");

        assertThat(fingerprint.of(renewedUrl)).isEqualTo(expected);
        assertThat(fingerprint.of(renewedExpiry)).isEqualTo(expected);
        assertThat(fingerprint.of(bothRenewed)).isEqualTo(expected);
    }

    /**
     * 业务字段变化 → 指纹必须不同：media.sha256 / durationMs / mimeType。
     */
    @Test
    void media_business_fields_are_part_of_the_fingerprint() {
        String expected = fingerprint.of(baseRequest());

        assertThat(fingerprint.of(withMedia("b".repeat(64), 60_000L, "audio/mpeg",
                SOURCE_URL, URL_EXPIRES_AT))).isNotEqualTo(expected);
        assertThat(fingerprint.of(withMedia(SHA256, 60_001L, "audio/mpeg",
                SOURCE_URL, URL_EXPIRES_AT))).isNotEqualTo(expected);
        assertThat(fingerprint.of(withMedia(SHA256, 60_000L, "video/mp4",
                SOURCE_URL, URL_EXPIRES_AT))).isNotEqualTo(expected);
    }

    /**
     * 业务字段变化 → 指纹必须不同：身份（clientRequestId / classSessionId / mediaId / moduleType / schemaVersion）。
     */
    @Test
    void identity_fields_are_part_of_the_fingerprint() {
        String expected = fingerprint.of(baseRequest());

        assertThat(fingerprint.of(new JobRequest("1.0", "req-002", "TRANSCRIPTION", "cs-001",
                "media-001", media(), range(), parameters()))).isNotEqualTo(expected);
        assertThat(fingerprint.of(new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-002",
                "media-001", media(), range(), parameters()))).isNotEqualTo(expected);
        assertThat(fingerprint.of(new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001",
                "media-002", media(), range(), parameters()))).isNotEqualTo(expected);
        assertThat(fingerprint.of(new JobRequest("1.0", "req-001", "HEAD_UP", "cs-001",
                "media-001", media(), range(), parameters()))).isNotEqualTo(expected);
        assertThat(fingerprint.of(new JobRequest("2.0", "req-001", "TRANSCRIPTION", "cs-001",
                "media-001", media(), range(), parameters()))).isNotEqualTo(expected);
    }

    /**
     * 业务字段变化 → 指纹必须不同：analysisRange 与 parameters。
     */
    @Test
    void range_and_parameters_are_part_of_the_fingerprint() {
        String expected = fingerprint.of(baseRequest());

        assertThat(fingerprint.of(withRange(1L, 60_000L))).isNotEqualTo(expected);
        assertThat(fingerprint.of(withRange(0L, 59_999L))).isNotEqualTo(expected);
        assertThat(fingerprint.of(withParameters("en", false))).isNotEqualTo(expected);
        assertThat(fingerprint.of(withParameters("zh-CN", true))).isNotEqualTo(expected);
    }

    /**
     * 指纹是<b>小写</b> 64 位十六进制 SHA-256。
     *
     * <p>主项目会把它当不透明字符串比较/登记，大小写和长度都必须稳定。
     */
    @Test
    void fingerprint_is_lowercase_hex_sha256() {
        String value = fingerprint.of(baseRequest());

        assertThat(value).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(value).isEqualTo(value.toLowerCase(java.util.Locale.ROOT));
        assertThat(value).isNotEqualTo(fingerprint.of(withRange(0L, 59_999L)));
    }

    /**
     * 已知答案测试：指纹必须等于"手写规范 JSON 文本"的 SHA-256。
     *
     * <p>这里刻意在测试里独立拼出规范文本（键递归排序、数组保留顺序、无空白），
     * 再用 JDK 的 MessageDigest 自己算哈希。这样即使 {@link RequestFingerprint}
     * 内部实现被改写（换序列化库、改字段集合），只要语义变了本用例就会失败——
     * 而不是两边用同一份实现"自证正确"。
     *
     * <p>注意文本里 {@code classSessionId} 排在 {@code clientRequestId} <b>之前</b>：
     * 规范顺序是按字符串排序的结果，与字段声明顺序无关。
     */
    @Test
    void fingerprint_matches_independently_written_canonical_json() {
        String canonical = "{"
                + "\"analysisRange\":{\"endMs\":60000,\"startMs\":0},"
                + "\"classSessionId\":\"cs-001\","
                + "\"clientRequestId\":\"req-001\","
                + "\"media\":{\"durationMs\":60000,\"mimeType\":\"audio/mpeg\",\"sha256\":\"" + SHA256 + "\"},"
                + "\"mediaId\":\"media-001\","
                + "\"moduleType\":\"TRANSCRIPTION\","
                + "\"parameters\":{\"enableSpeakerLabels\":false,\"language\":\"zh-CN\"},"
                + "\"schemaVersion\":\"1.0\""
                + "}";

        // 整份请求序列化后会带上 sourceUrl/urlExpiresAt，那不是业务幂等文本，故必须不相等
        assertThat(RequestFingerprint.canonicalize(new ObjectMapper().valueToTree(baseRequest())))
                .isNotEqualTo(canonical);
        assertThat(fingerprint.of(baseRequest())).isEqualTo(sha256Hex(canonical));
        // 续期后（只换 sourceUrl/urlExpiresAt）仍然命中同一条规范文本
        assertThat(fingerprint.of(withMedia(SHA256, 60_000L, "audio/mpeg",
                "https://media.example.com/classroom/001.mp4?signature=zzz", "2026-09-20T10:00:00Z")))
                .isEqualTo(sha256Hex(canonical));
    }

    /**
     * 规范 JSON：键递归排序、忽略 key 顺序、数组保留顺序。
     */
    @Test
    void canonicalization_sorts_keys_recursively_and_keeps_array_order() {
        ObjectMapper mapper = new ObjectMapper();

        ObjectNode first = mapper.createObjectNode();
        first.put("b", 1);
        first.put("a", "x");
        ObjectNode firstNested = first.putObject("nested");
        firstNested.put("z", true);
        firstNested.put("y", (String) null);

        ObjectNode second = mapper.createObjectNode();
        ObjectNode secondNested = second.putObject("nested");
        secondNested.put("y", (String) null);
        secondNested.put("z", true);
        second.put("a", "x");
        second.put("b", 1);

        assertThat(RequestFingerprint.canonicalize(first))
                .isEqualTo("{\"a\":\"x\",\"b\":1,\"nested\":{\"y\":null,\"z\":true}}")
                .isEqualTo(RequestFingerprint.canonicalize(second));

        // 数组顺序必须保留：契约明确"数组保留顺序"，不能排序
        ObjectNode withArray = mapper.createObjectNode();
        ArrayNode warnings = withArray.putArray("warnings");
        warnings.add("PARTIAL_PROCESSING");
        warnings.add("NO_SPEECH");
        assertThat(RequestFingerprint.canonicalize(withArray))
                .isEqualTo("{\"warnings\":[\"PARTIAL_PROCESSING\",\"NO_SPEECH\"]}")
                .isNotEqualTo(RequestFingerprint.canonicalize(
                        mapper.createObjectNode().putArray("warnings").add("NO_SPEECH").add("PARTIAL_PROCESSING")));
    }

    /**
     * 含需要转义的字符（引号、反斜杠、换行、回车、制表、中文）时指纹仍然稳定且可区分。
     *
     * <p>ID 是<b>不透明字符串</b>，契约只限制长度不限制字符集；如果转义处理不一致，
     * 同一份请求在不同序列化路径下会算出不同指纹，幂等就会随机失效。
     */
    @Test
    void special_characters_are_escaped_stably() {
        String tricky = "请求\"引号\" 反斜杠\\ 换行\n 回车\r 制表\t 中文：傅里叶";
        String alsoTricky = tricky + "。";

        JobRequest first = new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001",
                tricky, media(), range(), parameters());
        JobRequest sameAgain = new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001",
                tricky, media(), range(), parameters());
        JobRequest different = new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001",
                alsoTricky, media(), range(), parameters());

        assertThat(fingerprint.of(first)).isEqualTo(fingerprint.of(sameAgain));
        assertThat(fingerprint.of(first)).isNotEqualTo(fingerprint.of(different));
        assertThat(fingerprint.of(first)).matches("[0-9a-f]{64}");

        // 规范文本里的转义必须与 JSON 一致：换行不能原样进入文本（否则含 \n 的哈希跨实现不稳定）
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("mediaId", tricky);
        String canonical = RequestFingerprint.canonicalize(node);
        assertThat(canonical).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
        assertThat(canonical).contains("\\n").contains("\\r").contains("\\t")
                .contains("\\\"").contains("\\\\").contains("中文：傅里叶");
    }

    /**
     * 身份字段与指纹一一对应：不能因为 hash 相同就复用身份不同的任务。
     *
     * <p>契约：参数/身份不同且同 clientRequestId → 409 IDEMPOTENCY_CONFLICT，
     * 因此指纹必须覆盖全部身份字段。
     */
    @Test
    void fingerprint_covers_every_business_identity_field() {
        String base = fingerprint.of(baseRequest());

        assertThat(fingerprint.of(new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001",
                "media-001", media(), range(), parameters())))
                .isEqualTo(base);
        assertThat(fingerprint.of(withMedia(SHA256, 60_000L, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT)))
                .isEqualTo(base);
        assertThat(fingerprint.of(new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001",
                "media-001", media(), range(),
                new JobRequest.TranscriptionParameters("zh-CN", Boolean.FALSE))))
                .isEqualTo(base);
    }

    /**
     * 指纹计算对缺失的子对象是防御性的（正式调用发生在校验之后，不会走到这里）。
     *
     * <p>这里只钉住"不抛异常且仍然是合法 64 位十六进制"，
     * 避免将来把空指针异常泄漏到 500 响应里。
     */
    @Test
    void missing_optional_sub_objects_do_not_throw() {
        JobRequest bare = new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001",
                "media-001", null, null, null);

        assertThat(fingerprint.of(bare)).matches("[0-9a-f]{64}");
        assertThat(fingerprint.of(bare)).isNotEqualTo(fingerprint.of(baseRequest()));
        assertThat(fingerprint.of(new JobRequest(null, null, null, null, null, null, null, null)))
                .matches("[0-9a-f]{64}");
    }

    // ==================================================================
    //  夹具
    // ==================================================================

    /** 默认合法请求：与契约 fixture 同形（zh-CN、整条媒体、不要说话人标签）。 */
    private static JobRequest baseRequest() {
        return new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001", "media-001",
                media(), range(), parameters());
    }

    private static JobRequest.MediaRef media() {
        return new JobRequest.MediaRef(SHA256, 60_000L, "audio/mpeg", SOURCE_URL, URL_EXPIRES_AT);
    }

    private static JobRequest withMedia(String sha256, Long durationMs, String mimeType,
                                       String sourceUrl, String urlExpiresAt) {
        return new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001", "media-001",
                new JobRequest.MediaRef(sha256, durationMs, mimeType, sourceUrl, urlExpiresAt),
                range(), parameters());
    }

    private static JobRequest withRange(Long startMs, Long endMs) {
        return new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001", "media-001",
                media(), new JobRequest.AnalysisRange(startMs, endMs), parameters());
    }

    private static JobRequest withParameters(String language, Boolean enableSpeakerLabels) {
        return new JobRequest("1.0", "req-001", "TRANSCRIPTION", "cs-001", "media-001",
                media(), range(), new JobRequest.TranscriptionParameters(language, enableSpeakerLabels));
    }

    private static JobRequest.AnalysisRange range() {
        return new JobRequest.AnalysisRange(0L, 60_000L);
    }

    private static JobRequest.TranscriptionParameters parameters() {
        return new JobRequest.TranscriptionParameters("zh-CN", Boolean.FALSE);
    }

    /** 独立实现一遍 SHA-256，避免与被测代码共用同一份哈希逻辑。 */
    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
