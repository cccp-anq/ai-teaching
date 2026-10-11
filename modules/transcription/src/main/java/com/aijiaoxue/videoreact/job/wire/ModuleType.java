package com.aijiaoxue.videoreact.job.wire;

/**
 * 模块类型。契约规定凭证/服务必须与请求里的类型匹配。
 */
public enum ModuleType {

    /** 转写模块（本项目）。 */
    TRANSCRIPTION,

    /** 抬头率模块（另一项目，本项目不实现）。 */
    HEAD_UP;

    /**
     * 宽松解析，非法值返回 null 由调用方决定报错方式。
     *
     * @param value 原始字符串
     * @return 匹配的枚举；不匹配返回 null
     */
    public static ModuleType parse(String value) {
        if (value == null) {
            return null;
        }
        for (ModuleType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }
}
