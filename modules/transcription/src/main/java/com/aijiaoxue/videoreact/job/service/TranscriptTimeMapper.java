package com.aijiaoxue.videoreact.job.service;

import com.aijiaoxue.videoreact.job.wire.JobEnvelope;
import com.aijiaoxue.videoreact.asr.client.ProviderSentence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 转写结果 → 契约 payload 的映射，含<b>时间轴回映射</b>与不变量强制。
 *
 * <p>契约 §3/§4 对本模块的要求，逐条在此落实：
 * <ol>
 *   <li><b>时间必须是原媒体毫秒轴</b>。本实现提取的是整条音轨（不裁剪），
 *       因此映射为恒等：{@code mediaMs = clipMs}。类里显式声明 offset 参数，
 *       是为将来支持单段裁剪留出唯一改动点——那时的公式是
 *       {@code mediaMs = clipMs + offsetMs}，绝不能靠调用方去猜。</li>
 *   <li><b>片段必须完整落在 processedRanges 与 analysisRange 内</b>
 *       （校验脚本会强制检查）。超界片段会被<b>裁剪到范围内</b>，
 *       若裁剪后为空则丢弃并计入 warning——而不是把越界片段原样返回。</li>
 *   <li><b>text trim 后为空必须省略该片段</b>。</li>
 *   <li><b>id 在结果内唯一且稳定</b>（seg-001 递增）。</li>
 *   <li><b>置信度引擎不提供就为 null</b>，不能填 1 冒充有置信度。</li>
 *   <li>没有语音时返回空 segments，由调用方补 {@code NO_SPEECH} warning。</li>
 * </ol>
 */
public class TranscriptTimeMapper {

    private static final Logger log = LoggerFactory.getLogger(TranscriptTimeMapper.class);

    /**
     * 映射结果。
     *
     * @param segments 已回映射到原媒体轴、且已裁剪到范围内的片段
     * @param warnings 映射过程产生的告警（越界裁剪、空文本等）
     */
    public record Mapped(List<JobEnvelope.Segment> segments, List<String> warnings) {
    }

    /**
     * 把提供方分段映射成契约片段。
     *
     * @param sentences      提供方分段（时间为音频轴）
     * @param clipOffsetMs   音频轴相对原媒体轴的偏移；整条音轨时为 0
     * @param analysisStart  请求的处理范围起点（原媒体轴）
     * @param analysisEnd    请求的处理范围终点（原媒体轴）
     * @param emittedRanges  实际已处理的音频范围（原媒体轴，用于裁剪上界）
     * @return 映射结果
     */
    public Mapped map(
            List<ProviderSentence> sentences,
            long clipOffsetMs,
            long analysisStart,
            long analysisEnd,
            List<JobEnvelope.Range> emittedRanges) {

        return map(sentences,clipOffsetMs,analysisStart,analysisEnd,emittedRanges,false);
    }
    public Mapped map(List<ProviderSentence> sentences,long clipOffsetMs,long analysisStart,long analysisEnd,List<JobEnvelope.Range> emittedRanges,boolean speakerLabels) {
        List<JobEnvelope.Segment> segments = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (sentences == null || sentences.isEmpty()) {
            return new Mapped(List.of(), List.of());
        }

        // 没有任何已处理区间时不能返回任何片段：那等于把"没处理"冒充成"处理过"。
        // 之前这里用 minStart=0 / maxEnd=Long.MAX_VALUE 兜底，语义上会说成
        // "整个 analysisRange 都处理了"，与契约 §3 的 processedRanges 语义相反。
        if (emittedRanges == null || emittedRanges.isEmpty()) {
            log.warn("emittedRanges 为空：没有已处理区间，按契约不返回任何片段");
            return new Mapped(List.of(), List.of("EMPTY_PROCESSED_RANGE"));
        }

        // 裁剪上界取"处理范围 ∩ 已处理范围"：片段声称落在 analysisRange 内还不够，
        // 校验脚本要求它完整落于 processedRanges 之内
        long lowerBound = Math.max(analysisStart, minStart(emittedRanges));
        long upperBound = Math.min(analysisEnd, maxEnd(emittedRanges));
        if (upperBound <= lowerBound) {
            warnings.add("EMPTY_PROCESSED_RANGE");
            return new Mapped(List.of(), warnings);
        }

        int index = 0;
        int clipped = 0;
        int droppedBlank = 0;
        int droppedOutOfRange = 0;

        for (ProviderSentence sentence : sentences) {
            if (sentence == null) {
                continue;
            }
            String text = firstNonBlank(
                    sentence.finalSentence(), sentence.sliceSentence(), sentence.writtenText());
            if (text == null) {
                droppedBlank++;
                continue;
            }

            Long startRaw = sentence.startMs();
            Long endRaw = sentence.endMs();
            if (startRaw == null || endRaw == null) {
                // 时间戳缺失：宁可丢弃也不能猜一个位置（契约禁止猜偏移）
                droppedOutOfRange++;
                continue;
            }

            long start = startRaw + clipOffsetMs;
            long end = endRaw + clipOffsetMs;
            if (end < start) {
                long swap = start;
                start = end;
                end = swap;
            }

            // 与处理范围无关的越界：整体丢弃
            if (end <= lowerBound || start >= upperBound) {
                droppedOutOfRange++;
                continue;
            }
            // 部分越界：裁剪到范围内，而不是原样返回越界片段
            if (start < lowerBound || end > upperBound) {
                start = Math.max(start, lowerBound);
                end = Math.min(end, upperBound);
                clipped++;
            }
            if (end <= start) {
                droppedOutOfRange++;
                continue;
            }
            // 裁剪后还必须被若干"相接"的已处理区间连续覆盖：
            // 否则片段会跨过一段根本没处理的间隙，主项目的覆盖统计会因此偏大
            if (!coveredByContiguousRanges(emittedRanges, start, end)) {
                droppedOutOfRange++;
                continue;
            }

            index++;
            segments.add(new JobEnvelope.Segment(
                    "seg-%03d".formatted(index),
                    start,
                    end,
                    text,
                    // 提供方未给置信度 → null（不能填 1 冒充）
                    null,
                    // Anonymous IDs are scoped to this task, never person identities.
                    speakerLabels && sentence.speakerId()!=null && sentence.speakerId()>=0 ? "speaker-"+sentence.speakerId() : null));
        }

        if (clipped > 0) {
            warnings.add("SEGMENTS_CLIPPED_TO_RANGE:" + clipped);
        }
        if (droppedOutOfRange > 0) {
            warnings.add("SEGMENTS_DROPPED_OUT_OF_RANGE:" + droppedOutOfRange);
        }
        if (droppedBlank > 0) {
            warnings.add("SEGMENTS_DROPPED_BLANK:" + droppedBlank);
        }
        if (clipped > 0 || droppedOutOfRange > 0) {
            log.warn("片段映射发生裁剪/丢弃: clipped={}, droppedOutOfRange={}, droppedBlank={}",
                    clipped, droppedOutOfRange, droppedBlank);
        }
        return new Mapped(List.copyOf(segments), List.copyOf(warnings));
    }

    private static long minStart(List<JobEnvelope.Range> ranges) {
        return ranges.stream().mapToLong(JobEnvelope.Range::startMs).min().orElse(0L);
    }

    private static long maxEnd(List<JobEnvelope.Range> ranges) {
        return ranges.stream().mapToLong(JobEnvelope.Range::endMs).max().orElse(Long.MAX_VALUE);
    }

    /**
     * 判断一个区间是否被若干<b>相接</b>的已处理区间连续覆盖。
     *
     * <p>不能用"落在某个 processedRange 之内"来判定：那样会放过跨间隙的片段。
     * 例如已处理 {@code [0,2000)} 与 {@code [5000,6000)}，而片段是 {@code [0,6000)}，
     * 它"落在 [0,6000) ∪ … 之内"却覆盖了中间 3 秒根本没处理的区间。
     * 契约要求片段完整落于 processedRanges，主项目的覆盖统计也依赖这一点，
     * 所以这里按"消费式"匹配：从片段起点开始，逐个用起点相接的区间推进，直到覆盖到终点。
     *
     * <p>当前实现只产生单个 processedRange（整条音轨提取），因此这个方法在现有路径上
     * 恒为真；它的价值是在将来引入分段处理时仍然正确。
     *
     * @param ranges 已处理区间（可乱序，本方法自行排序）
     * @param start  片段起点
     * @param end    片段终点
     * @return true 表示被相接区间连续覆盖
     */
    static boolean coveredByContiguousRanges(List<JobEnvelope.Range> ranges, long start, long end) {
        if (ranges == null || ranges.isEmpty() || start >= end) {
            return false;
        }
        List<JobEnvelope.Range> sorted = ranges.stream()
                .sorted(java.util.Comparator.comparingLong(JobEnvelope.Range::startMs))
                .toList();

        long cursor = start;
        for (JobEnvelope.Range range : sorted) {
            if (range.endMs() <= cursor) {
                continue;
            }
            if (range.startMs() > cursor) {
                // 出现间隙：cursor 没有被任何区间接上
                return false;
            }
            cursor = range.endMs();
            if (cursor >= end) {
                return true;
            }
        }
        return cursor >= end;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    /**
     * 计算时间映射偏移（供将来支持单段裁剪时使用）。
     *
     * <p>当前实现提取整条音轨，因此恒为 0。这个方法存在的意义是把
     * "偏移从哪来"这件事显式化：一旦引入裁剪，唯一改动点就在这里，
     * 而不是散落在转换逻辑里。
     *
     * @param clipStartMs 处理片段在原媒体中的起点；0 表示整条
     * @return 原媒体毫秒 = 音频毫秒 + 返回值
     */
    public static long offsetFor(long clipStartMs) {
        return clipStartMs;
    }
}
