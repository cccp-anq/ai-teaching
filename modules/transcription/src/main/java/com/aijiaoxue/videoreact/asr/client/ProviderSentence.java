package com.aijiaoxue.videoreact.asr.client;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 提供方输出的一个分段，作为 client 与 converter 之间的<b>类型化交换格式</b>。
 *
 * <p>刻意不用 JSON 字符串做中间格式：腾讯云 SDK 已经把 {@code ResultDetail} 解析成
 * 对象数组，再序列化回 JSON 只会引入额外的失败点（字段名、类型宽松度、编解码库版本），
 * 却不能带来任何好处。用自有 record 交换后：
 * <ul>
 *   <li>converter 是纯逻辑，单元测试直接构造对象即可，不需要解析报文；</li>
 *   <li>更换提供方时，新 client 只要映射出同样的结构，converter 完全不用改；</li>
 *   <li>不再依赖任何 JSON 库的版本细节。</li>
 * </ul>
 *
 * @param finalSentence  按标点分段的最终文本（ResTextFormat=3 时的主要取值）
 * @param sliceSentence  逐字切片文本，FinalSentence 为空时的兜底
 * @param writtenText    口语转书面语文本（增值功能，未开启时为空）
 * @param startMs        起始毫秒
 * @param endMs          结束毫秒
 * @param speakerId      说话人 ID，未开启说话人分离时为 null
 * @param words          词级时间戳；第一版不落库，仅用于时间戳兜底
 */
public record ProviderSentence(
        @JsonProperty("FinalSentence") String finalSentence,
        @JsonProperty("SliceSentence") String sliceSentence,
        @JsonProperty("WrittenText") String writtenText,
        @JsonProperty("StartMs") Long startMs,
        @JsonProperty("EndMs") Long endMs,
        @JsonProperty("SpeakerId") Long speakerId,
        @JsonProperty("Words") List<ProviderWord> words) {

    /**
     * 词级时间戳。
     *
     * @param word         词文本
     * @param offsetStartMs 词起始毫秒
     * @param offsetEndMs   词结束毫秒
     */
    public record ProviderWord(
            @JsonProperty("Word") String word,
            @JsonProperty("OffsetStartMs") Long offsetStartMs,
            @JsonProperty("OffsetEndMs") Long offsetEndMs) {
    }
}
