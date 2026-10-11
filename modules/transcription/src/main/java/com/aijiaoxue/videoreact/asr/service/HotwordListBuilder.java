package com.aijiaoxue.videoreact.asr.service;

import com.aijiaoxue.videoreact.asr.config.CourseHotwordProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 课程级热词 → 腾讯云临时热词表（HotwordList）字符串的构建器。
 *
 * <p><b>为什么用 HotwordList 而不是 HotwordId</b>：官方说明「同时传入 hotword_id 和
 * hotword_list 时只有 hotword_list 生效」，且临时热词表无需先在控制台建表，
 * 适合按课程动态拼装。
 *
 * <p>官方限制（全部在此处强制）：权重取值 1-11 或 100；单个热词不超过 30 字符
 * （最多 10 个汉字）；最多 128 个词。越界项会被丢弃或截断并打 WARN，
 * 避免提交后被腾讯以 InvalidParameter 整体拒绝。
 */
public class HotwordListBuilder {

    private static final Logger log = LoggerFactory.getLogger(HotwordListBuilder.class);

    /** 官方允许的最大权重（同音同调强制替换）。 */
    private static final int MAX_WEIGHT = 100;

    /** 官方允许的普通最大权重（11 = 超级热词）。 */
    private static final int SUPER_HOTWORD_WEIGHT = 11;

    private final CourseHotwordProperties properties;

    public HotwordListBuilder(CourseHotwordProperties properties) {
        this.properties = properties;
    }

    /**
     * 为指定课程构建热词表。
     *
     * @param courseId 课程 ID，可为 null
     * @return 形如 {@code 事务|10,ACID|11} 的热词串；无热词时返回 null
     */
    public String build(Long courseId) {
        if (courseId == null) {
            return null;
        }
        List<String> configured = properties.byCourse() == null
                ? null
                : properties.byCourse().get(String.valueOf(courseId));
        if (configured == null || configured.isEmpty()) {
            log.debug("课程 {} 未配置热词，跳过 HotwordList", courseId);
            return null;
        }

        Set<String> seen = new LinkedHashSet<>();
        List<String> formatted = new ArrayList<>();
        int droppedByLength = 0;
        int droppedAsDuplicate = 0;

        for (String raw : configured) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String[] parts = raw.trim().split("\\|", 2);
            String word = parts[0].trim();
            if (word.isEmpty()) {
                continue;
            }
            if (word.length() > properties.maxWordLength()) {
                droppedByLength++;
                continue;
            }
            if (!seen.add(word)) {
                droppedAsDuplicate++;
                continue;
            }
            int weight = parseWeight(parts.length > 1 ? parts[1] : null);
            formatted.add(word + "|" + weight);
            if (formatted.size() >= properties.maxListSize()) {
                log.warn("课程 {} 热词数量超过上限 {}，多余热词被截断（官方临时热词表上限为 128）",
                        courseId, properties.maxListSize());
                break;
            }
        }

        if (droppedByLength > 0) {
            log.warn("课程 {} 有 {} 个热词超过 {} 字符上限被丢弃（官方限制单个热词不超过 30 字符 / 10 个汉字）",
                    courseId, droppedByLength, properties.maxWordLength());
        }
        if (droppedAsDuplicate > 0) {
            log.warn("课程 {} 有 {} 个重复热词被去重丢弃", courseId, droppedAsDuplicate);
        }
        if (formatted.isEmpty()) {
            return null;
        }

        String result = String.join(",", formatted);
        log.info("课程 {} 生效热词 {} 个: {}", courseId, formatted.size(), result);
        return result;
    }

    /**
     * 解析并夹紧权重。
     *
     * <p>合法值只有 1-11 与 100，其余一律夹紧到 {@code defaultWeight}，
     * 避免整表被腾讯拒绝。
     */
    private int parseWeight(String raw) {
        int fallback = clamp(properties.defaultWeight());
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int weight = Integer.parseInt(raw.trim());
            if (weight == MAX_WEIGHT) {
                return MAX_WEIGHT;
            }
            if (weight >= 1 && weight <= SUPER_HOTWORD_WEIGHT) {
                return weight;
            }
            log.warn("热词权重 {} 非法（合法值 1-11 或 100），已回退为 {}", weight, fallback);
            return fallback;
        } catch (NumberFormatException e) {
            log.warn("热词权重 '{}' 不是数字，已回退为 {}", raw, fallback);
            return fallback;
        }
    }

    private int clamp(int weight) {
        if (weight == MAX_WEIGHT) {
            return MAX_WEIGHT;
        }
        if (weight < 1 || weight > SUPER_HOTWORD_WEIGHT) {
            log.warn("默认热词权重 {} 非法，已夹紧为 10", weight);
            return 10;
        }
        return weight;
    }
}
