package com.aijiaoxue.videoreact.asr.service;

import com.aijiaoxue.videoreact.asr.client.ProviderSentence;
import com.aijiaoxue.videoreact.asr.config.AsrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 联调辅助：把提供方返回的分段明细落盘，便于事后核对字段与本地转换逻辑。
 *
 * <p>默认关闭（{@code videoreact.asr.debug.dump-raw-result}）。
 * 真实联调第一轮建议打开：识别效果出问题时，能直接看到腾讯到底返回了什么。
 *
 * <p>使用 {@link ObjectProvider} 延迟获取 Jackson，保证"没有 Jackson 也能启动、
 * 只是无法落盘"——调试功能不应该成为启动的硬依赖。
 */
@Component
public class AsrRawResultDumper {

    private static final Logger log = LoggerFactory.getLogger(AsrRawResultDumper.class);

    private final ObjectProvider<ObjectMapper> objectMapperProvider;
    private final AsrProperties.Debug debug;

    public AsrRawResultDumper(ObjectProvider<ObjectMapper> objectMapperProvider, AsrProperties properties) {
        this.objectMapperProvider = objectMapperProvider;
        this.debug = properties.debug();
    }

    /**
     * 是否已开启落盘。
     *
     * @return true 表示开启
     */
    public boolean enabled() {
        return debug != null && debug.dumpRawResult();
    }

    /**
     * 落盘某个任务的分段明细。
     *
     * @param taskId    系统任务 ID
     * @param sentences 提供方分段
     */
    public void dump(Long taskId, List<ProviderSentence> sentences) {
        if (!enabled() || sentences == null || sentences.isEmpty()) {
            return;
        }
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable();
        if (objectMapper == null) {
            log.warn("已开启原始结果落盘，但容器中没有可用的 Jackson ObjectMapper，跳过");
            return;
        }
        try {
            Path dir = Path.of(debug.dumpDir());
            Files.createDirectories(dir);
            Path file = dir.resolve("task-%d-sentences.json".formatted(taskId));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, sentences);
            log.info("已落盘提供方分段明细（{} 条）: {}", sentences.size(), file.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            // 调试功能失败不能影响主流程
            log.warn("落盘分段明细失败: {}", e.getMessage());
        }
    }
}
