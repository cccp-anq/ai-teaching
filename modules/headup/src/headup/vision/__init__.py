"""视觉层（FRAME-3 起）。

职责边界（plan §3/§15）：

> `QwenVisionClient` 不应知道"视频"是什么，只处理 Image 或 Image[]。

- 输入：本地图片路径（单帧）或图片路径列表（多帧窗口，FRAME-4 用）
- 输出：`FrameReading`（自洽计数 + 程序计算的 rate/coverage + 追溯信息）
- 不做：视频解码、抽帧、窗口划分、质量分档、课程聚合

`analyze.py` 仍然可用（历史兼容入口），但它只是本层的外壳。
"""

from .schema import FrameAnalyzer, FrameReading
from .qwen_client import QwenVisionClient, QwenVlAnalyzer, crop_to_data_url, prepare_image, tiled_boxes
from .validator import ObservationValidator, validate_observation

__all__ = [
    "FrameAnalyzer", "FrameReading",
    "QwenVisionClient", "QwenVlAnalyzer",
    "prepare_image", "tiled_boxes", "crop_to_data_url",
    "ObservationValidator", "validate_observation",
]
