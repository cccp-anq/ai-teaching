package com.aijiaoxue.videoreact.asr.client;

import com.aijiaoxue.videoreact.asr.config.AsrProperties;
import com.tencentcloudapi.asr.v20190614.AsrClient;
import com.tencentcloudapi.asr.v20190614.models.CreateRecTaskRequest;
import com.tencentcloudapi.asr.v20190614.models.CreateRecTaskResponse;
import com.tencentcloudapi.asr.v20190614.models.DescribeTaskStatusRequest;
import com.tencentcloudapi.asr.v20190614.models.DescribeTaskStatusResponse;
import com.tencentcloudapi.asr.v20190614.models.SentenceDetail;
import com.tencentcloudapi.asr.v20190614.models.SentenceWords;
import com.tencentcloudapi.asr.v20190614.models.TaskStatus;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * 腾讯云录音文件识别（大模型 2.0）客户端。
 *
 * <p>只做两件事：把 {@link AsrSubmitCommand} 翻译成 {@code CreateRecTaskRequest}，
 * 把 {@code DescribeTaskStatus} 的返回翻译成 {@link AsrProviderStatus}。
 * 所有业务判断（能否重复提交、超时怎么办）都在 {@code AsrService} 里。
 *
 * <p><b>注意</b>：{@code TencentCloudSDKException} 只提供 {@code getErrorCode()} 与
 * {@code getRequestId()}，错误说明在 {@code getMessage()} 里，没有 {@code getErrorMessage()}。
 *
 * <p>对应官方接口：<a href="https://cloud.tencent.com/document/api/1093/37823">CreateRecTask</a>、
 * <a href="https://cloud.tencent.com/document/api/1093/37822">DescribeTaskStatus</a>。
 */
public class TencentAsrClient implements AsrProviderClient {

    /** 提供方标识。 */
    public static final String PROVIDER = "TENCENT";

    private static final Logger log = LoggerFactory.getLogger(TencentAsrClient.class);

    private final AsrClient sdkClient;

    /** 凭证与引擎参数留一份引用，供 credentialsReady() / configurationHints() 使用。 */
    private final String secretId;
    private final String secretKey;
    private final String engineModelType;

    public TencentAsrClient(AsrProperties properties) {
        AsrProperties.Tencent tencent = properties.provider().tencent();
        this.secretId = tencent.secretId();
        this.secretKey = tencent.secretKey();
        this.engineModelType = properties.engine().modelType();

        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint(properties.provider().endpoint());
        httpProfile.setConnTimeout(properties.provider().connectTimeoutSeconds());
        httpProfile.setReadTimeout(properties.provider().readTimeoutSeconds());
        httpProfile.setWriteTimeout(properties.provider().readTimeoutSeconds());

        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);

        Credential credential = new Credential(tencent.secretId(), tencent.secretKey());
        this.sdkClient = new AsrClient(credential, tencent.region(), clientProfile);
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public boolean credentialsReady() {
        return !isBlank(secretId) && !isBlank(secretKey);
    }

    @Override
    public java.util.List<String> configurationHints() {
        if (credentialsReady()) {
            return java.util.List.of();
        }
        return java.util.List.of(
                "腾讯云标准 ASR 凭证未配置：请设置环境变量 TENCENT_SECRET_ID / TENCENT_SECRET_KEY"
                        + "（复制 .env.example 为 .env 后填入，或用 scripts/Use-Env.ps1 载入）。"
                        + "当前 secretId " + (isBlank(secretId) ? "为空" : "已配置")
                        + "，secretKey " + (isBlank(secretKey) ? "为空" : "已配置") + "。");
    }

    @Override
    public String modelIdentifier() {
        return engineModelType;
    }

    /**
     * 腾讯云 Url 方式的官方上限：1GB。
     *
     * <p>官方原文：「音频 URL 时长不能大于 5 小时，文件大小不超过 1GB」。
     * 因此 45 分钟 1080p 课堂录像（约 1.0–1.7GB）必须先用 FFmpeg 抽出音频再提交。
     */
    @Override
    public long maxInputUrlBytes() {
        return AsrProperties.Source.TENCENT_URL_SIZE_LIMIT;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String submit(AsrSubmitCommand command) {
        // 契约路径总是内联提交音频（模块已下载并核验过源媒体），
        // 而腾讯云标准 ASR 只接受"公网 URL"或"≤5MB 的 base64 Data"。
        // 这里必须明确报错，而不是发出一个 Url=null 的请求——
        // 那种请求会得到一个含义模糊的参数错误，排查成本很高。
        if (command.hasInlineAudio()) {
            throw new AsrProviderException(PROVIDER, "InlineAudioNotSupported",
                    "腾讯云标准 ASR 客户端尚未实现内联音频提交（契约路径需要它）："
                            + "请在 .env 里改用 ASR_PROVIDER=wand，"
                            + "或为本客户端补上 SourceType=1 的 base64 Data 提交实现。",
                    false);
        }
        if (command.audioUrl() == null || command.audioUrl().isBlank()) {
            throw new AsrProviderException(PROVIDER, "MissingAudioSource",
                    "提交命令既没有音频 URL 也没有内联音频", false);
        }

        CreateRecTaskRequest request = new CreateRecTaskRequest();
        request.setEngineModelType(command.engineModelType());
        request.setChannelNum(command.channelNum());
        request.setResTextFormat(command.resTextFormat());
        request.setSourceType(command.sourceType());
        request.setUrl(command.audioUrl());
        request.setSpeakerDiarization(command.speakerDiarization());
        request.setConvertNumMode(command.convertNumMode());
        request.setFilterDirty(command.filterDirty());
        request.setFilterPunc(command.filterPunc());
        request.setFilterModal(command.filterModal());
        if (command.sentenceMaxLength() > 0) {
            request.setSentenceMaxLength(command.sentenceMaxLength());
        }
        if (command.hotwordList() != null && !command.hotwordList().isBlank()) {
            request.setHotwordList(command.hotwordList());
        }
        // 第一版不做 Callback：官方说明使用轮询方式获取结果时无需提交 CallbackUrl。
        // 显式不设置，避免误传一个不可达地址导致腾讯侧反复回调失败。

        log.info("提交腾讯云 ASR 任务: engine={}, channelNum={}, resTextFormat={}, hotwordCount={}, url={}",
                command.engineModelType(), command.channelNum(), command.resTextFormat(),
                countHotwords(command.hotwordList()), maskUrl(command.audioUrl()));

        try {
            CreateRecTaskResponse response = sdkClient.CreateRecTask(request);
            Long taskId = response.getData() == null ? null : response.getData().getTaskId();
            if (taskId == null) {
                throw new AsrProviderException(PROVIDER, "EmptyTaskId",
                        "腾讯云未返回 TaskId，RequestId=" + response.getRequestId(), true);
            }
            log.info("腾讯云 ASR 任务已创建: taskId={}, requestId={}", taskId, response.getRequestId());
            // 对外统一成字符串：跨提供方保持一致（TokenHub 的 task_id 本身就是字符串）
            return String.valueOf(taskId);
        } catch (TencentCloudSDKException e) {
            throw translate(e, "CreateRecTask");
        }
    }

    @Override
    public AsrProviderStatus describe(String providerTaskId) {
        Long numericTaskId = parseTaskId(providerTaskId);
        DescribeTaskStatusRequest request = new DescribeTaskStatusRequest();
        request.setTaskId(numericTaskId);

        try {
            DescribeTaskStatusResponse response = sdkClient.DescribeTaskStatus(request);
            TaskStatus data = response.getData();
            if (data == null) {
                throw new AsrProviderException(PROVIDER, "EmptyTaskStatus",
                        "腾讯云未返回任务状态，RequestId=" + response.getRequestId(), true);
            }
            return new AsrProviderStatus(
                    data.getTaskId() == null ? providerTaskId : String.valueOf(data.getTaskId()),
                    ProviderTaskStatus.fromTencent(data.getStatus()),
                    data.getStatusStr(),
                    data.getResult(),
                    toProviderSentences(data.getResultDetail()),
                    data.getAudioDuration() == null ? null : data.getAudioDuration().doubleValue(),
                    data.getErrorMsg());
        } catch (TencentCloudSDKException e) {
            throw translate(e, "DescribeTaskStatus");
        }
    }

    /**
     * 把字符串形态的任务 ID 还原成腾讯云要求的数字。
     *
     * @param providerTaskId 任务 ID 字符串
     * @return 数字任务 ID
     * @throws AsrProviderException 不是合法数字时抛出（说明这行数据不属于本提供方）
     */
    private static Long parseTaskId(String providerTaskId) {
        try {
            return Long.valueOf(providerTaskId);
        } catch (NumberFormatException e) {
            throw new AsrProviderException(PROVIDER, "InvalidTaskIdFormat",
                    "腾讯云 TaskId 应为数字，实际为: " + providerTaskId
                            + "（该任务可能属于其它提供方，请检查 asr_task.provider 与 ASR_PROVIDER 是否一致）",
                    false, e);
        }
    }

    /**
     * 把 SDK 的 {@code SentenceDetail[]} 映射为自有交换类型。
     *
     * <p>只取本模块需要且语义稳定的字段，腾讯新增字段不影响这里。
     *
     * @param details SDK 分段数组，可能为 null
     * @return 自有分段列表；输入为空时返回空列表
     */
    static List<ProviderSentence> toProviderSentences(SentenceDetail[] details) {
        if (details == null || details.length == 0) {
            return List.of();
        }
        return Arrays.stream(details)
                .map(TencentAsrClient::toProviderSentence)
                .toList();
    }

    private static ProviderSentence toProviderSentence(SentenceDetail detail) {
        if (detail == null) {
            return new ProviderSentence(null, null, null, null, null, null, List.of());
        }
        return new ProviderSentence(
                detail.getFinalSentence(),
                detail.getSliceSentence(),
                detail.getWrittenText(),
                detail.getStartMs(),
                detail.getEndMs(),
                detail.getSpeakerId(),
                toProviderWords(detail.getWords()));
    }

    private static List<ProviderSentence.ProviderWord> toProviderWords(SentenceWords[] words) {
        if (words == null || words.length == 0) {
            return List.of();
        }
        return Arrays.stream(words)
                .filter(word -> word != null)
                .map(word -> new ProviderSentence.ProviderWord(
                        word.getWord(), word.getOffsetStartMs(), word.getOffsetEndMs()))
                .toList();
    }

    /**
     * 把 SDK 异常翻译成本模块统一异常，并判定是否可重试。
     */
    private AsrProviderException translate(TencentCloudSDKException e, String action) {
        String code = e.getErrorCode() == null ? "UnknownError" : e.getErrorCode();
        String message = "%s 调用失败 [%s]: %s (RequestId=%s)"
                .formatted(action, code, e.getMessage(), e.getRequestId());
        // 已知不可重试的错误码直接判死；其余（含未知码）保守视为可重试，
        // 避免一次网络抖动就把任务标记失败，真正的死循环由 poll.timeout 兜底。
        boolean retryable = !isKnownNonRetryable(code);
        log.warn("腾讯云接口异常: action={}, code={}, retryable={}, requestId={}, message={}",
                action, code, retryable, e.getRequestId(), e.getMessage());
        return new AsrProviderException(PROVIDER, code, message, retryable, e);
    }

    private boolean isKnownNonRetryable(String code) {
        return code.startsWith("AuthFailure")
                || code.startsWith("InvalidParameter")
                || code.startsWith("MissingParameter")
                || code.startsWith("UnknownParameter")
                || code.startsWith("FailedOperation.User")
                || code.startsWith("FailedOperation.ServiceIsolate")
                || code.equals("FailedOperation.UserNotRegistered")
                || AsrErrorCodes.isExpiredCode(code);
    }

    private static int countHotwords(String hotwordList) {
        if (hotwordList == null || hotwordList.isBlank()) {
            return 0;
        }
        return hotwordList.split(",").length;
    }

    /**
     * 打日志时对 URL 做脱敏：预签名 URL 的 query 里带签名，不应完整落日志。
     */
    static String maskUrl(String url) {
        if (url == null) {
            return null;
        }
        int queryIndex = url.indexOf('?');
        return queryIndex < 0 ? url : url.substring(0, queryIndex) + "?<signed-params-hidden>";
    }
}
