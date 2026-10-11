"""视频层（FRAME-0 起）。

职责：把"本地课堂视频"变成"可追溯的帧文件 + 采样点清单"。
- `probe`：元数据探测（FRAME-0）
- `frame_extractor`：单点精确抽帧 / 批量抽帧（FRAME-1、FRAME-2）
- `sampling`：采样策略（采样网格只由 SamplingPolicy 生成，FFmpeg 不得自行定义）
- `workspace`：任务级临时目录生命周期（FRAME-5）

边界（plan §15）：本层不做视觉判断；视觉层不做视频解码。
"""

from .probe import ProbeError, VideoMetadata, VideoProbeService, probe_video

__all__ = ["ProbeError", "VideoMetadata", "VideoProbeService", "probe_video"]
