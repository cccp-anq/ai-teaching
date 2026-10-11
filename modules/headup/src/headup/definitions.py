"""观测口径（definitionId）与分母说明的权威文本。

契约要求 `payload.definitionId` 与 `payload.denominatorDefinition` 必须能被主项目登记与解释，
因此口径文本与口径 ID 必须成对维护，**不允许只改 ID 不改文本**（`Config.load()` 会做一致性检查）。

| definitionId | 用途 | 口径 |
| --- | --- | --- |
| `head-up-attendance-qwen-vl-v2` | 真实处理（本部署默认） | 匿名可见学生人数；抬头分母仍为可判断头部状态的学生人数 |
| `head-up-qwen-vl-v1` | 历史真实处理 | 代表帧内可判断抬头状态的可观测人数 = head_up + head_down |
| `head-up-demo-v1` | 合成 fixture（主项目文档包登记值） | 人工合成观测，仅用于结构联调 |

口径细节（判定规则、分母、排除对象、遮挡判无效、选帧方式）见 `docs/定义说明-head-up.md`。
"""

from __future__ import annotations

DEFINITION_ID_REAL_V1 = "head-up-qwen-vl-v1"
DEFINITION_TEXT_REAL_V1 = (
    "代表帧内可判断抬头状态的可观测人数（head_up + head_down）；"
    "侧背对镜头、被遮挡、画面过糊等无法判断者不计入；"
    "不等于班级应到人数或实到人数，也不用于出席率"
)
DEFINITION_ID_REAL = "head-up-attendance-qwen-vl-v2"
DEFINITION_TEXT_REAL = (
    "代表帧内匿名可见学生人数 visibleStudentCount；排除教师、助教、访客、屏幕人物、"
    "反射和重复影像，不做人脸或身份识别；抬头率分母仍为可判断头部状态的学生人数；"
    "可见学生人数不等于班级应到人数，跨窗出勤人数仅作稀疏截图估计"
)

DEFINITION_ID_DEMO = "head-up-demo-v1"
DEFINITION_TEXT_DEMO = "合成样例：代表帧内可判断抬头状态的可观测人数，非应到或实到人数"

DEFINITIONS: dict[str, str] = {
    DEFINITION_ID_REAL_V1: DEFINITION_TEXT_REAL_V1,
    DEFINITION_ID_REAL: DEFINITION_TEXT_REAL,
    DEFINITION_ID_DEMO: DEFINITION_TEXT_DEMO,
}


def text_for(definition_id: str) -> str | None:
    return DEFINITIONS.get(definition_id)
