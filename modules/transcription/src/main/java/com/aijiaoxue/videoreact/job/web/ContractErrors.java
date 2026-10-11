package com.aijiaoxue.videoreact.job.web;

import java.util.List;

/**
 * 契约 §5 的错误码与 HTTP 语义。
 *
 * <p>集中在常量里而不是散落在各处字符串，是因为契约对这些码有<b>精确的语义约定</b>：
 * 主项目靠 {@code code} 决定"改请求还是重试"，写错一个字母就会让上游做出错误决策。
 *
 * <p>两类错误要严格区分（契约明确要求）：
 * <ul>
 *   <li><b>HTTP 拒绝体</b>：请求本身有问题（{@code MALFORMED_JSON}、{@code VALIDATION_FAILED}…），
 *       返回 {@code {code, message, retryable, requestId, details}}；</li>
 *   <li><b>任务 FAILED</b>：请求合法、任务受理后失败（{@code DECODE_FAILED}…），
 *       放在信封的 {@code error} 里，HTTP 仍是 200。</li>
 * </ul>
 * 把后者当成 4xx 返回会让主项目以为"请求写错了"，从而不去查任务状态。
 */
public final class ContractErrors {

    // ---------- HTTP 拒绝码 ----------
    /** 400：JSON 结构非法。 */
    public static final String MALFORMED_JSON = "MALFORMED_JSON";
    /** 401：未认证。 */
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    /** 403：已认证但无权（含类型不匹配、下载源不被允许）。 */
    public static final String FORBIDDEN = "FORBIDDEN";
    /** 404：不存在或无权，不泄漏归属。 */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 409：同键不同内容。 */
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    /** 409：身份/归属与已登记任务不一致。 */
    public static final String ASSOCIATION_MISMATCH = "ASSOCIATION_MISMATCH";
    /** 413：请求体过大。 */
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    /** 422：字段取值非法。 */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    /** 422：不支持的 schemaVersion。 */
    public static final String UNSUPPORTED_SCHEMA = "UNSUPPORTED_SCHEMA";
    /** 429：限流。 */
    public static final String RATE_LIMITED = "RATE_LIMITED";
    /** 503：临时不可用（模块侧问题或下载源临时故障）。 */
    public static final String TEMPORARILY_UNAVAILABLE = "TEMPORARILY_UNAVAILABLE";

    // ---------- 任务 FAILED 码 ----------
    /** 下载地址过期/被拒；主项目续期后可用同键重提。 */
    public static final String MEDIA_URL_EXPIRED = "MEDIA_URL_EXPIRED";
    /** 下载内容与登记 sha256 不一致；必须先修数据。 */
    public static final String MEDIA_HASH_MISMATCH = "MEDIA_HASH_MISMATCH";
    /** 容器/编解码不支持，或时间映射无法恢复。 */
    public static final String UNSUPPORTED_MEDIA = "UNSUPPORTED_MEDIA";
    /** 解码失败（含无音轨、文件损坏）。 */
    public static final String DECODE_FAILED = "DECODE_FAILED";
    /** 处理失败（含 ASR 提供方失败、内部错误）。 */
    public static final String PROCESSING_FAILED = "PROCESSING_FAILED";

    private ContractErrors() {
    }

    /**
     * 判断是否为 HTTP 拒绝码（相对于任务 FAILED 码）。
     *
     * @param code 错误码
     * @return true 表示应当以 4xx/5xx 直接拒绝请求
     */
    public static boolean isHttpRejection(String code) {
        return List.of(MALFORMED_JSON, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND,
                IDEMPOTENCY_CONFLICT, ASSOCIATION_MISMATCH, PAYLOAD_TOO_LARGE,
                VALIDATION_FAILED, UNSUPPORTED_SCHEMA, RATE_LIMITED,
                TEMPORARILY_UNAVAILABLE).contains(code);
    }
}
