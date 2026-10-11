package com.aijiaoxue.videoreact.asr.staging;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 只支持内联提交的暂存实现：任何 URL 暂存请求都明确失败。
 *
 * <p>这不是"还没写完"，而是<b>当前状态的真实表达</b>。
 * 换成"把 sourceUrl 直接返回"会看起来更"能跑"，但那样一旦生效，
 * 模块就会在不核验提供方读到什么的前提下声称完成了 sha256 校验——
 * 这种错误不会报错，只会让契约里那条校验悄悄失效。宁可让它响亮地失败。
 *
 * <p>何时会被换掉：主项目确认媒体存储形态后，
 * 换成基于对象存储（预签名 URL）的实现，并把 {@link #urlStagingAvailable()}
 * 改为 true。届时 {@code JobRunner} 不需要任何改动。
 */
public class InlineAudioStaging implements AudioStaging {

    /** 指导使用者该做什么的固定文案，避免每个调用点自己拼。 */
    public static final String NOT_CONFIGURED_HINT =
            "当前没有可用的音频暂存实现（AudioStaging）。"
                    + "阿里云非实时语音识别的长音频档只接受公网 URL，不接受内联音频字节"
                    + "（官方 FAQ：「不支持Base64编码方式的音频…不支持二进制流，也不支持直接识别本地文件」）。"
                    + "可选做法：① 配置一个基于对象存储的 AudioStaging 实现（推荐，"
                    + "这样音频字节仍由本模块上传，与契约的 sha256 核验语义一致）；"
                    + "② 换用支持内联音频的提供方（ASR_PROVIDER=wand 等）。"
                    + "注意：直接把 sourceUrl 转给提供方会让 sha256 核验失去意义，"
                    + "本模块不会默默这么做。";

    @Override
    public StagedAudio stage(Path file, String contentType, String fileName, Duration lifetime) {
        throw new AudioStagingException("AudioStagingNotConfigured", NOT_CONFIGURED_HINT, false);
    }

    @Override
    public boolean urlStagingAvailable() {
        return false;
    }

    @Override
    public String description() {
        return "inline-only（不支持暂存为公网 URL）";
    }
}
