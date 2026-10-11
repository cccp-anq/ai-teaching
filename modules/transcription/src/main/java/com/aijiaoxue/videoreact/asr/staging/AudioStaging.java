package com.aijiaoxue.videoreact.asr.staging;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 音频暂存：把抽好的音轨变成一个<b>提供方能自己下载的公网 URL</b>。
 *
 * <p><b>为什么需要这一层</b>：契约 §3 要求模块自己下载媒体并核验 sha256，
 * 因此最干净的做法是把音频字节<b>内联</b>交给识别引擎，字节不再离开模块。
 * 但这条路的可用性由模型决定：阿里云百炼的官方 FAQ 明确写着
 * 「是否支持Base64编码方式的音频？<b>不支持</b>…不支持二进制流，也不支持直接识别本地文件」，
 * 而支持内联的那一档模型时长上限只有 5 分钟（见 {@code docs/阿里云模型选型.md}）。
 * 课堂录播动辄 45 分钟以上，只能用「12 小时档」，而那一档<b>只认公网 URL</b>。
 *
 * <p>于是这里必须做一个取舍，并且只能选一个：
 * <ol>
 *   <li><b>暂存到自己控制的存储</b>（本接口的正式实现）：上传音轨 → 生成预签名 URL → 提交
 *       → 任务终态后删除。契约里 {@code sourceUrl} 与 {@code urlExpiresAt} 两个字段、
 *       以及幂等指纹<b>故意排除</b>它们的设计，正是为这条路预留的。</li>
 *   <li><b>把 {@code sourceUrl} 直接转给提供方</b>：零成本，但要求该地址本身就是公网匿名可读的。
 *       这种情况下模块<b>从未核验过提供方实际读到的那份字节</b>，
 *       契约的 {@code MEDIA_HASH_MISMATCH} 分支会退化成摆设——
 *       我们能证明"我们下载到的那份是对的"，却无法证明"引擎读的是同一份"。</li>
 * </ol>
 * 当前实现是第 2 条的<b>显式缺席</b>：只有 {@link InlineAudioStaging}，
 * 它会对任何 URL 暂存请求明确抛错，而不是悄悄回退。
 * 这样"选了阿里云但没配暂存"会当场暴露成一条可读的错误，
 * 而不是变成一次安静的核验绕过。
 *
 * <p><b>生命周期</b>：暂存句柄必须在<b>轮询到终态之后</b>才关闭。
 * 引擎可能在排队期间才去拉取音频，提前删除会让任务以一个含糊的下载失败告终。
 */
public interface AudioStaging {

    /**
     * 暂存音频并返回可提交的地址。
     *
     * @param file        已抽好的音轨文件
     * @param contentType 内容类型，如 {@code audio/wav}
     * @param fileName    交给提供方的文件名提示
     * @param lifetime    暂存有效期，应覆盖"排队 + 识别 + 轮询"的总时长
     * @return 暂存句柄；调用方必须用 try-with-resources 关闭
     * @throws AudioStagingException 暂存不可用或上传失败
     */
    StagedAudio stage(Path file, String contentType, String fileName, Duration lifetime);

    /**
     * 本实现是否真的能产出 URL。
     *
     * <p>用于启动自检：选了 URL-only 的提供方却只有内联实现时，
     * 应当在<b>启动时</b>就报错，而不是等第一个任务跑到提交阶段才失败。
     *
     * @return true 表示 {@link #stage} 可用
     */
    boolean urlStagingAvailable();

    /**
     * 人类可读的实现说明，用于 {@code /health} 与排障。
     *
     * @return 说明文本
     */
    String description();

    /** 按持久化对象键删除暂存音频。实现必须校验键属于本部署前缀。 */
    default boolean delete(String objectKey) { return true; }

    /**
     * 暂存结果：一个提供方可下载的地址，加一个清理动作。
     */
    interface StagedAudio extends AutoCloseable {

        /**
         * 交给提供方的地址。
         *
         * <p>必须是 HTTPS 预签名 URL，<b>不要</b>是原始 {@code sourceUrl}。
         *
         * @return 地址
         */
        String url();

        /** 地址失效时刻，写入契约的 {@code urlExpiresAt}。 */
        java.time.Instant expiresAt();

        /** 可持久化且不含凭证的对象键，用于进程重启后继续清理。 */
        default String objectKey() { return ""; }

        /**
         * 删除暂存对象。必须可重复调用且不抛异常——
         * 它会在 finally 里执行，抛异常会掩盖真正的失败原因。
         */
        @Override
        void close();
    }
}
