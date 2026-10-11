"""FRAME-0 测试：视频探测能力。

验收（plan §9/§23）：
- 正常 MP4 可可靠读取元数据（时长/分辨率/帧率/编码）
- 损坏文件 → DECODE_FAILED + 阶段 PROBING_VIDEO
- 不存在文件 → DECODE_FAILED + 阶段 PROBING_VIDEO
- 无 FFmpeg 环境 → FFMPEG_MISSING，且**在任何模型调用之前**失败
- 无视频流 → UNSUPPORTED_MEDIA
"""

from __future__ import annotations

import json
import subprocess
import unittest
from pathlib import Path

from headup.config import Config
from headup.media import FfmpegTools, MediaError
from headup.video import ProbeError, VideoProbeService, probe_video
from headup.wire import TaskError

from support import MODULE_FIXTURES, SYNTHETIC_MEDIA, find_tools, temp_root

#: 50 秒真实课堂场景素材（用于校验时长/分辨率；20 秒人形示意图另有其文件）
SCENES_MEDIA = MODULE_FIXTURES / "media" / "headup-realclassroom-scenes-50s.mp4"


def cfg() -> Config:
    tools = find_tools()
    base = dict(media_probe_timeout_s=60)
    if tools:
        base["ffmpeg_path"], base["ffprobe_path"] = tools
    return Config(**base)


def broken_tools(_cfg: Config) -> FfmpegTools:
    return FfmpegTools(ffmpeg="definitely-not-ffmpeg", ffprobe="definitely-not-ffprobe",
                       available=False, detail="缺少外部工具: ffmpeg, ffprobe")


@unittest.skipUnless(find_tools() is not None and SYNTHETIC_MEDIA.is_file(),
                     "缺少 ffmpeg 或测试媒体")
class ProbeRealMediaTest(unittest.TestCase):
    def setUp(self) -> None:
        self.service = VideoProbeService(cfg())

    def test_reads_duration_resolution_fps_codec(self) -> None:
        meta = self.service.probe(SYNTHETIC_MEDIA)          # 20s / 640x360 / 5fps
        self.assertEqual(meta.duration_ms, 20_000)
        self.assertEqual((meta.width, meta.height), (640, 360))
        self.assertEqual(meta.fps, 5.0)
        self.assertEqual(meta.codec, "h264")
        self.assertIn("mp4", meta.container or "")
        self.assertTrue(meta.has_audio)
        self.assertEqual(meta.source_path, str(SYNTHETIC_MEDIA))

    @unittest.skipUnless(SCENES_MEDIA.is_file(), "缺少 50 秒场景素材")
    def test_reads_longer_scene_media(self) -> None:
        meta = self.service.probe(SCENES_MEDIA)             # 50s / 1280x720 / 5fps
        self.assertEqual(meta.duration_ms, 50_000)
        self.assertEqual((meta.width, meta.height), (1280, 720))
        self.assertEqual(meta.nb_frames, 250)

    def test_metadata_serializes_with_expected_keys(self) -> None:
        payload = self.service.probe(SYNTHETIC_MEDIA).to_dict()
        for key in ("durationMs", "width", "height", "fps", "codec", "container",
                    "bitRate", "nbFrames", "hasAudio", "sourcePath"):
            self.assertIn(key, payload)
        json.dumps(payload)      # 必须可 JSON 序列化（要进任务记录）

    def test_accepts_str_path(self) -> None:
        meta = probe_video(str(SYNTHETIC_MEDIA), cfg())
        self.assertEqual(meta.duration_ms, 20_000)


class ProbeFailureTest(unittest.TestCase):
    def test_missing_file_is_decode_failed_with_stage(self) -> None:
        missing = temp_root("probe") / "does-not-exist.mp4"
        with self.assertRaises(ProbeError) as ctx:
            VideoProbeService(cfg()).probe(missing)
        self.assertEqual(ctx.exception.code, "DECODE_FAILED")
        self.assertEqual(ctx.exception.stage, "PROBING_VIDEO")
        self.assertFalse(ctx.exception.retryable)

    def test_empty_file_is_rejected_before_ffprobe(self) -> None:
        empty = temp_root("probe") / "empty.mp4"
        empty.write_bytes(b"")
        with self.assertRaises(ProbeError) as ctx:
            VideoProbeService(cfg()).probe(empty)
        self.assertEqual(ctx.exception.code, "DECODE_FAILED")
        self.assertIn("空", ctx.exception.message)

    def test_directory_is_rejected(self) -> None:
        with self.assertRaises(ProbeError) as ctx:
            VideoProbeService(cfg()).probe(temp_root("probe"))
        self.assertEqual(ctx.exception.code, "DECODE_FAILED")

    def test_corrupted_file_is_decode_failed(self) -> None:
        bad = temp_root("probe") / "corrupted.mp4"
        header = bytearray(4096)
        header[4:8] = b"ftyp"          # 看起来像 MP4，实际内容损坏
        header[8:12] = b"isom"
        bad.write_bytes(bytes(header))
        with self.assertRaises(ProbeError) as ctx:
            VideoProbeService(cfg()).probe(bad)
        self.assertEqual(ctx.exception.code, "DECODE_FAILED")
        self.assertEqual(ctx.exception.stage, "PROBING_VIDEO")

    def test_missing_ffmpeg_environment_is_explicit(self) -> None:
        """环境缺失必须是 FFMPEG_MISSING，而不是被当成媒体错误。"""
        with self.assertRaises(ProbeError) as ctx:
            VideoProbeService(cfg(), tool_locator=broken_tools).probe(SYNTHETIC_MEDIA)
        self.assertEqual(ctx.exception.code, "FFMPEG_MISSING")
        self.assertEqual(ctx.exception.stage, "PROBING_VIDEO")

    def test_probe_error_is_a_task_error(self) -> None:
        """探测错误必须能被任务层当普通 TaskError 捕获（契约 §5 兼容）。"""
        with self.assertRaises(TaskError) as ctx:
            VideoProbeService(cfg()).probe(temp_root("probe") / "nope.mp4")
        self.assertIsInstance(ctx.exception, MediaError)
        self.assertEqual(ctx.exception.code, "DECODE_FAILED")

    @unittest.skipUnless(find_tools() is not None, "缺少 ffmpeg")
    def test_audio_only_media_is_unsupported(self) -> None:
        tools = find_tools()
        assert tools is not None
        target = temp_root("probe") / "audio-only.m4a"
        completed = subprocess.run(
            [tools[0], "-nostdin", "-hide_banner", "-loglevel", "error",
             "-f", "lavfi", "-i", "sine=frequency=300:duration=2", "-c:a", "aac",
             "-y", str(target)], capture_output=True, text=True, check=False)
        if completed.returncode != 0 or not target.is_file():
            self.skipTest("ffmpeg 无法生成纯音频测试文件")
        with self.assertRaises(ProbeError) as ctx:
            VideoProbeService(cfg()).probe(target)
        self.assertEqual(ctx.exception.code, "UNSUPPORTED_MEDIA")
        self.assertEqual(ctx.exception.stage, "PROBING_VIDEO")


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
