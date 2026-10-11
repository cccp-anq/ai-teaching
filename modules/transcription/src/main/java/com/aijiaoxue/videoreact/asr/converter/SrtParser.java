package com.aijiaoxue.videoreact.asr.converter;

import com.aijiaoxue.videoreact.asr.client.ProviderSentence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SRT 字幕解析器。
 *
 * <p><b>为什么需要它</b>：实测发现 TokenHub 的<b>异步</b>接口
 * （{@code async_create} + {@code GET /tasks/{id}}）在成功时只返回一个
 * {@code output.subtitle_url}，<b>不返回 {@code sentences}/{@code text}</b>；
 * 而<b>同步</b>接口 {@code sync_transcribe} 才返回结构化 {@code sentences}。
 * 我们走的是异步通道（避免阻塞 HTTP 线程），所以时间戳只能从 SRT 里解析。
 *
 * <p>实测的 SRT 样例（{@code hello_world_female2.wav}）：
 * <pre>
 * 1
 * 00:00:00,462 --&gt; 00:00:01,187
 * Hello world,
 *
 * 2
 * 00:00:01,187 --&gt; 00:00:03,362
 * 这里是阿里巴巴语音实验室。
 * </pre>
 *
 * <p>纯逻辑、无 IO，便于单测覆盖各家 SRT 的方言差异。
 */
public final class SrtParser {

    private static final Logger log = LoggerFactory.getLogger(SrtParser.class);

    /**
     * 时间行：{@code 00:00:00,462 --> 00:00:01,187}。
     *
     * <p>宽松匹配，因为不同生成器的写法不完全一致：
     * <ul>
     *   <li>毫秒分隔符可能是 {@code ,} 或 {@code .}；</li>
     *   <li>小时部分可能省略（{@code 00:00,462}）；</li>
     *   <li>毫秒部分可能缺失；</li>
     *   <li>箭头两侧空格数量不定。</li>
     * </ul>
     */
    private static final Pattern TIME_LINE = Pattern.compile(
            "^(\\d{1,2}:)?(\\d{1,2}):(\\d{1,2})(?:[,.](\\d{1,3}))?\\s*-->\\s*"
                    + "(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{1,2})(?:[,.](\\d{1,3}))?.*$");

    /** 纯数字的序号行。 */
    private static final Pattern INDEX_LINE = Pattern.compile("^\\d+$");

    private SrtParser() {
    }

    /**
     * 解析 SRT 文本为分段列表。
     *
     * @param srt 完整 SRT 内容；可为 null
     * @return 分段列表，按出现顺序；解析不出任何分段时返回空列表
     */
    public static List<ProviderSentence> parse(String srt) {
        if (srt == null || srt.isBlank()) {
            return List.of();
        }
        // 去 BOM、统一换行、逐块处理
        String normalized = srt.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        String[] blocks = normalized.split("\n\\s*\n");

        List<ProviderSentence> result = new ArrayList<>();
        int malformed = 0;

        for (String block : blocks) {
            if (block.isBlank()) {
                continue;
            }
            String[] lines = block.split("\n");
            int timeLineIndex = -1;
            long[] times = null;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                Matcher matcher = TIME_LINE.matcher(line);
                if (matcher.matches()) {
                    times = toMillis(matcher);
                    timeLineIndex = i;
                    break;
                }
                // 序号行之外还没遇到时间行，说明这一块结构异常
                if (!INDEX_LINE.matcher(line).matches() && i == 0) {
                    break;
                }
            }

            if (times == null || timeLineIndex < 0) {
                malformed++;
                continue;
            }

            // 时间行之后的所有非空行拼成正文（SRT 允许多行）
            StringBuilder text = new StringBuilder();
            for (int i = timeLineIndex + 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(line);
            }
            if (text.isEmpty()) {
                malformed++;
                continue;
            }

            result.add(new ProviderSentence(text.toString(), null, null, times[0], times[1], null, List.of()));
        }

        if (malformed > 0) {
            log.warn("SRT 解析：{} 个块结构异常被跳过（共解析出 {} 条）", malformed, result.size());
        }
        return List.copyOf(result);
    }

    /**
     * 把正则各捕获组换算成毫秒。
     *
     * @param matcher 已匹配 {@link #TIME_LINE} 的匹配器
     * @return {startMs, endMs}
     */
    private static long[] toMillis(Matcher matcher) {
        long start = combine(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
        long end = combine(matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8));
        return new long[] {start, end};
    }

    /**
     * 组合成毫秒。小时段允许缺失；毫秒不足三位时按"高位补零"解释
     * （{@code ,4} 是 400ms 而不是 4ms——这是 SRT 的通行约定）。
     */
    private static long combine(String hours, String minutes, String seconds, String millis) {
        long h = hours == null ? 0 : Long.parseLong(stripColon(hours));
        long m = Long.parseLong(minutes);
        long s = Long.parseLong(seconds);
        long ms = 0;
        if (millis != null) {
            ms = Long.parseLong(millis);
            if (millis.length() == 1) {
                ms *= 100;
            } else if (millis.length() == 2) {
                ms *= 10;
            }
        }
        return ((h * 60 + m) * 60 + s) * 1000 + ms;
    }

    private static String stripColon(String value) {
        return value.endsWith(":") ? value.substring(0, value.length() - 1) : value;
    }
}
