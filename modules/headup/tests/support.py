"""测试公共设施（仅标准库）。"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from headup import definitions, wire
from headup.config import Config

REPO_ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = REPO_ROOT.parents[1]
MODULE_FIXTURES = REPO_ROOT / "docs" / "fixtures" / "head-up-module-v0.1"
SYNTHETIC_MEDIA = MODULE_FIXTURES / "media" / "headup-synthetic-classroom-20s.mp4"

# 主项目文档包里的冻结样例（契约基准，只读参考；缺失时相关用例跳过）。
MAIN_PROJECT_FIXTURES = (PROJECT_ROOT / "docs" / "integration"
                         / "fixtures" / "classroom-analysis-v0.1")

PORTABLE_FFMPEG_CANDIDATES = (
    PROJECT_ROOT / ".work" / "tools" / "ffmpeg" / "ffmpeg.exe",
    PROJECT_ROOT / ".work" / "tools" / "ffmpeg" / "ffprobe.exe",
)


def main_project_fixtures_available() -> bool:
    return (MAIN_PROJECT_FIXTURES / "head-up-success.json").is_file()


def find_tools() -> tuple[str, str] | None:
    """找一个可用的 ffmpeg/ffprobe 组合（PATH 优先，其次便携目录）。"""
    ffmpeg = os.environ.get("FFMPEG_PATH") or shutil.which("ffmpeg")
    ffprobe = os.environ.get("FFPROBE_PATH") or shutil.which("ffprobe")
    if ffmpeg and ffprobe:
        return ffmpeg, ffprobe
    candidate = PORTABLE_FFMPEG_CANDIDATES[0]
    if candidate.is_file():
        probe = PORTABLE_FFMPEG_CANDIDATES[1]
        if probe.is_file():
            return str(candidate), str(probe)
    return None


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def probe_duration_ms(ffprobe: str, path: Path) -> int:
    completed = subprocess.run(
        [ffprobe, "-v", "error", "-show_entries", "format=duration", "-of",
         "default=nw=1:nk=1", str(path)], capture_output=True, text=True, check=False)
    return int(round(float(completed.stdout.strip()) * 1000))


def make_test_media(directory: Path, *, duration_ms: int = 20_000) -> Path:
    """用规则画面合成一段测试媒体（不含任何真实人物）。"""
    tools = find_tools()
    if tools is None:
        raise RuntimeError("缺少 ffmpeg/ffprobe")
    ffmpeg, _ffprobe = tools
    target = directory / "headup-test-media.mp4"
    if target.is_file():
        return target
    from PIL import Image, ImageDraw

    still = directory / "headup-test-still.png"
    image = Image.new("RGB", (640, 360), (235, 233, 228))
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, 640, 80], fill=(40, 60, 80))
    for row in range(4):
        for col in range(5):
            cx, cy = 64 + col * 128, 120 + row * 56
            draw.ellipse([cx - 18, cy - 18, cx + 18, cy + 18], fill=(210, 170, 140))
            draw.rectangle([cx - 26, cy + 20, cx + 26, cy + 50], fill=(90, 110, 150))
    image.save(still, format="PNG")
    seconds = duration_ms / 1000.0
    completed = subprocess.run(
        [ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
         "-loop", "1", "-framerate", "5", "-i", str(still),
         "-f", "lavfi", "-i", f"sine=frequency=300:duration={seconds:.3f}",
         "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
         "-t", f"{seconds:.3f}", "-c:a", "aac", "-shortest", "-y", str(target)],
        capture_output=True, text=True, check=False)
    if completed.returncode != 0 or not target.is_file():
        raise RuntimeError(f"ffmpeg 生成测试媒体失败: {completed.stderr[:300]}")
    return target


class LocalMediaServer:
    """把本地文件以 HTTP 提供出来，模拟主项目的受控下载地址。"""

    def __init__(self, path: Path, *, status: int = 200):
        payload = path.read_bytes() if status == 200 else b"expired"
        self.status = status
        self.payload = payload

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_GET(self) -> None:  # noqa: N802
                self.send_response(status)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, fmt: str, *args: Any) -> None:
                del fmt, args

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}/media.bin"

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


class TempConfig:
    """临时配置：本地回环 HTTP + 合成 fixture provider + 无凭证启发式判定。"""

    def __init__(self, root: Path, **overrides: Any):
        tools = find_tools()
        defaults: dict[str, Any] = dict(
            provider_id="head-up-test",
            module_version="head-up-test-0.1.0",
            bearer_tokens=("test-token",),
            db_path=str(root / "headup.sqlite3"),
            work_dir=str(root / "work"),
            allow_loopback_http=True,
            host_allow_list=("127.0.0.1",),
            vision_provider="heuristic",
            enable_fixture_provider=True,
            definition_id=definitions.DEFINITION_ID_REAL,
            definition_text=definitions.DEFINITION_TEXT_REAL,
            fixture_provider_id="head-up-demo",
            fixture_provider_version="demo-1.0",
            max_body_bytes=4096,
            # 端口 0 = 由系统分配：部分机器上 8080 被系统保留，绑定会直接报 WinError 10013
            server_port=0,
        )
        if tools is not None:
            # 本机 ffmpeg 可能在便携目录而不在 PATH：测试里显式带上，避免误判成 FFMPEG_MISSING
            defaults["ffmpeg_path"] = tools[0]
            defaults["ffprobe_path"] = tools[1]
        defaults.update(overrides)
        self.cfg = Config(**defaults)


def request_doc(*, client_request_id: str, class_session_id: str, media_id: str, sha256: str,
                duration_ms: int, source_url: str, window_ms: int = 10_000,
                output_mode: str = "COUNTS", definition_id: str | None = None,
                start_ms: int = 0, end_ms: int | None = None,
                mime_type: str = "video/mp4") -> dict[str, Any]:
    return {
        "schemaVersion": wire.SCHEMA_VERSION,
        "clientRequestId": client_request_id,
        "moduleType": wire.MODULE_TYPE,
        "classSessionId": class_session_id,
        "mediaId": media_id,
        "media": {
            "sha256": sha256,
            "durationMs": duration_ms,
            "mimeType": mime_type,
            "sourceUrl": source_url,
            "urlExpiresAt": "2099-01-01T00:00:00Z",
        },
        "analysisRange": {"startMs": start_ms, "endMs": end_ms or duration_ms},
        "parameters": {
            "windowMs": window_ms,
            "outputMode": output_mode,
            "definitionId": definition_id or definitions.DEFINITION_ID_REAL,
        },
    }


DEMO_SHA256 = "a" * 64


def demo_request_doc(**overrides: Any) -> dict[str, Any]:
    base = dict(
        client_request_id="req-head-up-001",
        class_session_id="session-demo-001",
        media_id="media-demo-001",
        sha256=DEMO_SHA256,
        duration_ms=60_000,
        source_url="https://media.example.invalid/fixture.mp4",
        window_ms=10_000,
        output_mode="COUNTS",
        definition_id=definitions.DEFINITION_ID_DEMO,
    )
    base.update(overrides)
    return request_doc(**base)


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def temp_root(prefix: str = "t") -> Path:
    """测试用临时目录（仓库内 `.t/<prefix>-<n>`，自带唯一化）。

    注意：**不要改用 ``tempfile.mkdtemp``**——在受管沙箱里 mkdtemp 创建出来的目录
    会继承受限 ACL，SQLite 直接打不开（`unable to open database file`）。
    用 ``Path.mkdir`` 自建目录则可正常读写。
    """
    root = REPO_ROOT / ".t"
    root.mkdir(parents=True, exist_ok=True)
    for index in range(1, 10_000):
        candidate = root / f"{prefix}-{index}"
        if candidate.exists():
            continue
        candidate.mkdir(parents=True)
        return candidate
    raise RuntimeError("无法在 .t/ 下创建测试目录")
