package com.aijiaoxue.videoreact.job.service;

import com.aijiaoxue.videoreact.job.web.ContractErrors;

import java.util.Set;

/**
 * 把"处理过程中抛出的错误码"收敛到契约 §5 允许的<b>任务级</b>错误码。
 *
 * <p><b>为什么需要这一层</b>：下载与音轨提取阶段会抛出一些
 * <b>HTTP 语义</b>的错误码（{@code PAYLOAD_TOO_LARGE}、{@code VALIDATION_FAILED}、
 * {@code FORBIDDEN}）——它们在"请求本身有问题"时是对的，但一旦任务已经受理，
 * 最终结果只能通过 {@code GET /jobs/{id}} 返回，此时 {@code error.code} 必须落在
 * 契约列出的任务码内：
 * {@code MEDIA_URL_EXPIRED} / {@code MEDIA_HASH_MISMATCH} / {@code UNSUPPORTED_MEDIA}
 * / {@code DECODE_FAILED} / {@code PROCESSING_FAILED}。
 *
 * <p>不这样做会出现两个问题：主项目按 HTTP 码判断"该改请求还是该重试"时拿到未知值；
 * 且信封里出现了一个契约从未定义的 code，联调时无从对账。
 *
 * <p>注意<b>不改变可重试性</b>：体积超限、地址不允许这类问题重试无效，
 * 因此映射后仍然 {@code retryable=false}。
 */
public final class TaskErrorCodes {

    /** 契约允许的任务级错误码。 */
    private static final Set<String> CONTRACT_TASK_CODES = Set.of(
            ContractErrors.MEDIA_URL_EXPIRED,
            ContractErrors.MEDIA_HASH_MISMATCH,
            ContractErrors.UNSUPPORTED_MEDIA,
            ContractErrors.DECODE_FAILED,
            ContractErrors.PROCESSING_FAILED);

    private TaskErrorCodes() {
    }

    /**
     * 规范化任务级错误码。
     *
     * @param raw 处理过程中得到的原始错误码，可为 null
     * @return 契约允许的任务级错误码
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return ContractErrors.PROCESSING_FAILED;
        }
        if (CONTRACT_TASK_CODES.contains(raw)) {
            return raw;
        }
        return switch (raw) {
            // 体积超限：请求本身合法（声明值在限内），实际内容过大 → 处理失败
            case ContractErrors.PAYLOAD_TOO_LARGE -> ContractErrors.PROCESSING_FAILED;
            // 下载源不被允许（含解析到内网、不在白名单）：属于不可处理的媒体来源
            case ContractErrors.FORBIDDEN -> ContractErrors.UNSUPPORTED_MEDIA;
            // 提交期才会出现的字段问题，走到这里说明是内部不一致
            case ContractErrors.VALIDATION_FAILED,
                 ContractErrors.UNSUPPORTED_SCHEMA,
                 ContractErrors.MALFORMED_JSON -> ContractErrors.PROCESSING_FAILED;
            // 限流/临时不可用：可重试的处理失败
            case ContractErrors.RATE_LIMITED,
                 ContractErrors.TEMPORARILY_UNAVAILABLE -> ContractErrors.PROCESSING_FAILED;
            default -> ContractErrors.PROCESSING_FAILED;
        };
    }

    /**
     * 判断错误码是否属于契约允许的任务级码。
     *
     * @param code 错误码
     * @return true 表示合规
     */
    public static boolean isContractTaskCode(String code) {
        return code != null && CONTRACT_TASK_CODES.contains(code);
    }
}
