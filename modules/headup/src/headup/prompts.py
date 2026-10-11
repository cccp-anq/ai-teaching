"""提示词台账（v0.3 起匿名学生计数口径为默认）。

规则（用户 v0.2 分轮提示词 · 全局执行原则 8/9）：提示词版本必须可追溯，
正式结果里必须记录 `prompt_version`；不允许"只有数值、查不到口径"。

| prompt_version | 口径 | 状态 |
| --- | --- | --- |
| `headup-prompt-1` | 旧版：只有 UP/DOWN/UNKNOWN 三分类，未禁止推测 | 保留兼容，可显式启用 |
| `headup-strict-v0.2` | 严格人物观察口径：只依据直接可见的视觉证据 | 保留兼容，可显式启用 |
| `headup-student-attendance-v0.3` | 匿名学生计数，并排除教师、访客、媒介人物、反射和重复影像 | 默认 |
| `headup-student-attendance-review-multi-v0.3` | 同口径的三帧复核，一名学生只计一次 | 复核专用 |

严格口径的四条硬约束：

1. 只见后脑 / 背对镜头 ⇒ **UNKNOWN**；
2. 不得根据身体朝向、座位位置、周围同学状态或常识推测 UP / DOWN；
3. 宁可增加 UNKNOWN，也不猜（"宁可输出无法可靠判断，也不给一个猜出来的数字"）；
4. 输出必须是自洽的计数：`up + down + unknown = visible`，`up + down = evaluable`。
"""

from __future__ import annotations

SYSTEM_PROMPT = (
    "你是课堂视频画面的匿名观测标注员。只做学生人数与学生头部朝向的计数，"
    "不做人脸或身份识别，不推测姓名、出勤身份或课堂评价。只输出 JSON。"
)

#: 旧版提示词（保留：主项目/历史结果可按存档口径复现）
USER_PROMPT_V1 = """观察这张教室画面（可能是拼图分块），统计可见人物并按头部朝向分类。

判定规则：
1. head_up：面部朝向讲台/正前方/黑板/屏幕，能看出抬头注视姿态。
2. head_down：明显低头看桌面、纸笔、手机，或趴伏。
3. unknown：背对镜头、侧脸且姿态不可辨别、被前排或设备遮挡、画面过糊或过小到无法判断。
4. 只统计画面中真实存在的人；不要根据课桌、衣物、投影内容推测人数。
5. legible_ratio = (head_up + head_down) / 画面中所有可见人物数量；画面中没有人时填 0。

只输出如下 JSON（不要解释、不要多余文字）：
{"head_up": 0, "head_down": 0, "unknown": 0, "legible_ratio": 0.0, "confidence": 0.0, "note": ""}
"""

#: 严格观察口径提示词（默认）
USER_PROMPT_STRICT_V02 = """观察这张教室画面（可能是拼图分块），对画面中每一个可见人物做头部朝向观测。

【最重要的原则】只根据**直接可见的视觉证据**判断。
如果某人的头部方向无法可靠判断，必须计入 UNKNOWN。
- **仅看到后脑（后脑勺、头发顶部、背影）时必须计入 UNKNOWN**；
- 不得根据身体朝向、座位位置、周围同学的状态、课堂常识来推测某人是 UP 还是 DOWN；
- **宁可增加 UNKNOWN，也不要猜**。猜错的精确数字比诚实的不确定更有害。

判定规则：
1. head_up_count：能直接看到面部/眼睛朝向前方、讲台、黑板或屏幕。
2. head_down_count：能直接看到明显低头看桌面/纸笔/手机，或趴伏（可见头顶或额头朝下）。
3. unknown_count：以下情况**必须**计入 UNKNOWN，不得猜成 up 或 down：
   - 只见后脑、背影、头发顶部，看不到面部；
   - 只见侧脸轮廓，无法判断抬头还是低头；
   - 被前排人体、屏幕、课桌、设备遮挡；
   - 画面过糊、过小、只露出局部躯干或手臂；
   - 画面边缘只剩半个人、无法确认姿态。
4. 只统计画面中真实存在的人；不要根据课桌、衣物、投影内容推测人数。
5. evaluable_count = head_up_count + head_down_count（可判断者）；
   visible_count = head_up_count + head_down_count + unknown_count（画面中所有可见人物）。
   **必须满足这两个等式**，否则请重新数一遍再输出。
6. 画面中没有人时，五个计数都为 0。

只输出如下 JSON（不要解释、不要多余文字）：
{"visible_count": 0, "evaluable_count": 0, "head_up_count": 0, "head_down_count": 0, "unknown_count": 0, "confidence": 0.0, "note": ""}
"""

USER_PROMPT_ATTENDANCE_V03 = """观察这张教室画面（可能是拼图分块），匿名统计可见学生，并对每一名可见学生做头部朝向观测。

【人数口径】
- visible_count 只统计当前画面中可确认存在的学生；不做人脸或身份识别。
- 排除讲台教师、助教、访客、屏幕/海报中的人物、镜面反射和同一人的重复影像。
- 不因空座、书包、衣物或被完全遮挡的座位推测有人。

【头部口径】只根据直接可见的视觉证据判断。
- head_up_count：能直接看到学生面部/眼睛朝向前方、讲台、黑板或屏幕。
- head_down_count：能直接看到学生明显低头看桌面/纸笔/手机，或趴伏。
- unknown_count：学生存在但头部方向无法可靠判断，包括后脑、背影、遮挡、模糊、局部躯干。
- 宁可增加 UNKNOWN，也不要猜；不得根据座位、身体方向、同学状态或课堂常识推测。
- 必须满足：head_up_count + head_down_count + unknown_count = visible_count；
  evaluable_count = head_up_count + head_down_count。
- 画面中没有可见学生时，五个计数都为 0。

只输出如下 JSON（不要解释、不要多余文字）：
{"visible_count": 0, "evaluable_count": 0, "head_up_count": 0, "head_down_count": 0, "unknown_count": 0, "confidence": 0.0, "note": ""}
"""

PROMPT_VERSION_V1 = "headup-prompt-1"
PROMPT_VERSION_STRICT_V02 = "headup-strict-v0.2"
PROMPT_VERSION_STRICT = "headup-student-attendance-v0.3"
#: 三帧复核（**一次多图请求**）使用的提示词版本（FRAME-4 / 用户决策③）
PROMPT_VERSION_REVIEW = "headup-student-attendance-review-multi-v0.3"
DEFAULT_PROMPT_VERSION = PROMPT_VERSION_STRICT

#: 复核请求：三张图共同产生**一个** centerTimestamp 的观测（禁止三图各调一次再平均）
USER_PROMPT_REVIEW_MULTI = """下面三张图片来自**同一节课的同一个时刻附近**（相隔约 0.5 秒），
顺序为：t-0.5s、t、t+0.5s。请把它们当作同一个观察窗口，输出**一组**计数。

【最重要的原则】只根据**直接可见的视觉证据**判断。
- **仅看到后脑（后脑勺、头发顶部、背影）时必须计入 UNKNOWN**；
- 不得根据身体朝向、座位位置、周围同学的状态、课堂常识来推测某人是 UP 还是 DOWN；
- **宁可增加 UNKNOWN，也不要猜**；
- 不要因为"前后两帧看起来在抬头"就把某一帧看不清的人推断成抬头。

计数规则（三帧合并成一组数字，**不是每帧各出一组**）：
1. visible_count：三帧中可确认存在的学生人数（同一学生在多帧出现只算一次）；
   排除教师、助教、访客、屏幕/海报人物、反射和重复影像，不推测完全遮挡的座位。
2. head_up_count / head_down_count：**至少有一帧能直接看清其头部朝向**才计入；
   只看清一帧、其余帧看不清，也按看清的那一帧计入。
3. unknown_count：三帧都看不清朝向的人（背对、遮挡、模糊、只剩局部）。
4. 必须满足：head_up_count + head_down_count + unknown_count = visible_count；
   evaluable_count = head_up_count + head_down_count。
5. 三帧仍然都看不清时，如实把这些人放进 unknown_count，不要凑数。

只输出如下 JSON（不要解释、不要多余文字）：
{"visible_count": 0, "evaluable_count": 0, "head_up_count": 0, "head_down_count": 0, "unknown_count": 0, "confidence": 0.0, "note": ""}
"""

PROMPTS: dict[str, str] = {
    PROMPT_VERSION_V1: USER_PROMPT_V1,
    PROMPT_VERSION_STRICT_V02: USER_PROMPT_STRICT_V02,
    PROMPT_VERSION_STRICT: USER_PROMPT_ATTENDANCE_V03,
    PROMPT_VERSION_REVIEW: USER_PROMPT_REVIEW_MULTI,
}


def user_prompt(prompt_version: str) -> str:
    try:
        return PROMPTS[prompt_version]
    except KeyError as exc:  # pragma: no cover - 配置错误
        raise ValueError(
            f"未知的 prompt_version={prompt_version!r}；可用: {', '.join(sorted(PROMPTS))}"
        ) from exc


def is_strict(prompt_version: str) -> bool:
    """严格口径 = 要求 visible/evaluable 自洽计数的那一版（复核提示词也属严格口径）。"""
    return prompt_version in (PROMPT_VERSION_STRICT_V02, PROMPT_VERSION_STRICT, PROMPT_VERSION_REVIEW)
