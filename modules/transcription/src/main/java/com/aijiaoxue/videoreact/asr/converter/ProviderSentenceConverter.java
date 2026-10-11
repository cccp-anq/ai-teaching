package com.aijiaoxue.videoreact.asr.converter;

import com.aijiaoxue.videoreact.asr.client.ProviderSentence;
import com.aijiaoxue.videoreact.asr.domain.TranscriptSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 提供方分段（{@link ProviderSentence}）→ 系统统一 {@link TranscriptSegment} 的转换器。
 *
 * <p>纯逻辑、无外部依赖、无 IO：输入输出都是自有类型，单元测试直接构造对象即可。
 *
 * <p><b>字段取值策略</b>（不同引擎输出的语义差异需要兜底）：
 * <ol>
 *   <li>文本优先 {@code FinalSentence}（按标点分段的最终结果），
 *       为空时退回 {@code SliceSentence}，再为空退回 {@code WrittenText}；</li>
 *   <li>时间优先 {@code StartMs}/{@code EndMs}，缺失时用词级时间戳 {@code Words} 的首尾兜底；</li>
 *   <li>文本为空、或时间戳完全无法获取的分段会被丢弃并记录 warning，
 *       避免把脏数据写进 transcript_segment。</li>
 * </ol>
 */
public class ProviderSentenceConverter {

    private static final Logger log = LoggerFactory.getLogger(ProviderSentenceConverter.class);

    /**
     * 转换结果。
     *
     * @param segments 已按原始顺序重编号（segmentIndex 从 0 连续）的分段
     * @param warnings 被丢弃或被兜底修复的分段说明，用于联调排查
     */
    public record ConversionResult(List<TranscriptSegment> segments, List<String> warnings) {

        /** 拼接全部文本。 */
        public String fullText() {
            return segments.stream().map(TranscriptSegment::text).reduce("", String::concat);
        }
    }

    /**
     * 执行转换。
     *
     * @param sentences 提供方分段列表，可为 null
     * @return 转换结果；输入为空时返回空分段而不是抛异常（是否算失败由 service 判断）
     */
    public ConversionResult convert(List<ProviderSentence> sentences) {
        if (sentences == null || sentences.isEmpty()) {
            return new ConversionResult(List.of(), List.of("提供方未返回任何分段明细"));
        }

        List<TranscriptSegment> segments = new ArrayList<>(sentences.size());
        List<String> warnings = new ArrayList<>();
        int index = 0;

        for (int i = 0; i < sentences.size(); i++) {
            ProviderSentence sentence = sentences.get(i);
            if (sentence == null) {
                warnings.add("第 %d 条分段为空对象，已丢弃".formatted(i));
                continue;
            }

            String text = resolveText(sentence);
            if (text == null || text.isBlank()) {
                warnings.add("第 %d 条分段文本为空（FinalSentence/SliceSentence/WrittenText 均为空），已丢弃"
                        .formatted(i));
                continue;
            }

            long[] times = resolveTimes(sentence);
            if (times == null) {
                warnings.add("第 %d 条分段缺少可用时间戳且无词级时间可兜底，已丢弃: %s"
                        .formatted(i, abbreviate(text)));
                continue;
            }
            if (sentence.startMs() == null && sentence.endMs() == null) {
                warnings.add("第 %d 条分段 StartMs/EndMs 缺失，已用词级时间戳兜底: %s"
                        .formatted(i, abbreviate(text)));
            }
            if (times[1] < times[0]) {
                warnings.add("第 %d 条分段 EndMs(%d) 小于 StartMs(%d)，已交换".formatted(i, times[1], times[0]));
                long swap = times[0];
                times[0] = times[1];
                times[1] = swap;
            }

            segments.add(new TranscriptSegment(index++, times[0], times[1], text, sentence.speakerId()));
        }

        if (!warnings.isEmpty()) {
            log.warn("分段转换出现 {} 条告警，首条: {}", warnings.size(), warnings.get(0));
        }
        return new ConversionResult(List.copyOf(segments), List.copyOf(warnings));
    }

    private String resolveText(ProviderSentence sentence) {
        if (isNotBlank(sentence.finalSentence())) {
            return sentence.finalSentence().trim();
        }
        if (isNotBlank(sentence.sliceSentence())) {
            return sentence.sliceSentence().trim();
        }
        if (isNotBlank(sentence.writtenText())) {
            return sentence.writtenText().trim();
        }
        return null;
    }

    /**
     * 解析起止毫秒。
     *
     * @return 长度为 2 的数组 {startMs, endMs}；无法解析时返回 null
     */
    private long[] resolveTimes(ProviderSentence sentence) {
        Long start = sentence.startMs();
        Long end = sentence.endMs();
        if (start != null && end != null) {
            return new long[] {start, end};
        }

        List<ProviderSentence.ProviderWord> words = sentence.words();
        if (words != null && !words.isEmpty()) {
            Long firstStart = null;
            Long lastEnd = null;
            for (ProviderSentence.ProviderWord word : words) {
                if (word == null) {
                    continue;
                }
                if (firstStart == null && word.offsetStartMs() != null) {
                    firstStart = word.offsetStartMs();
                }
                if (word.offsetEndMs() != null) {
                    lastEnd = word.offsetEndMs();
                }
            }
            if (firstStart != null || lastEnd != null) {
                long resolvedStart = start != null ? start : (firstStart != null ? firstStart : 0L);
                long resolvedEnd = end != null ? end : (lastEnd != null ? lastEnd : resolvedStart);
                return new long[] {resolvedStart, resolvedEnd};
            }
        }

        if (start != null) {
            return new long[] {start, start};
        }
        if (end != null) {
            return new long[] {end, end};
        }
        return null;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String abbreviate(String text) {
        return text.length() <= 20 ? text : text.substring(0, 20) + "…";
    }
}
