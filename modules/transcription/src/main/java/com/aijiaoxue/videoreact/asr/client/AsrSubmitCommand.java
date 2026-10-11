package com.aijiaoxue.videoreact.asr.client;

import java.util.List;

/**
 * 提交识别任务时需要的全部参数。
 *
 * <p>由 {JobRunner} 组装，client 只负责翻译成各提供方的请求，不做业务判断。
 *
 * <p><b>两种输入方式</b>（二选一，不能同时为空）：
 * <ul>
 *   <li>{@code audioUrl}：把地址交给提供方去下载；</li>
 *   <li>{@code audioBytes}：把音频内容内联提交（TokenHub 的 {@code data} 通道，base64）。</li>
 * </ul>
 * 哪一种可用<b>由提供方决定</b>，见 {@link AsrProviderClient#acceptsInlineAudio()}。
 * 两者的取舍是实打实的：
 * <ul>
 *   <li><b>内联</b>——模块自己下载并核验了 sha256，字节不再离开模块；缺点是支持它的模型
 *       普遍有短音频限制（阿里云 5 分钟档、TokenHub 可用）；</li>
 *   <li><b>URL</b>——长音频档<b>只</b>认公网 URL（阿里云 filetrans 系列 12 小时档）。
 *       这时 {@code audioUrl} 必须是暂存后的<b>预签名地址</b>，不是原始 {@code sourceUrl}，
 *       否则等于把受控地址交给第三方。</li>
 * </ul>
 *
 * @param audioUrl          可下载的音频/视频 URL；用内联方式时为 null
 * @param audioBytes        内联音频内容；用 URL 方式时为 null
 * @param audioFileName     内联音频的文件名（仅用于日志与提供方提示）
 * @param engineModelType   引擎/模型标识
 * @param channelNum        声道数
 * @param resTextFormat     返回样式，3 = 词级时间戳 + 标点 + 按标点分段
 * @param sourceType        数据来源，0 = URL（腾讯云标准 ASR 用）
 * @param speakerDiarization 是否开启说话人分离
 * @param convertNumMode    阿拉伯数字智能转换
 * @param filterDirty       脏词过滤
 * @param filterPunc        标点过滤
 * @param filterModal       语气词过滤
 * @param sentenceMaxLength 单标点最多字数，0 = 关闭
 * @param hotwordList       临时热词表，形如 {@code 事务|10,ACID|11}；为空表示不传
 * @param languageHints     语种提示（BCP-47 短码，如 {@code zh} / {@code en}）；
 *                          为空表示让引擎自行判断。<b>各提供方支持范围不同</b>——
 *                          阿里云 filetrans 系列接受该参数，腾讯云标准 ASR 用
 *                          {@code EngineModelType} 表达语种而不接受它，
 *                          因此 client 必须在为空时<b>整个字段都不发</b>，
 *                          而不是发一个空数组。
 */
public record AsrSubmitCommand(
        String audioUrl,
        byte[] audioBytes,
        String audioFileName,
        String engineModelType,
        long channelNum,
        long resTextFormat,
        long sourceType,
        long speakerDiarization,
        long convertNumMode,
        long filterDirty,
        long filterPunc,
        long filterModal,
        long sentenceMaxLength,
        String hotwordList,
        List<String> languageHints) {

    /** 防御性拷贝，避免把可变列表暴露给 client。 */
    public AsrSubmitCommand {
        languageHints = languageHints == null ? null : List.copyOf(languageHints);
    }

    /**
     * 构造 URL 提交命令。
     *
     * @param audioUrl 可下载地址
     * @param engineModelType 引擎标识
     * @return 命令
     */
    public static AsrSubmitCommand ofUrl(String audioUrl, String engineModelType) {
        return new AsrSubmitCommand(audioUrl, null, null, engineModelType, 1, 3, 0, 0, 1, 0, 0, 0, 0, null, null);
    }

    /**
     * 构造内联音频提交命令。
     *
     * @param audioBytes 音频内容
     * @param fileName   文件名
     * @param engineModelType 引擎标识
     * @return 命令
     */
    public static AsrSubmitCommand ofBytes(byte[] audioBytes, String fileName, String engineModelType) {
        return new AsrSubmitCommand(null, audioBytes, fileName, engineModelType, 1, 3, 0, 0, 1, 0, 0, 0, 0, null, null);
    }

    /**
     * 构造内联音频提交命令，并带上语种提示。
     *
     * @param audioBytes 音频内容
     * @param fileName   文件名
     * @param engineModelType 引擎标识
     * @param languageHints 语种提示；可为 null
     * @return 命令
     */
    public static AsrSubmitCommand ofBytes(
            byte[] audioBytes, String fileName, String engineModelType, List<String> languageHints) {
        return new AsrSubmitCommand(null, audioBytes, fileName, engineModelType,
                1, 3, 0, 0, 1, 0, 0, 0, 0, null, languageHints);
    }

    /**
     * 构造 URL 提交命令，并带上语种提示。
     *
     * @param audioUrl 可下载地址
     * @param engineModelType 引擎标识
     * @param languageHints 语种提示；可为 null
     * @return 命令
     */
    public static AsrSubmitCommand ofUrl(
            String audioUrl, String engineModelType, List<String> languageHints) {
        return new AsrSubmitCommand(audioUrl, null, null, engineModelType,
                1, 3, 0, 0, 1, 0, 0, 0, 0, null, languageHints);
    }

    /** 是否为内联提交。 */
    public boolean hasInlineAudio() {
        return audioBytes != null && audioBytes.length > 0;
    }

    /** 是否有可用的 URL。 */
    public boolean hasUrl() {
        return audioUrl != null && !audioUrl.isBlank();
    }
}
