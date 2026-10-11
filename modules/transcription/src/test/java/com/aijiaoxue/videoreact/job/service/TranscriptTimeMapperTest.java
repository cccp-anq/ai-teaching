package com.aijiaoxue.videoreact.job.service;

import com.aijiaoxue.videoreact.asr.client.ProviderSentence;
import com.aijiaoxue.videoreact.job.wire.JobEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间映射与裁剪测试，对应契约 §3/§4 里最强调的正确性要求。
 *
 * <p>契约 §1：「允许模块内部提取音轨或做单段裁剪，但<b>返回时间必须已经映射回原媒体</b>」；
 * 转写规范 §4 给了唯一的算例：「模块内部若裁取原媒体 {@code [10000,20000)}，
 * 识别得到片段 {@code [0,2000)}，返回必须为 {@code [10000,12000)}，
 * 不能让主项目再次猜偏移」。
 *
 * <p>契约 §3 对 Segment 的硬约束（{@code verify_contract.py} 会逐条校验）：
 * <ul>
 *   <li>{@code 0 <= startMs < endMs <= durationMs}，且<b>完整落在 processedRanges 之内</b>；</li>
 *   <li>{@code text} trim 后非空；{@code id} 结果内唯一；按 startMs 排序；</li>
 *   <li>{@code confidence} 引擎不提供就是 null，<b>不能填 1 装作有置信度</b>；</li>
 *   <li>无语音时 segments 为空，{@code NO_SPEECH} 由调用方补。</li>
 * </ul>
 *
 * <p>全部用例都是纯内存计算：不下载媒体、不调用 ASR、不需要 ffmpeg。
 */
class TranscriptTimeMapperTest {

    /** 契约 fixture 的媒体时长量级。 */
    private static final long MEDIA_END_MS = 60_000L;

    /** 整条媒体都处理完的 processedRanges（当前 JobRunner 总是这样构造）。 */
    private static final List<JobEnvelope.Range> FULL_PROCESSED =
            List.of(new JobEnvelope.Range(0L, MEDIA_END_MS));

    private final TranscriptTimeMapper mapper = new TranscriptTimeMapper();

    /**
     * 正常映射：时间原样保留，id 从 seg-001 递增。
     */
    @Test
    void normal_mapping_keeps_times_and_numbers_ids_incrementally() {
        TranscriptTimeMapper.Mapped mapped = mapFull(List.of(
                sentence("第一句", 0L, 2000L),
                sentence("第二句", 2000L, 5000L),
                sentence("第三句", 5000L, 9000L)));

        assertThat(mapped.warnings()).isEmpty();
        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::id)
                .containsExactly("seg-001", "seg-002", "seg-003");
        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::text)
                .containsExactly("第一句", "第二句", "第三句");
        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::startMs)
                .containsExactly(0L, 2000L, 5000L);
        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::endMs)
                .containsExactly(2000L, 5000L, 9000L);
        assertThat(mapped.segments()).allSatisfy(segment -> insideMedia(segment));
    }

    /**
     * {@code clipOffsetMs} 非 0 时，提供方时间必须加上偏移。
     *
     * <p>这是"将来支持单段裁剪"的<b>唯一改动点</b>：本实现提取整条音轨，偏移恒为 0；
     * 但一旦引入裁剪，映射公式必须是 {@code 原媒体毫秒 = 音频毫秒 + offsetMs}，
     * 而不是靠调用方去猜。
     */
    @Test
    void clip_offset_is_added_to_provider_times() {
        // 契约 §4 的官方算例：裁取原媒体 [10000,20000)，识别得到 [0,2000)，返回必须是 [10000,12000)
        TranscriptTimeMapper.Mapped mapped = mapper.map(
                List.of(sentence("裁剪后的第一句", 0L, 2000L),
                        sentence("裁剪后的第二句", 2000L, 4500L)),
                TranscriptTimeMapper.offsetFor(10_000L),
                10_000L, 20_000L,
                List.of(new JobEnvelope.Range(10_000L, 20_000L)));

        assertThat(TranscriptTimeMapper.offsetFor(10_000L)).isEqualTo(10_000L);
        assertThat(TranscriptTimeMapper.offsetFor(0L)).isZero();
        assertThat(mapped.warnings()).isEmpty();
        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::startMs)
                .containsExactly(10_000L, 12_000L);
        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::endMs)
                .containsExactly(12_000L, 14_500L);
        assertThat(mapped.segments()).allSatisfy(segment -> {
            assertThat(segment.startMs()).isGreaterThanOrEqualTo(10_000L);
            assertThat(segment.endMs()).isLessThanOrEqualTo(20_000L);
        });
    }

    /**
     * 越界片段被裁剪到 {@code processedRanges ∩ analysisRange} 之内，
     * <b>不会</b>把越界片段原样返回给主项目。
     */
    @Test
    void segments_crossing_the_boundary_are_clipped_into_range() {
        TranscriptTimeMapper.Mapped mapped = mapFull(List.of(
                sentence("跨过终点", 59_000L, 61_500L),
                sentence("跨过起点", -500L, 1500L)));

        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::startMs)
                .containsExactly(59_000L, 0L);
        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::endMs)
                .containsExactly(MEDIA_END_MS, 1500L);
        assertThat(mapped.warnings()).contains("SEGMENTS_CLIPPED_TO_RANGE:2");
        assertThat(mapped.segments()).allSatisfy(segment -> insideMedia(segment));
    }

    /**
     * 裁剪上界是"已处理范围"而不是"请求范围"：请求 [0,60000) 但只处理了 [0,30000) 时，
     * 片段不得超出已处理部分（否则 {@code verify_contract.py} 会判 "outside processed"）。
     */
    @Test
    void clipping_uses_processed_ranges_not_only_analysis_range() {
        TranscriptTimeMapper.Mapped mapped = mapper.map(
                List.of(sentence("跨过已处理终点", 29_000L, 40_000L)),
                0L, 0L, MEDIA_END_MS,
                List.of(new JobEnvelope.Range(0L, 30_000L)));

        assertThat(mapped.segments()).hasSize(1);
        assertThat(mapped.segments().get(0).startMs()).isEqualTo(29_000L);
        assertThat(mapped.segments().get(0).endMs()).isEqualTo(30_000L);
        assertThat(mapped.segments().get(0).text()).isEqualTo("跨过已处理终点");
        assertThat(mapped.warnings()).contains("SEGMENTS_CLIPPED_TO_RANGE:1");
    }

    /**
     * 完全在范围外的片段被丢弃，并产生 warning（不能默默少给片段）。
     */
    @Test
    void segments_fully_outside_the_range_are_dropped_with_warning() {
        TranscriptTimeMapper.Mapped mapped = mapFull(List.of(
                sentence("范围之前", -5000L, -1000L),
                sentence("范围内", 1000L, 2000L),
                sentence("范围之后", MEDIA_END_MS, MEDIA_END_MS + 2000L)));

        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::text)
                .containsExactly("范围内");
        // id 只在真正返回的片段上递增，不因丢弃而留空洞
        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::id)
                .containsExactly("seg-001");
        assertThat(mapped.warnings()).contains("SEGMENTS_DROPPED_OUT_OF_RANGE:2");
    }

    /**
     * 空文本/空白文本的片段被丢弃（契约：trim 后为空应省略该片段）。
     */
    @Test
    void blank_text_segments_are_dropped_with_warning() {
        TranscriptTimeMapper.Mapped mapped = mapFull(List.of(
                new ProviderSentence(" ", "  ", null, 0L, 1000L, null, null),
                new ProviderSentence(null, null, null, 1000L, 2000L, null, null),
                sentence("有内容", 2000L, 3000L)));

        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::text)
                .containsExactly("有内容");
        assertThat(mapped.warnings()).contains("SEGMENTS_DROPPED_BLANK:2");
    }

    /**
     * 文本取值优先级：FinalSentence → SliceSentence，并统一 trim。
     */
    @Test
    void text_falls_back_to_slice_sentence_and_is_trimmed() {
        TranscriptTimeMapper.Mapped mapped = mapFull(List.of(
                new ProviderSentence(null, "  逐字切片文本  ", null, 0L, 1000L, null, null),
                new ProviderSentence("最终文本", "逐字切片文本", null, 1000L, 2000L, null, null)));

        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::text)
                .containsExactly("逐字切片文本", "最终文本");
    }

    /**
     * 结束时间小于开始时间时被交换，而不是产生负长度片段。
     */
    @Test
    void reversed_times_are_swapped_instead_of_producing_negative_length() {
        TranscriptTimeMapper.Mapped mapped = mapFull(List.of(sentence("时间倒序", 5000L, 3000L)));

        assertThat(mapped.warnings()).isEmpty();
        assertThat(mapped.segments()).hasSize(1);
        JobEnvelope.Segment segment = mapped.segments().get(0);
        assertThat(segment.startMs()).isEqualTo(3000L);
        assertThat(segment.endMs()).isEqualTo(5000L);
        assertThat(segment.endMs()).isGreaterThan(segment.startMs());

        // 交换后仍然整体在范围外的，按"范围外"丢弃而不是返回
        TranscriptTimeMapper.Mapped dropped = mapFull(List.of(
                sentence("交换后仍在范围外", MEDIA_END_MS + 2000L, MEDIA_END_MS + 1000L)));
        assertThat(dropped.segments()).isEmpty();
        assertThat(dropped.warnings()).contains("SEGMENTS_DROPPED_OUT_OF_RANGE:1");
    }

    /**
     * 置信度与说话人标签必须是 null：引擎不给就不能伪造。
     *
     * <p>提供方即使给了 {@code speakerId} 与词级时间戳，首版也不支持说话人标签，
     * 更不能因为"看起来有信息"就把 confidence 填成 1。
     */
    @Test
    void confidence_and_speaker_label_are_never_fabricated() {
        ProviderSentence withSpeakerAndWords = new ProviderSentence(
                "带说话人与词级时间戳", null, null, 1000L, 3000L, 7L,
                List.of(new ProviderSentence.ProviderWord("带", 1000L, 1200L),
                        new ProviderSentence.ProviderWord("说话人", 1200L, 1600L)));

        TranscriptTimeMapper.Mapped mapped = mapFull(List.of(withSpeakerAndWords));

        assertThat(mapped.segments()).hasSize(1);
        assertThat(mapped.segments().get(0).confidence()).isNull();
        assertThat(mapped.segments().get(0).speakerLabel()).isNull();
    }

    /**
     * 没有语音时返回空 segments，{@code NO_SPEECH} 由调用方补——mapper 不自己冒充。
     *
     * <p>契约要求"合法静音"与"解码失败"必须可区分，因此这里只返回事实。
     */
    @Test
    void no_speech_returns_empty_segments_for_the_caller_to_flag() {
        TranscriptTimeMapper.Mapped empty = mapFull(List.of());

        assertThat(empty.segments()).isEmpty();
        assertThat(empty.warnings()).isEmpty();
        assertThat(empty.warnings()).doesNotContain("NO_SPEECH");

        TranscriptTimeMapper.Mapped nullInput =
                mapper.map(null, 0L, 0L, MEDIA_END_MS, FULL_PROCESSED);
        assertThat(nullInput.segments()).isEmpty();
        assertThat(nullInput.warnings()).isEmpty();
    }

    /**
     * 时间戳缺失的片段被丢弃，而不是猜一个位置（契约：检测到时间映射不可恢复不猜偏移）。
     */
    @Test
    void segments_without_timestamps_are_dropped_rather_than_guessed() {
        TranscriptTimeMapper.Mapped mapped = mapFull(List.of(
                new ProviderSentence("没有时间戳", null, null, null, null, null, null),
                new ProviderSentence("只有起点", null, null, 1000L, null, null, null),
                sentence("两个时间都有", 1000L, 2000L)));

        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::text)
                .containsExactly("两个时间都有");
        assertThat(mapped.warnings()).contains("SEGMENTS_DROPPED_OUT_OF_RANGE:2");
    }

    /**
     * 相邻/重叠的片段<b>不合并造句</b>（契约：允许语音重叠，不合并）。
     */
    @Test
    void overlapping_segments_are_kept_separate() {
        TranscriptTimeMapper.Mapped mapped = mapFull(List.of(
                sentence("前一句", 1000L, 3000L),
                sentence("后一句（与前一句重叠）", 2000L, 4000L)));

        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::text)
                .containsExactly("前一句", "后一句（与前一句重叠）");
        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::startMs)
                .containsExactly(1000L, 2000L);
        assertThat(mapped.segments()).extracting(JobEnvelope.Segment::endMs)
                .containsExactly(3000L, 4000L);
    }

    /**
     * 多种问题同时出现时，warning 的内容与顺序稳定可复现。
     */
    @Test
    void warnings_are_reported_in_stable_order() {
        TranscriptTimeMapper.Mapped mapped = mapFull(List.of(
                new ProviderSentence(" ", null, null, 0L, 1000L, null, null),
                sentence("范围外", 70_000L, 71_000L),
                sentence("跨过终点", 59_500L, 60_500L)));

        assertThat(mapped.warnings()).containsExactly(
                "SEGMENTS_CLIPPED_TO_RANGE:1",
                "SEGMENTS_DROPPED_OUT_OF_RANGE:1",
                "SEGMENTS_DROPPED_BLANK:1");
    }

    /**
     * 列表里的 null 元素被跳过，不抛异常（提供方报文偶尔会给出空行）。
     */
    @Test
    void null_entries_in_sentence_list_are_skipped() {
        TranscriptTimeMapper.Mapped mapped = mapFull(java.util.Arrays.asList(
                null,
                sentence("有效片段", 1000L, 2000L),
                null));

        assertThat(mapped.segments()).hasSize(1);
        assertThat(mapped.segments().get(0).text()).isEqualTo("有效片段");
    }

    /**
     * 已处理范围为空时不出片段，并给出 EMPTY_PROCESSED_RANGE。
     */
    @Test
    void empty_processed_range_yields_no_segments() {
        TranscriptTimeMapper.Mapped mapped = mapper.map(
                List.of(sentence("任何文本", 1000L, 2000L)),
                0L, 0L, MEDIA_END_MS,
                List.of(new JobEnvelope.Range(MEDIA_END_MS, MEDIA_END_MS)));

        assertThat(mapped.segments()).isEmpty();
        assertThat(mapped.warnings()).containsExactly("EMPTY_PROCESSED_RANGE");
    }

    /**
     * processedRanges 不连续时，落在<b>空洞里</b>或<b>跨越空洞</b>的片段必须被丢弃，
     * 只有被"相接区间连续覆盖"的片段才允许返回。
     *
     * <p>依据是 {@code verify_contract.py} 第 96 行：
     * {@code sum(overlap(span, p) for p in processed) == span[1] - span[0]}，
     * 即每个片段必须<b>完整落在 processedRanges 之内</b>。仅按"最小起点~最大终点"
     * 的凸包裁剪是不够的：[2000,4000) 落在 [0,2000)∪[5000,6000) 的凸包内，
     * 却覆盖了 3 秒根本没处理的音频，会让主项目的"转写片段覆盖"统计偏大。
     *
     * <p>当前 JobRunner 只传单个连续区间（整条音轨提取），所以空洞场景只在
     * 将来引入分块处理时才出现；本用例把规则钉住，避免退化成凸包裁剪。
     */
    @Test
    void segments_not_covered_by_contiguous_processed_ranges_are_dropped() {
        List<JobEnvelope.Range> disjoint = List.of(new JobEnvelope.Range(0L, 2000L),
                new JobEnvelope.Range(5000L, 6000L));

        // 落在空洞里：不返回
        TranscriptTimeMapper.Mapped inGap = mapper.map(
                List.of(sentence("落在空洞里", 3000L, 4000L)), 0L, 0L, 6000L, disjoint);
        assertThat(inGap.segments()).isEmpty();
        assertThat(inGap.warnings()).contains("SEGMENTS_DROPPED_OUT_OF_RANGE:1");

        // 跨越空洞：即使一头落在已处理区间内也不返回
        TranscriptTimeMapper.Mapped acrossGap = mapper.map(
                List.of(sentence("跨越空洞", 1000L, 5500L)), 0L, 0L, 6000L, disjoint);
        assertThat(acrossGap.segments()).isEmpty();
        assertThat(acrossGap.warnings()).contains("SEGMENTS_DROPPED_OUT_OF_RANGE:1");

        // 完整落在某一个已处理区间内：返回
        TranscriptTimeMapper.Mapped insideOne = mapper.map(
                List.of(sentence("落在区间内", 5000L, 6000L)), 0L, 0L, 6000L, disjoint);
        assertThat(insideOne.segments()).hasSize(1);
        assertThat(insideOne.warnings()).isEmpty();
    }

    /**
     * 相接的多个 processedRange 视为连续：片段跨越两个相接区间是合法的。
     */
    @Test
    void touching_processed_ranges_are_treated_as_continuous() {
        List<JobEnvelope.Range> touching = List.of(new JobEnvelope.Range(0L, 2000L),
                new JobEnvelope.Range(2000L, 5000L));

        TranscriptTimeMapper.Mapped mapped = mapper.map(
                List.of(sentence("跨越相界", 1000L, 4000L)), 0L, 0L, 6000L, touching);

        assertThat(mapped.segments()).hasSize(1);
        assertThat(mapped.segments().get(0).startMs()).isEqualTo(1000L);
        assertThat(mapped.segments().get(0).endMs()).isEqualTo(4000L);
        assertThat(mapped.warnings()).isEmpty();
    }

    // ==================================================================
    //  夹具
    // ==================================================================

    /** 默认场景：整条媒体都处理完，偏移为 0。 */
    private TranscriptTimeMapper.Mapped mapFull(List<ProviderSentence> sentences) {
        return mapper.map(sentences, TranscriptTimeMapper.offsetFor(0L), 0L, MEDIA_END_MS, FULL_PROCESSED);
    }

    /** 只给文本与时间的提供方片段（不说话人、不给词级时间戳）。 */
    private static ProviderSentence sentence(String text, Long startMs, Long endMs) {
        return new ProviderSentence(text, null, null, startMs, endMs, null, null);
    }

    /** 断言片段本身是合法的契约片段（非空文本、正长度、已 trim）。 */
    private static void insideMedia(JobEnvelope.Segment segment) {
        assertThat(segment.id()).isNotBlank();
        assertThat(segment.text()).isNotBlank().isEqualTo(segment.text().strip());
        assertThat(segment.startMs()).isGreaterThanOrEqualTo(0L);
        assertThat(segment.startMs()).isLessThan(segment.endMs());
        assertThat(segment.endMs()).isLessThanOrEqualTo(MEDIA_END_MS);
    }

    @org.junit.jupiter.api.Test void anonymous_speaker_labels_require_persisted_opt_in() {
        var sentence=new com.aijiaoxue.videoreact.asr.client.ProviderSentence("为什么",null,null,0L,1000L,7L,java.util.List.of());
        var mapper=new TranscriptTimeMapper();var ranges=java.util.List.of(new com.aijiaoxue.videoreact.job.wire.JobEnvelope.Range(0,2000));
        assertThat(mapper.map(java.util.List.of(sentence),0,0,2000,ranges,true).segments().get(0).speakerLabel()).isEqualTo("speaker-7");
        assertThat(mapper.map(java.util.List.of(sentence),0,0,2000,ranges,false).segments().get(0).speakerLabel()).isNull();
    }
}
