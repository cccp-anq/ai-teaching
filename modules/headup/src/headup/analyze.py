"""视觉判定的兼容入口（历史 API）。

FRAME-3 起真正的实现在 `headup.vision`：
- 数据结构 → `headup.vision.schema`
- Qwen 客户端与图像预处理 → `headup.vision.qwen_client`
- 无凭证降级 / 离线启发式 → 仍留在本文件（它们不是"真实视觉客户端"）

本文件保留全部旧导入名，因此既有调用方与测试不需要改动。
"""

from __future__ import annotations

import logging
from pathlib import Path

from .config import Config
from .counts import WindowCounts, make_counts
from .vision.qwen_client import (PROMPT_VERSION, SYSTEM_PROMPT, USER_PROMPT_STRICT_V02,
                                 USER_PROMPT_V1, QwenVisionClient, QwenVlAnalyzer,
                                 crop_to_data_url, prepare_image, tiled_boxes)
from .vision.schema import FrameAnalyzer, FrameReading
from .wire import TaskError

LOG = logging.getLogger("headup.analyze")

__all__ = [
    "PROMPT_VERSION", "SYSTEM_PROMPT", "USER_PROMPT_V1", "USER_PROMPT_STRICT_V02",
    "FrameAnalyzer", "FrameReading", "QwenVlAnalyzer", "QwenVisionClient",
    "prepare_image", "tiled_boxes", "crop_to_data_url",
    "UnavailableAnalyzer", "HeuristicAnalyzer", "build_analyzer",
]


# --------------------------------------------------------------------------
# 无凭证时的显式降级：绝不编造人数
# --------------------------------------------------------------------------

class UnavailableAnalyzer:
    """没有可用视觉判定能力时使用：不输出任何人数，窗口显式降级。"""

    name = "unavailable"

    def __init__(self, reason: str):
        self.reason = reason
        self.version = f"{PROMPT_VERSION}+unavailable"

    def analyze(self, image_path: Path) -> FrameReading:
        del image_path
        empty = make_counts(visible_count=0, head_up_count=0, head_down_count=0, unknown_count=0,
                            strict=False)
        return FrameReading(head_up=0, head_down=0, unknown=0, legible_ratio=0.0,
                            confidence=0.0, analysis_notes=[self.reason], raw="", counts=empty)


_HEURISTIC_NOTICE = (
    "离线灰度启发式（HEADUP_HEURISTIC=1）：不做姿态判定，只按亮度/清晰度决定可判断性，"
    "所有窗口输出 LOW_QUALITY、不含人数。仅用于在无凭证环境下验证契约与覆盖逻辑。"
)


class HeuristicAnalyzer:
    """只回答“这一帧能不能看”，不回答“几个人抬头”。

    契约要求噪声/遮挡帧显式降级而不是猜数，因此本分析器恒返回 LOW_QUALITY 原因；
    只有当调用方显式配置 `HEADUP_HEURISTIC_SYNTHETIC=1` 时才会返回合成计数（用于端到端演练，
    且 providerId 会保持 fixture 身份，避免被误当真实观测）。
    """

    name = "heuristic"

    def __init__(self, *, synthetic_counts: bool = False, seed: int = 0):
        self.synthetic_counts = synthetic_counts
        self.seed = seed
        self.version = f"{PROMPT_VERSION}+heuristic"

    def analyze(self, image_path: Path) -> FrameReading:
        note = _HEURISTIC_NOTICE
        if self.synthetic_counts:
            # 确定性伪数据：同一路径 + seed 稳定，绝不是真实识别结果。
            digest = 0
            for char in image_path.name:
                digest = (digest * 131 + ord(char)) % 1_000_003
            digest = (digest + self.seed) % 1_000_003
            denominator = 30 + digest % 11
            head_up = int(denominator * (0.35 + (digest % 40) / 100.0))
            head_up = max(0, min(denominator, head_up))
            counts = make_counts(visible_count=denominator + 2, head_up_count=head_up,
                                 head_down_count=denominator - head_up, unknown_count=2,
                                 confidence=0.5, strict=False)
            return FrameReading(head_up=head_up, head_down=denominator - head_up, unknown=2,
                                legible_ratio=counts.coverage or 0.0, confidence=0.5,
                                analysis_notes=[note + "（合成计数，仅供演练）"], raw="",
                                counts=counts)
        empty = make_counts(visible_count=0, head_up_count=0, head_down_count=0, unknown_count=0,
                            strict=False)
        return FrameReading(head_up=0, head_down=0, unknown=0, legible_ratio=0.0, confidence=0.0,
                            analysis_notes=[note], raw="", counts=empty)


def build_analyzer(cfg: Config) -> FrameAnalyzer:
    provider = (cfg.vision_provider or "").strip().lower()
    if provider in ("qwen-vl", "qwen", "dashscope"):
        if cfg.vision_api_key:
            try:
                import PIL  # noqa: F401
            except ImportError as exc:
                raise TaskError("VISION_DEPENDENCY_MISSING",
                                "Qwen 视觉识别需要 Pillow；请按 requirements.txt 安装已锁定版本") from exc
            return QwenVlAnalyzer(cfg)
        return UnavailableAnalyzer("未配置 DASHSCOPE_API_KEY：本部署无法进行真实抬头判定")
    if provider in ("heuristic", "offline"):
        return HeuristicAnalyzer(synthetic_counts=cfg.heuristic_synthetic)
    raise TaskError("PROCESSING_FAILED", f"未知的视觉判定 provider: {provider!r}")
