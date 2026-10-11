package com.aijiaoxue.videoreact.asr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * 课程级 ASR 热词配置，前缀 {@code videoreact.course-hotwords}。
 *
 * <p>课堂语音里专业术语密度高，热词直接影响字准率，因此按课程维度维护。
 *
 * <p><b>腾讯云临时热词表（HotwordList）官方限制</b>：
 * <ul>
 *   <li>格式 {@code 热词|权重}，多个热词用英文逗号分隔；</li>
 *   <li>权重取值 <b>1-11 或 100</b>：11 = 超级热词（官方建议只给必须生效的词，
 *       给多了反而影响整体字准率）；100 = 同音同调强制替换（官方<b>仅支持 8k_zh / 16k_zh</b>，
 *       大模型 2.0 引擎使用前需实测）；</li>
 *   <li>单个热词不超过 30 字符（最多 10 个汉字）；</li>
 *   <li>最多 <b>128</b> 个热词；</li>
 *   <li>同时传 {@code HotwordId}（控制台热词表）与 {@code HotwordList} 时，
 *       <b>只有 HotwordList 生效</b>——所以本模块只用 HotwordList，免去控制台建表。</li>
 * </ul>
 *
 * @param byCourse        课程 ID → 热词列表，热词写法为纯词或 {@code 词|权重}
 * @param defaultWeight   未显式标注权重时使用的权重
 * @param maxListSize     临时热词表最大条数，超出截断并告警
 * @param maxWordLength   单个热词最大字符数，超出丢弃并告警
 */
@ConfigurationProperties(prefix = "videoreact.course-hotwords")
public record CourseHotwordProperties(
        Map<String, List<String>> byCourse,
        int defaultWeight,
        int maxListSize,
        int maxWordLength) {
}
