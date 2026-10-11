package com.aijiaoxue.videoreact.job.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 语种提示的映射规则。
 *
 * <p>契约里 {@code language} 由主项目给，实际取值常带地区（{@code zh-CN}）；
 * 而引擎只认两字母短码。传错不是"退化到自动判断"，有的引擎会<b>直接拒掉整个任务</b>——
 * 一个大小写或连字符的差异就能让 45 分钟的转写白等一场。
 * 所以这里的原则是：能确定就收敛，不能确定就<b>什么都不传</b>，让引擎自己判断。
 */
class LanguageHintsTest {

    @Test
    @DisplayName("带地区的码收敛成短码")
    void normalisesToShortCodes() {
        assertThat(JobRunner.languageHintsFor("zh-CN")).containsExactly("zh");
        assertThat(JobRunner.languageHintsFor("zh_CN")).containsExactly("zh");
        assertThat(JobRunner.languageHintsFor("EN")).containsExactly("en");
        assertThat(JobRunner.languageHintsFor(" zh-cn ")).containsExactly("zh");
        assertThat(JobRunner.languageHintsFor("zh-Hans-CN")).containsExactly("zh");
    }

    @Test
    @DisplayName("无法映射时返回空，绝不原样透传非法值")
    void unmappableYieldsNoHint() {
        assertThat(JobRunner.languageHintsFor(null)).isEmpty();
        assertThat(JobRunner.languageHintsFor("")).isEmpty();
        assertThat(JobRunner.languageHintsFor("   ")).isEmpty();
        // 契约允许 auto / und 表示"未知"，但它们不是合法的引擎语种码
        assertThat(JobRunner.languageHintsFor("auto")).isEmpty();
        assertThat(JobRunner.languageHintsFor("und")).isEmpty();
        // 三字母码不猜（cmn 是普通话的 ISO 639-3，但百炼要 zh）
        assertThat(JobRunner.languageHintsFor("cmn")).isEmpty();
    }

    @Test
    @DisplayName("结果不可变，避免被调用方改坏")
    void resultIsImmutable() {
        List<String> hints = JobRunner.languageHintsFor("zh-CN");

        assertThatThrownBy(() -> hints.add("en"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
