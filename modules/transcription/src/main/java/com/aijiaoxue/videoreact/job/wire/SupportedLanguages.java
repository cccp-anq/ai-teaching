package com.aijiaoxue.videoreact.job.wire;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本模块实际支持的语言标签。
 *
 * <p><b>为什么要集中定义</b>：审计发现 {@code /health} 里硬编码了 4 个语言，
 * 而校验器接受 7 个——主项目会按 {@code /health} 声明去准备请求，
 * 一旦两边不一致，就会出现"接口声明支持、提交却被 422"的矛盾。
 * 交接表里的"支持语言"也直接引用这里，保证文档与实现同源。
 *
 * <p>标签按 BCP-47 风格书写，大小写不敏感匹配。
 */
public final class SupportedLanguages {

    /** 允许调用方传入的标签（统一小写比较）。 */
    private static final Set<String> ACCEPTED = Set.of(
            "auto",      // 交给引擎自动判断；返回时用 und 表示未知
            "zh",
            "zh-cn",
            "zh-hans",
            "en",
            "en-us",
            "zh-en");    // 中英混说场景

    /**
     * 对外声明的能力列表（/health 与交接表使用）。
     *
     * <p>首版固定用 zh-CN 作为"规范写法"，其余为可接受的等价写法。
     */
    private static final List<String> DECLARED = List.of(
            "zh-CN", "zh", "zh-Hans", "en", "en-US", "zh-en", "auto");

    private SupportedLanguages() {
    }

    /**
     * 是否接受该语言标签。
     *
     * @param language 原始标签
     * @return true 表示接受
     */
    public static boolean accepts(String language) {
        return language != null && ACCEPTED.contains(language.toLowerCase(Locale.ROOT));
    }

    /**
     * 对外声明的支持语言列表。
     *
     * @return 不可变列表
     */
    public static List<String> declared() {
        return DECLARED;
    }
}
