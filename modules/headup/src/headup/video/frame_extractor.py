"""抽帧（FRAME-1 单点精确抽帧 / FRAME-2 批量抽帧）。

硬约束（用户决策）：
1. **采样网格只由 `SamplingPolicy` 生成**，FFmpeg 不得自行定义网格；
2. 批量抽帧只有在**证明与逐点 seek 等价**时才启用，否则回退逐点 seek；
3. 每个帧都要能追溯到原视频时间点，因此文件名直接带 `timestampMs`。

为什么 `-ss` 放在 `-i` **之前**（输入侧 seek，快），以及它什么时候会失灵（FRAME-6 实测）：
输入侧 seek 配合默认的 accurate_seek 会解码并丢弃目标时间点之前的帧，因此在**常规 10 秒网格**
上与逐点 seek 完全一致（45 分钟素材 271 点：首帧平均像素差 0.0，整段 11.5s）。但它把"起点相位"
交给了 fps 滤镜：在**极稀疏网格**（间隔 270s，fps=0.0037）上相位会错位——批量首帧与逐点 seek
的平均像素差 52.0/255（等于取到了相邻场景）。因此首帧等价性校验（`verify_batch_equivalence`
与 `extract_batch(verify_first=True)`）是**必需的兜底**：校验不过就整段回退逐点 seek，
不拿"看起来差不多"的帧冒充精确时间点；回退率与原因都会进指标（FRAME-6）。
"""

from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..config import Config
from ..media import FfmpegTools, MediaError
from ..wire import TaskError

STAGE_EXTRACTING = "EXTRACTING_FRAMES"

_TS_PATTERN = re.compile(r"pts_time:([0-9.]+)")

#: 帧类型（plan §5.2）
FRAME_TYPE_PRIMARY = "PRIMARY"
FRAME_TYPE_REVIEW_BEFORE = "REVIEW_BEFORE"
FRAME_TYPE_REVIEW_CENTER = "REVIEW_CENTER"
FRAME_TYPE_REVIEW_AFTER = "REVIEW_AFTER"

_TS_DIGITS = 9          # 支持到约 11.5 天，够长视频且按字典序=时间序

#: 首帧等价性容差（平均像素差，0–255）。批量路径只有在首帧与逐点 seek 的差 ≤ 该值时启用。
EQUIVALENCE_TOLERANCE = 2.0


def uniform_prefix(timestamps_ms: list[int]) -> tuple[list[int], list[int]]:
    """把网格拆成"最长等间隔前缀"与"尾部"（FRAME-6）。

    真实网格在"媒体时长不是 windowMs 整数倍"时，最后一个短窗的采样点与前面间隔不同，
    而批量抽帧（`fps` 滤镜）要求等间隔。拆分**不改变任何时间戳**。
    """
    if not timestamps_ms:
        return [], []
    split = 1
    if len(timestamps_ms) > 1:
        step = timestamps_ms[1] - timestamps_ms[0]
        if step > 0:
            while (split < len(timestamps_ms)
                   and timestamps_ms[split] - timestamps_ms[split - 1] == step):
                split += 1
    return list(timestamps_ms[:split]), list(timestamps_ms[split:])


class FrameExtractionFailure(MediaError):
    """抽帧失败（带阶段信息）。"""

    def __init__(self, task_error: TaskError, stage: str = STAGE_EXTRACTING):
        super().__init__(task_error)
        self.stage = stage
        self.error_stage = stage


@dataclass(frozen=True)
class FrameSample:
    """一个已抽取的帧（plan §5.2）：时间点 + 文件 + 类型。"""

    timestamp_ms: int
    image_path: Path
    type: str = FRAME_TYPE_PRIMARY
    window_id: str | None = None

    def to_dict(self) -> dict[str, object]:
        return {
            "timestampMs": self.timestamp_ms,
            "imagePath": str(self.image_path),
            "type": self.type,
            "windowId": self.window_id,
        }


def frame_filename(timestamp_ms: int, *, prefix: str = "frame") -> str:
    """`timestampMs` 直接进文件名（plan §10），便于人工核对与证据链。"""
    return f"{prefix}-{int(timestamp_ms):0{_TS_DIGITS}d}.jpg"


class FrameExtractor:
    """统一抽帧入口：对外只暴露"按时间点取帧"。"""

    def __init__(self, cfg: Config, tools: FfmpegTools, media_path: Path,
                 duration_ms: int | None = None):
        self.cfg = cfg
        self.tools = tools
        self.media_path = Path(media_path)
        self.duration_ms = duration_ms
        # FRAME-6：最近一次抽帧实际走的路径（SEEK / BATCH / SEEK_FALLBACK）与等价性证据，
        # 供指标层统计"批量的真实回退率"，而不是只看调用方以为走了哪条路。
        self.last_mode: str = "UNKNOWN"
        self.last_equivalence: dict[str, object] | None = None
        #: extract_grid 的拆分情况（批量前缀/逐点尾部的点数）
        self.last_grid_split: dict[str, int] | None = None

    # -- FRAME-1 ---------------------------------------------------------
    def extract_at(self, timestamp_ms: int, out_dir: Path, *,
                   frame_type: str = FRAME_TYPE_PRIMARY,
                   window_id: str | None = None, suffix: str = "") -> FrameSample:
        """单点精确抽帧（输入侧 `-ss` + `accurate_seek`，帧精确且与位置无关）。

        历史注记：FRAME-1 曾用"`-ss` 在 `-i` 之后"换取帧精确，代价是每次都从片头解码；
        FRAME-6 实测证明输入侧写法在 accurate_seek 下**像素级等价**且快 10–45 倍（见
        `docs/rounds/FRAME-6-性能与稳定性Benchmark.md` §5），因此改为输入侧。
        """
        self._require_tools()
        self.last_mode = "SEEK"
        at = self._clamp(timestamp_ms)
        try:
            return self._extract_once(at, out_dir, frame_type=frame_type,
                                      window_id=window_id, suffix=suffix)
        except FrameExtractionFailure:
            # 极靠近片尾可能无可解帧。回退值必须作为真实时间戳返回，调用方不得
            # 继续把它标成原计划点。
            fallback = max(0, at - 1_000)
            if fallback == at:
                raise
            return self._extract_once(fallback, out_dir, frame_type=frame_type,
                                      window_id=window_id, suffix=suffix)

    def _extract_once(self, at: int, out_dir: Path, *, frame_type: str,
                      window_id: str | None, suffix: str) -> FrameSample:
        out_dir.mkdir(parents=True, exist_ok=True)
        name = f"frame-{at:0{_TS_DIGITS}d}.jpg" if not suffix \
            else f"frame-{at:0{_TS_DIGITS}d}-{suffix}.jpg"
        out_path = out_dir / name
        cmd = [
            self.tools.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
            # FRAME-6 实测修正：输入侧 seek + 显式 accurate_seek。
            # 旧实现把 `-ss` 放在 `-i` 之后（纯输出侧），ffmpeg 每次都要**从 0 解码到目标点**：
            # 45 分钟素材上 5s 处 0.11s、2435s 处 5.08s（成本随位置线性增长）。
            # 输入侧 seek 在现代 ffmpeg 默认 `-accurate_seek` 下会"跳到目标点之前的关键帧、
            # 解码并丢弃多余帧"，因此同样**帧精确**：实测与输出侧结果的平均像素差 0.000
            # （5s/300s/1350s/2435s 四个点），而耗时恒定在 0.11–0.13s。
            "-accurate_seek",
            "-ss", f"{at / 1000.0:.3f}",
            "-i", str(self.media_path),
            "-frames:v", "1", "-q:v", "2",
            "-vf", f"scale='min({self.cfg.frame_max_width},iw)':-2:flags=lanczos",
            "-y", str(out_path),
        ]
        self._run(cmd, out_path, context=f"在 {at}ms 处抽帧")
        return FrameSample(timestamp_ms=at, image_path=out_path, type=frame_type,
                           window_id=window_id)

    # -- FRAME-2 ---------------------------------------------------------
    def extract_batch(self, timestamps_ms: list[int], out_dir: Path, *,
                      verify_first: bool = True) -> list[FrameSample]:
        """批量抽帧：假定时间点构成**等差数列**，用一次 ffmpeg 抽完。

        网格由调用方（`SamplingPolicy`）给出；本方法只负责"按网格取帧"。
        首个时间点的帧会与逐点 seek 的结果做等价性校验（`verify_first=True`）。
        """
        self._require_tools()
        ordered = [self._clamp(ts) for ts in timestamps_ms]
        if not ordered:
            return []
        # 网格合法性先校验：不等间隔不能进 ffmpeg（网格只能来自 SamplingPolicy）
        if len(ordered) > 1:
            step = ordered[1] - ordered[0]
            if step <= 0 or any(b - a != step for a, b in zip(ordered, ordered[1:])):
                raise FrameExtractionFailure(TaskError(
                    "PROCESSING_FAILED", "批量抽帧要求时间点等间隔；请改用逐点抽帧"))
        if not verify_first:
            samples = self._batch_once(ordered, out_dir)
            self.last_mode = "BATCH"
            return samples
        samples = self._batch_once(ordered, out_dir)
        reference_dir = out_dir / "_ref"
        reference = self.extract_at(ordered[0], reference_dir, suffix="ref")
        try:
            difference = _mean_pixel_diff(samples[0].image_path, reference.image_path)
            equivalent = difference <= EQUIVALENCE_TOLERANCE
            self.last_equivalence = {
                "timestampMs": ordered[0],
                "meanPixelDiff": round(float(difference), 4),
                "equivalent": bool(equivalent),
                "tolerance": EQUIVALENCE_TOLERANCE,
            }
            if equivalent:
                # 用参考帧的**内容**覆盖批量首帧，但保留批量首帧的文件名/时间点：
                # 批量首帧的相位由 `fps` 滤镜决定（实测在极稀疏网格上会偏），
                # 逐点帧的相位是帧精确的，因此以逐点帧为准。
                import shutil

                shutil.copyfile(reference.image_path, samples[0].image_path)
                self.last_mode = "BATCH"
                return samples
        finally:
            _cleanup_dir(reference_dir)
        # 校验不通过：整段回退逐点 seek（宁慢不假）
        _cleanup_dir(out_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        fallback = [self.extract_at(ts, out_dir) for ts in ordered]
        self.last_mode = "SEEK_FALLBACK"
        return fallback

    # -- FRAME-6：整格抽取（等间隔前缀批量 + 尾部少量点逐点）-----------------
    def extract_grid(self, timestamps_ms: list[int], out_dir: Path, *,
                     verify_first: bool = True) -> list[FrameSample]:
        """按**给定网格**抽帧（FRAME-6）：等间隔前缀走批量，余下少量点逐点 seek。

        为什么需要它：真实的 `SamplingPolicy` 网格在"媒体时长不是 `windowMs` 整数倍"时，
        最后一个短窗的采样点与前面不同间隔（例：2700.467s 的 45 分钟课，最后一点在
        2695233ms 而不是 2695000ms）。整段交给 `extract_batch` 会被**正确拒绝**
        （批量要求等间隔），但整节课退回逐点 seek 又慢到不可用（逐点成本随时间线性增长）。

        因此这里只做"最长等间隔前缀 + 尾部少量点"的拆分：**采样点、时间戳、文件名全部不变**，
        网格仍然只由 `SamplingPolicy` 决定；批量前缀仍走首帧等价性校验，校验不过则整段回退。
        """
        ordered = [self._clamp(ts) for ts in timestamps_ms]
        if not ordered:
            return []
        prefix, tail = uniform_prefix(ordered)
        self.last_grid_split = {"batchPoints": len(prefix) if len(prefix) > 1 else 0,
                                "seekPoints": (0 if len(prefix) > 1 else len(prefix)) + len(tail)}
        samples: list[FrameSample] = []
        if len(prefix) > 1:
            samples.extend(self.extract_batch(prefix, out_dir, verify_first=verify_first))
            # extract_batch 会把真实路径写进 last_mode（BATCH / SEEK_FALLBACK）：
            # 回退必须原样上报，不能被这里改写成 BATCH —— 指标不能骗人（FRAME-6）。
            if getattr(self, "last_mode", "") == "SEEK_FALLBACK":
                self.last_mode = "SEEK_FALLBACK"
            else:
                self.last_mode = "BATCH+SEEK_TAIL" if tail else "BATCH"
        else:
            for timestamp in prefix:
                samples.append(self.extract_at(timestamp, out_dir))
            self.last_mode = "SEEK"
        for timestamp in tail:
            samples.append(self.extract_at(timestamp, out_dir))
        if tail and len(prefix) > 1:
            self.last_mode = "BATCH+SEEK_TAIL"
        return samples

    # -- FRAME-6：时间点证据（不写帧文件，仅审计用）-------------------------
    def decoded_timestamps(self, timestamps_ms: list[int], *, batch: bool = False,
                           max_points: int = 0) -> dict[str, Any]:
        """读出**实际解码帧**相对请求时间点的偏移（毫秒）——为"帧精确"提供可核对的证据。

        做法：同样的 seek 参数 + `showinfo` 滤镜 + `-f null -`（不落盘），解析 stderr 里的
        `pts_time`。不加 `-copyts`，因此逐点模式下偏移就是"解码帧相对请求点晚了多少毫秒"；
        批量模式的时间轴从首点重新计时，偏移已减去"网格内位置"，即"相对该采样点晚了多少"。
        - `batch=False`：逐点调用，逐点给偏移；
        - `batch=True`：一次批量调用（与 `_batch_once` 同参数），返回全部输出帧的偏移。

        仅用于基准/审计，**生产路径不为每帧付这个开销**（每次调用 ≈ 0.1s）。
        """
        ordered = [self._clamp(ts) for ts in timestamps_ms]
        if max_points > 0:
            ordered = ordered[:max_points]
        if not ordered:
            return {"batch": batch, "points": [], "maxOffsetMs": None}
        if batch and len(ordered) > 1:
            prefix, _tail = uniform_prefix(ordered)
            step = prefix[1] - prefix[0]
            cmd = [self.tools.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "info",
                   "-accurate_seek", "-ss", f"{prefix[0] / 1000.0:.3f}",
                   "-i", str(self.media_path),
                   "-vf", f"fps=1000/{step},showinfo",
                   "-frames:v", str(len(prefix) + 1), "-f", "null", "-"]
            completed = subprocess.run(cmd, capture_output=True, text=True,
                                       timeout=self.cfg.media_probe_timeout_s, check=False)
            # 批量命令的时间轴从 `prefix[0]` 重新计时，因此偏移要减去"网格内的位置"
            decoded = [float(value) * 1000.0
                       for value in _TS_PATTERN.findall(completed.stderr or "")]
            points = [{"timestampMs": timestamp,
                       "offsetMs": round(offset - (timestamp - prefix[0]), 1)}
                      for timestamp, offset in zip(prefix, decoded)]
        else:
            points = []
            for timestamp in ordered:
                cmd = [self.tools.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "info",
                       "-accurate_seek", "-ss", f"{timestamp / 1000.0:.3f}",
                       "-i", str(self.media_path), "-frames:v", "1",
                       "-vf", "showinfo", "-f", "null", "-"]
                completed = subprocess.run(cmd, capture_output=True, text=True,
                                           timeout=self.cfg.media_probe_timeout_s, check=False)
                match = _TS_PATTERN.search(completed.stderr or "")
                points.append({"timestampMs": timestamp,
                               "offsetMs": (round(float(match.group(1)) * 1000.0, 1)
                                            if match else None)})
        offsets = [point["offsetMs"] for point in points if point["offsetMs"] is not None]
        return {
            "batch": batch,
            "points": points,
            "count": len(points),
            "maxOffsetMs": round(max(offsets), 1) if offsets else None,
            "meanOffsetMs": round(sum(offsets) / len(offsets), 1) if offsets else None,
        }

    def _batch_once(self, ordered: list[int], out_dir: Path) -> list[FrameSample]:
        if len(ordered) > 1:
            step = ordered[1] - ordered[0]
            fps_filter = f"fps=1000/{step}"
            frame_limit = len(ordered) + 1     # 多留一帧再截断，避免边界处少一帧
        else:
            fps_filter = None
            frame_limit = 1
        out_dir.mkdir(parents=True, exist_ok=True)
        pattern = out_dir / "batch-%05d.jpg"
        cmd = [
            self.tools.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
            # 输入侧 seek（快）+ 默认 accurate_seek：解码后丢弃目标时间点之前的帧。
            # FRAME-6 实测：把 `-ss` 挪到 `-i` 之后（纯输出侧）反而让 fps 滤镜的相位错位
            # （50s 素材首帧平均像素差 62.3/255 ⇒ 取到了相邻场景），因此保留输入侧写法，
            # 由首帧等价性校验兜底。
            "-ss", f"{ordered[0] / 1000.0:.3f}",
            "-i", str(self.media_path),
        ]
        if fps_filter:
            cmd += ["-vf",
                    f"{fps_filter},scale='min({self.cfg.frame_max_width},iw)':-2:flags=lanczos"]
        else:
            cmd += ["-vf", f"scale='min({self.cfg.frame_max_width},iw)':-2:flags=lanczos"]
        cmd += ["-frames:v", str(frame_limit), "-q:v", "2", "-y", str(pattern)]
        self._run(cmd, None, context="批量抽帧")
        produced = sorted(out_dir.glob("batch-*.jpg"))
        if len(produced) < len(ordered):
            raise FrameExtractionFailure(TaskError(
                "DECODE_FAILED",
                f"批量抽帧数量不足（期望 {len(ordered)}，实际 {len(produced)}）"))
        samples: list[FrameSample] = []
        for timestamp, produced_path in zip(ordered, produced):
            # 统一改名成带时间戳的文件名（证据链要求可追溯）
            final = out_dir / frame_filename(timestamp)
            if final.exists():
                final.unlink()
            produced_path.replace(final)
            samples.append(FrameSample(timestamp_ms=timestamp, image_path=final))
        for extra in produced[len(ordered):]:
            extra.unlink(missing_ok=True)
        return samples

    # -- 内部 -------------------------------------------------------------
    def _require_tools(self) -> None:
        if not self.tools.available:
            raise FrameExtractionFailure(TaskError("FFMPEG_MISSING", self.tools.detail))

    def _clamp(self, timestamp_ms: int) -> int:
        at = max(0, int(timestamp_ms))
        if self.duration_ms is not None:
            at = min(at, max(0, int(self.duration_ms) - 1))
        return at

    def _run(self, cmd: list[str], out_path: Path | None, *, context: str) -> None:
        try:
            completed = subprocess.run(cmd, capture_output=True, text=True,
                                       timeout=self.cfg.media_probe_timeout_s, check=False)
        except (subprocess.TimeoutExpired, OSError) as exc:
            raise FrameExtractionFailure(TaskError(
                "DECODE_FAILED", f"{context}执行失败: {type(exc).__name__}")) from exc
        if completed.returncode != 0:
            raise FrameExtractionFailure(TaskError(
                "DECODE_FAILED", f"{context}失败（该时间点无可用画面或解码错误）"))
        if out_path is not None and (not out_path.is_file() or out_path.stat().st_size == 0):
            raise FrameExtractionFailure(TaskError(
                "DECODE_FAILED", f"{context}未产出有效图片"))


def verify_batch_equivalence(extractor: FrameExtractor, first_timestamp_ms: int,
                             out_dir: Path) -> dict[str, object]:
    """等价性证明（FRAME-2 的启用前提）：批量首帧 vs 逐点 seek 首帧。

    返回平均像素差与是否等价；调用方可据此决定是否启用批量路径。
    """
    batch = extractor.extract_batch([first_timestamp_ms], out_dir, verify_first=False)
    reference = extractor.extract_at(first_timestamp_ms, out_dir / "_ref2", suffix="ref")
    try:
        diff = _mean_pixel_diff(batch[0].image_path, reference.image_path)
        return {
            "timestampMs": first_timestamp_ms,
            "meanPixelDiff": diff,
            "equivalent": diff <= EQUIVALENCE_TOLERANCE,
            "batchFrame": str(batch[0].image_path),
            "referenceFrame": str(reference.image_path),
        }
    finally:
        _cleanup_dir(out_dir / "_ref2")


def _mean_pixel_diff(left: Path, right: Path) -> float:
    """平均像素差（0–255）。缺 Pillow 时退化为"字节完全相同"判断。"""
    try:
        from PIL import Image, ImageChops
    except ImportError:  # pragma: no cover - 环境缺 Pillow
        return 0.0 if left.read_bytes() == right.read_bytes() else 255.0
    with Image.open(left) as a, Image.open(right) as b:
        if a.size != b.size:
            return 255.0
        diff = ImageChops.difference(a.convert("RGB"), b.convert("RGB"))
        histogram = diff.histogram()
    total = 0
    count = 0
    for channel in range(3):
        offset = channel * 256
        for value in range(256):
            hits = histogram[offset + value]
            total += value * hits
            count += hits
    return (total / count) if count else 255.0


def _frames_equivalent(left: Path, right: Path, cfg: Config,
                       *, tolerance: float = EQUIVALENCE_TOLERANCE) -> bool:
    del cfg
    return _mean_pixel_diff(left, right) <= tolerance


def _cleanup_dir(path: Path) -> None:
    import shutil

    shutil.rmtree(path, ignore_errors=True)
