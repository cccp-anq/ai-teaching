package com.aijiaoxue.videoreact.job.service;

import com.aijiaoxue.videoreact.job.wire.JobRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;

/**
 * 提交幂等指纹计算。
 *
 * <p>契约 §3 明确要求：「重复请求比较身份、hash、duration、range、parameters，
 * <b>不把临时 sourceUrl/urlExpiresAt 纳入业务幂等 hash</b>；
 * 同键仅更新下载凭据且尚未终态可续处理，终态不可变」。
 *
 * <p>因此 ：
 * <ul>
 *   <li>参与指纹：schemaVersion、clientRequestId、moduleType、classSessionId、mediaId、
 *       media.sha256、media.durationMs、media.mimeType、analysisRange、parameters；</li>
 *   <li><b>不参与</b>：media.sourceUrl、media.urlExpiresAt——主项目续期下载链接后
 *       会用<b>同一个 clientRequestId</b> 重提，此时必须命中同一个任务，
 *       而不是被判成冲突。</li>
 * </ul>
 *
 * <p>序列化方式与契约 §4 的 canonical 定义一致：解析后字段<b>递归按 key 排序</b>、
 * <b>数组保留顺序</b>，忽略 JSON 空白与 key 顺序。
 */
public class RequestFingerprint {

    private final ObjectMapper objectMapper;

    public RequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 计算业务指纹。
     *
     * @param request 已通过校验的请求
     * @return 小写 64 位十六进制 SHA-256
     */
    public String of(JobRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", request.schemaVersion());
        root.put("clientRequestId", request.clientRequestId());
        root.put("moduleType", request.moduleType());
        root.put("classSessionId", request.classSessionId());
        root.put("mediaId", request.mediaId());

        JobRequest.MediaRef media = request.media();
        if (media != null) {
            ObjectNode mediaNode = root.putObject("media");
            // 刻意不写入 sourceUrl / urlExpiresAt：它们是可续期的下载凭据
            mediaNode.put("sha256", media.sha256());
            mediaNode.put("durationMs", media.durationMs());
            mediaNode.put("mimeType", media.mimeType());
        }

        JobRequest.AnalysisRange range = request.analysisRange();
        if (range != null) {
            ObjectNode rangeNode = root.putObject("analysisRange");
            rangeNode.put("startMs", range.startMs());
            rangeNode.put("endMs", range.endMs());
        }

        JobRequest.TranscriptionParameters parameters = request.parameters();
        if (parameters != null) {
            ObjectNode paramNode = root.putObject("parameters");
            paramNode.put("language", parameters.language());
            paramNode.put("enableSpeakerLabels", parameters.enableSpeakerLabels());
        }

        return sha256(canonicalize(root));
    }

    /**
     * 递归按 key 排序生成规范 JSON 文本。
     *
     * <p>数组顺序保留：转写参数里没有数组，但契约对 payload 的规范化同样要求保留数组顺序，
     * 所以这里统一按同一规则实现，避免两处规则不一致。
     *
     * @param node 任意 JSON 节点
     * @return 规范文本
     */
    public static String canonicalize(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(node, sb);
        return sb.toString();
    }

    private static void writeCanonical(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
            return;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.propertyNames().iterator(); it.hasNext(); ) {
                names.add(it.next());
            }
            Collections.sort(names);
            sb.append('{');
            boolean first = true;
            for (String name : names) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(name)).append(':');
                writeCanonical(node.get(name), sb);
            }
            sb.append('}');
            return;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            sb.append('[');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeCanonical(array.get(i), sb);
            }
            sb.append(']');
            return;
        }
        if (node.isString()) {
            // isString()/stringValue() 是 Jackson 3 里 isTextual()/asText() 的替代，
            // 语义完全一致（isTextual 就是 isString 的别名）
            sb.append(quote(node.stringValue()));
            return;
        }
        sb.append(node.toString());
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
