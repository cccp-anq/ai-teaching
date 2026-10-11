"""命令行入口。

```
python -m headup serve                    # 启动 HTTP 服务
python -m headup run --file 课堂.mp4       # 直接对本地文件跑一遍真实流水线并打印结果
python -m headup run --file 课堂.mp4 --stats-only
python -m headup fixture --output out.json # 生成 fixture 形状的合成 JobEnvelope
python -m headup stats --envelope out.json # 用 stats-v1 重算并打印统计
python -m headup extract-frame --file 课堂.mp4 --at 35000 --out frame.jpg
python -m headup verify-fixtures <dir>     # 校验目录里的结果信封是否满足契约
python -m headup selftest                  # 内置自检（不含网络/模型）
```
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import sys
from pathlib import Path
from typing import Any

from . import perf, wire
from .analyze import build_analyzer
from .config import Config
from .frames import plan_frames
from .media import FfmpegTools, extract_frame, probe_media
from .pipeline import compute_stats, run_job
from .providers import FixtureProvider, RealProvider
from .service import JobService
from .store import Store
from .wire import ApiError, TaskError


def _setup_logging(verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )


def _make_output_console_safe() -> None:
    """Windows 控制台默认 GBK：GBK 之外的符号（⇒、⚠）会让 print 抛 UnicodeEncodeError，
    把一次成功的运算变成"命令失败"。这里只把错误策略改成替换，不换编码（中文照常显示）。
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if callable(reconfigure):
            try:
                reconfigure(errors="replace")
            except (ValueError, OSError):  # pragma: no cover - 非常规流对象
                pass


def _request_dict(*, client_request_id: str, class_session_id: str, media_id: str,
                  sha256: str, duration_ms: int, mime_type: str, source_url: str,
                  start_ms: int, end_ms: int, window_ms: int, output_mode: str,
                  definition_id: str) -> dict[str, Any]:
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
        "analysisRange": {"startMs": start_ms, "endMs": end_ms},
        "parameters": {"windowMs": window_ms, "outputMode": output_mode,
                       "definitionId": definition_id},
    }


def _sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _guess_mime(path: Path) -> str:
    suffix = path.suffix.lower()
    return {
        ".mp4": "video/mp4", ".mov": "video/quicktime", ".mkv": "video/x-matroska",
        ".avi": "video/x-msvideo", ".webm": "video/webm", ".flv": "video/x-flv",
    }.get(suffix, "application/octet-stream")


# --------------------------------------------------------------------------
# 子命令
# --------------------------------------------------------------------------

def cmd_serve(args: argparse.Namespace, cfg: Config) -> int:
    from .server import HeadUpApp, create_server

    store = Store(cfg.db_path)
    service = JobService(cfg, store)
    service.start()
    app = HeadUpApp(cfg, service)
    server = create_server(cfg, app)
    host, port = server.server_address[0], server.server_address[1]
    print(f"head-up 模块已启动: http://{host}:{port}/api/v1/health")
    print(f"providerId={cfg.provider_id} moduleVersion={cfg.module_version} "
          f"definitionId={cfg.definition_id}")
    if not cfg.bearer_tokens:
        print("⚠ 未配置 MODULE_BEARER_TOKENS：所有 /api/v1 请求都会 401（失败关闭）")
    if cfg.enable_fixture_provider:
        print("⚠ 已开启合成 provider：只对已登记演示身份输出人工合成观测")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("正在停止…")
    finally:
        server.shutdown()
        # shutdown() 只停 serve_forever；监听 socket 要 server_close() 才释放
        # （否则进程退出时留下未关闭的 socket，测试里表现为 ResourceWarning）
        server.server_close()
        service.stop()
    return 0


def cmd_run(args: argparse.Namespace, cfg: Config) -> int:
    path = Path(args.file).expanduser().resolve()
    if not path.is_file():
        print(f"文件不存在: {path}", file=sys.stderr)
        return 2
    tools = FfmpegTools.locate(cfg)
    if not tools.available:
        print(f"无法进行真实处理：{tools.detail}", file=sys.stderr)
        return 3
    probe = probe_media(tools, path, cfg)
    duration_ms = args.duration_ms or probe.duration_ms
    request_dict = _request_dict(
        client_request_id=args.client_request_id,
        class_session_id=args.class_session_id,
        media_id=args.media_id,
        sha256=_sha256_of(path),
        duration_ms=duration_ms,
        mime_type=_guess_mime(path),
        source_url=(path.as_uri() if args.local_file_url else "https://127.0.0.1/local-media"),
        start_ms=args.start_ms,
        end_ms=args.end_ms or duration_ms,
        window_ms=args.window_ms,
        output_mode=args.output_mode,
        definition_id=cfg.definition_id,
    )
    request = wire.parse_job_request(request_dict, idempotency_key=args.client_request_id,
                                     allowed_definition_ids=cfg.allowed_definition_ids(),
                                     max_duration_ms=cfg.max_duration_ms,
                                     max_windows=cfg.max_windows)

    analyzer = build_analyzer(cfg)
    if analyzer.name != "qwen-vl":
        reason = getattr(analyzer, "reason", None) or getattr(analyzer, "analysis_notes", None)
        print(f"⚠ 当前视觉判定为 {analyzer.name} 模式：不会输出任何真实人数"
              + (f"（{reason}）" if isinstance(reason, str) else ""), file=sys.stderr)
    try:
        metrics = perf.RunMetrics(enabled=cfg.perf_metrics_enabled)
        result = run_job(request=request, cfg=cfg, analyzer=analyzer,
                         work_root=Path(cfg.work_dir) / "cli", local_media=path,
                         metrics=metrics)
    except TaskError as exc:
        print(f"处理失败: {exc.code} {exc.message}", file=sys.stderr)
        return 4
    print(json.dumps({"stats": result.stats, "warnings": result.warnings},
                     ensure_ascii=False, indent=2))
    # FRAME-6：把实测性能与成本打出来（未配置单价时只报 token，不给金额）
    _print_metrics_summary(result.metrics)
    if args.metrics_out and result.metrics is not None:
        Path(args.metrics_out).write_text(
            json.dumps(result.metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"已写出性能指标: {args.metrics_out}")
    if args.output:
        envelope = {
            "schemaVersion": wire.SCHEMA_VERSION,
            "clientRequestId": request.client_request_id,
            "jobId": "job-head-up-cli-local",
            "moduleType": wire.MODULE_TYPE,
            "providerId": cfg.provider_id,
            "moduleVersion": cfg.module_version,
            "classSessionId": request.class_session_id,
            "mediaId": request.media_id,
            "mediaSha256": request.media.sha256,
            "durationMs": request.duration_ms,
            "timeBase": wire.TIME_BASE,
            "analysisRange": request.analysis_range,
            "status": wire.STATUS_SUCCEEDED,
            "revision": 1,
            "createdAt": wire.utc_now(),
            "updatedAt": wire.utc_now(),
            "payload": result.payload,
            "error": None,
        }
        wire.validate_envelope(envelope)
        Path(args.output).write_text(json.dumps(envelope, ensure_ascii=False, indent=2),
                                     encoding="utf-8")
        print(f"已写出信封: {args.output}")
    if args.reports:
        Path(args.reports).write_text(json.dumps({"reports": result.reports}, ensure_ascii=False,
                                                 indent=2), encoding="utf-8")
        print(f"已写出逐窗判读记录: {args.reports}")
    return 0


def _print_metrics_summary(metrics: dict[str, Any] | None) -> None:
    """把实测指标打成一行行的人话（没测到的一律不写）。"""
    if not metrics:
        return
    counters = metrics.get("counters") or {}
    derived = metrics.get("derived") or {}
    tokens = metrics.get("tokens") or {}
    stages = metrics.get("stageTimingsMs") or {}
    print("性能（实测）:")
    print(f"  总耗时 {metrics.get('wallClockMs')}ms；"
          f"抽帧 {stages.get('EXTRACTING_PRIMARY', {}).get('totalMs')}ms、"
          f"代表帧判定 {stages.get('VISION_PRIMARY', {}).get('totalMs')}ms、"
          f"复核 {stages.get('REVIEWING', {}).get('totalMs')}ms")
    print(f"  抽帧方式 {metrics.get('notes', {}).get('extractionMode')}；"
          f"代表帧 {counters.get('frames.primary')} 张、复核帧 {counters.get('frames.review')} 张；"
          f"落盘 {(metrics.get('diskBytes') or {}).get('primaryFrameBytes')} + "
          f"{(metrics.get('diskBytes') or {}).get('reviewFrameBytes')} 字节")
    print(f"  模型逻辑调用 {counters.get('vision.calls')} 次"
          f"（真实 HTTP 请求 {metrics.get('notes', {}).get('visionRequests')} 次）、"
          f"复核窗口 {counters.get('windows.reviewed')} 个"
          f"（reviewRatio={derived.get('reviewRatio')}）")
    print(f"  token：prompt={tokens.get('promptTokens')} completion={tokens.get('completionTokens')}")
    cost = metrics.get("cost")
    if cost:
        print(f"  成本估算：{cost['estimatedAmount']} {cost['currency']}（按配置单价估算，非账单）")
    else:
        print("  成本：未配置单价（VISION_PRICE_PROMPT_PER_1K / VISION_PRICE_COMPLETION_PER_1K）"
              "=> 只报 token，不猜金额")


def cmd_extract_frame(args: argparse.Namespace, cfg: Config) -> int:
    tools = FfmpegTools.locate(cfg)
    if not tools.available:
        print(f"无法抽帧：{tools.detail}", file=sys.stderr)
        return 3
    path = Path(args.file).expanduser().resolve()
    out = Path(args.out).expanduser().resolve()
    extract_frame(tools, path, args.at, out, max_width=cfg.frame_max_width, cfg=cfg)
    print(f"已抽取 {args.at}ms 处代表帧: {out}")
    return 0


def cmd_fixture(args: argparse.Namespace, cfg: Config) -> int:
    # 合成路径要求口径已登记：默认用主项目文档包登记的演示口径 head-up-demo-v1。
    from . import definitions

    definition_id = args.definition_id or definitions.DEFINITION_ID_DEMO
    definition_text = definitions.text_for(definition_id)
    if definition_text is None:
        print(f"未登记的口径: {definition_id}；可用: {', '.join(sorted(definitions.DEFINITIONS))}",
              file=sys.stderr)
        return 2
    cfg = Config(**{**cfg.__dict__, "enable_fixture_provider": True,
                    "definition_id": definition_id, "definition_text": definition_text})
    provider = FixtureProvider(cfg)
    request_dict = _request_dict(
        client_request_id=args.client_request_id,
        class_session_id=cfg.fixture_demo_class_session_id,
        media_id=cfg.fixture_demo_media_id,
        sha256=cfg.fixture_demo_sha256,
        duration_ms=args.duration_ms,
        mime_type="video/mp4",
        source_url="https://media.example.invalid/fixture.mp4",
        start_ms=0,
        end_ms=args.duration_ms,
        window_ms=args.window_ms,
        output_mode=args.output_mode,
        definition_id=definition_id,
    )
    request = wire.parse_job_request(request_dict, idempotency_key=args.client_request_id,
                                     allowed_definition_ids=cfg.allowed_definition_ids(),
                                     max_duration_ms=cfg.max_duration_ms,
                                     max_windows=cfg.max_windows)
    result = provider.run(request, source_url=None)
    envelope = {
        "schemaVersion": wire.SCHEMA_VERSION,
        "clientRequestId": request.client_request_id,
        "jobId": args.job_id,
        "moduleType": wire.MODULE_TYPE,
        "providerId": provider.provider_id,
        "moduleVersion": provider.version,
        "classSessionId": request.class_session_id,
        "mediaId": request.media_id,
        "mediaSha256": request.media.sha256,
        "durationMs": request.duration_ms,
        "timeBase": wire.TIME_BASE,
        "analysisRange": request.analysis_range,
        "status": wire.STATUS_SUCCEEDED,
        "revision": 1,
        "createdAt": "2026-09-13T03:00:00Z",
        "updatedAt": "2026-09-13T03:01:00Z",
        "payload": result.payload,
        "error": None,
    }
    wire.validate_envelope(envelope)
    text = json.dumps(envelope, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(text, encoding="utf-8")
        print(f"已写出合成信封: {args.output}")
    else:
        print(text)
    print(json.dumps(result.stats, ensure_ascii=False, indent=2), file=sys.stderr)
    return 0


def cmd_stats(args: argparse.Namespace, cfg: Config) -> int:
    del cfg
    envelope = json.loads(Path(args.envelope).read_text(encoding="utf-8"))
    wire.validate_envelope(envelope)
    stats = compute_stats(envelope["payload"], envelope["durationMs"])
    print(json.dumps(stats, ensure_ascii=False, indent=2))
    return 0


def cmd_verify_fixtures(args: argparse.Namespace, cfg: Config) -> int:
    del cfg
    directory = Path(args.directory)
    checked = 0
    skipped: list[str] = []
    failures: list[str] = []
    for path in sorted(directory.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(data, dict) or data.get("moduleType") != wire.MODULE_TYPE:
            continue
        if data.get("status") not in (wire.STATUS_QUEUED, wire.STATUS_RUNNING,
                                      wire.STATUS_SUCCEEDED, wire.STATUS_FAILED):
            # 请求形状（无 status）不是信封，跳过而不是误报
            skipped.append(path.name)
            continue
        try:
            wire.validate_envelope(data)
            checked += 1
            print(f"OK   {path.name}")
        except ValueError as exc:
            failures.append(f"{path.name}: {exc}")
            print(f"FAIL {path.name}: {exc}")
    if skipped:
        print(f"跳过请求/非信封文件: {', '.join(skipped)}")
    print(f"已校验 {checked} 个 HEAD_UP 信封；失败 {len(failures)}")
    return 1 if failures else 0


def cmd_cleanup(args: argparse.Namespace, cfg: Config) -> int:
    """FRAME-5：清理超过保留期的失败任务临时目录（幂等，可挂定时任务）。"""
    del args
    from .pipeline import workspace_service_for

    service = workspace_service_for(cfg)
    removed = service.cleanup_expired()
    print(f"已清理 {len(removed)} 个过期失败目录（保留期 {cfg.failed_task_retention_hours} 小时）")
    for task_id in removed:
        print(f"  - {task_id}")
    print(f"当前任务目录 {len(service.list_tasks())} 个")
    return 0


def cmd_selftest(args: argparse.Namespace, cfg: Config) -> int:
    del args
    from . import definitions

    demo_cfg = Config(**{**cfg.__dict__, "enable_fixture_provider": True,
                         "definition_id": definitions.DEFINITION_ID_DEMO,
                         "definition_text": definitions.DEFINITION_TEXT_DEMO})
    provider = FixtureProvider(demo_cfg)
    request_dict = _request_dict(
        client_request_id="req-selftest-001",
        class_session_id=cfg.fixture_demo_class_session_id,
        media_id=cfg.fixture_demo_media_id,
        sha256=cfg.fixture_demo_sha256,
        duration_ms=60000, mime_type="video/mp4",
        source_url="https://media.example.invalid/fixture.mp4",
        start_ms=0, end_ms=60000, window_ms=10000, output_mode="COUNTS",
        definition_id=definitions.DEFINITION_ID_DEMO,
    )
    request = wire.parse_job_request(request_dict, idempotency_key="req-selftest-001",
                                     allowed_definition_ids=(definitions.DEFINITION_ID_DEMO,),
                                     max_duration_ms=cfg.max_duration_ms,
                                     max_windows=cfg.max_windows)
    result = provider.run(request, source_url=None)
    rates = result.stats["headUp"]["windowRates"]
    expected = [0.75, 0.6, 0.0, None, None, 0.5]
    assert rates == expected, f"窗口比例不符: {rates}"
    assert abs(result.stats["headUp"]["meanRate"] - 0.4625) < 1e-9
    assert abs(result.stats["headUp"]["validCoverage"] - 2 / 3) < 1e-9
    assert result.stats["headUp"]["processedCoverage"] == 1
    print("自检通过：fixture 固定数值与 expected-statistics.json 一致")
    return 0


def cmd_benchmark(args: argparse.Namespace, cfg: Config) -> int:
    """FRAME-6 可复现基准：抽帧吞吐（+可选端到端），结果写成 JSON 报告。

    只报实测值：`--every` 抽稀必须保持等间隔（批量路径要求），抽稀后仍然逐点比对等价性。
    """
    from . import benchmark as bench

    path = Path(args.file).expanduser().resolve()
    if not path.is_file():
        print(f"文件不存在: {path}", file=sys.stderr)
        return 2
    tools = FfmpegTools.locate(cfg)
    if not tools.available:
        print(f"无法进行基准测试：{tools.detail}", file=sys.stderr)
        return 3

    modes = (bench.MODE_SEEK, bench.MODE_BATCH) if args.extraction == "both" \
        else (args.extraction,)
    out_dir = Path(args.work_dir).expanduser().resolve() if args.work_dir else None
    extraction = bench.run_extraction_benchmark(
        cfg, path, window_ms=args.window_ms, max_points=args.max_points, every=args.every,
        modes=modes, repeats=args.repeats, out_dir=out_dir,
        verify_equivalence=not args.no_equivalence)

    pipeline_report = None
    if args.analyzer:
        analyzer = _benchmark_analyzer(args, cfg)
        pipeline_report = bench.run_pipeline_benchmark(
            cfg, path, analyzer=analyzer, window_ms=args.window_ms,
            client_request_id=args.client_request_id,
            analysis_range=(args.start_ms, args.end_ms) if args.end_ms else None)

    report = bench.build_report(
        extraction=extraction, pipeline=pipeline_report,
        config={"windowMs": args.window_ms, "maxPoints": args.max_points, "every": args.every,
                "repeats": args.repeats, "modes": list(modes), "analyzer": args.analyzer,
                "batchExtractionConfigured": cfg.sampling_use_batch_extraction,
                "frameMaxWidth": cfg.frame_max_width, "providerId": cfg.provider_id},
        decord_threshold_s_per_frame=args.decord_threshold)

    out = Path(args.out).expanduser().resolve()
    bench.write_report(report, out)
    print("\n结论：")
    for line in report["conclusions"]:
        print(f"  - {line}")
    print(f"\n报告: {out}")
    return 0


def _benchmark_analyzer(args: argparse.Namespace, cfg: Config):
    """基准用分析器：real = 真实模型；heuristic/fixture = 合成（报告里会标注 synthetic）。"""
    from dataclasses import replace as _replace

    from .analyze import build_analyzer

    if args.analyzer == "real":
        return build_analyzer(cfg)
    if args.analyzer == "heuristic":
        return build_analyzer(_replace(cfg, vision_provider="heuristic",
                                       heuristic_synthetic=args.heuristic_synthetic))
    raise SystemExit(f"未知分析器: {args.analyzer}")


def _synthetic_classroom_image(path: Path, *, width: int, height: int, rows: int, cols: int) -> None:
    """画一张**示意性**教室图：人形轮廓（头部椭圆 + 肩部）按网格排布。

    它只是合成测试图，不是真实课堂画面，也不含任何真实人物；用途是让抽帧、分块、
    模型调用与窗口质量分类在无真实媒体时也能端到端跑通。
    """
    from PIL import Image, ImageDraw

    image = Image.new("RGB", (width, height), (238, 236, 230))
    draw = ImageDraw.Draw(image)
    # 讲台/黑板区域（画面上方）
    draw.rectangle([0, 0, width, int(height * 0.22)], fill=(36, 52, 68))
    draw.rectangle([int(width * 0.06), int(height * 0.05), int(width * 0.94), int(height * 0.20)],
                   fill=(22, 92, 74))
    cell_w = width / cols
    cell_h = (height * 0.78) / max(1, rows)
    palette = [(214, 173, 140), (196, 152, 120), (228, 190, 158), (176, 132, 104)]
    shirt = [(92, 112, 158), (140, 96, 96), (86, 132, 118), (120, 118, 150), (150, 138, 96)]
    index = 0
    for row in range(rows):
        for col in range(cols):
            index += 1
            cx = cell_w * (col + 0.5) + (6 if index % 2 else -6)
            cy = height * 0.24 + cell_h * (row + 0.45)
            head_r = cell_h * 0.19
            pad = int(height * 0.012 * row)  # 后排稍微抬高，模拟透视
            draw.ellipse([cx - head_r, cy - head_r - pad, cx + head_r, cy + head_r - pad],
                         fill=palette[index % len(palette)])
            draw.rectangle([cx - head_r * 1.5, cy + head_r * 0.9 - pad,
                            cx + head_r * 1.5, cy + head_r * 2.6 - pad],
                           fill=shirt[index % len(shirt)])
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG")


def cmd_generate_fixtures(args: argparse.Namespace, cfg: Config) -> int:
    """生成对外交付用的 fixture 包（M2 合成形状 + M3 真实媒体结果）。"""
    import shutil
    import subprocess

    from . import definitions
    from .store import Store

    out_dir = Path(args.output).expanduser().resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    media_path = Path(args.media).expanduser().resolve()
    tools = FfmpegTools.locate(cfg)
    if not tools.available:
        print(f"无法生成真实媒体：{tools.detail}", file=sys.stderr)
        return 3

    duration_ms = args.duration_ms
    if not media_path.is_file():
        media_path.parent.mkdir(parents=True, exist_ok=True)
        still = media_path.with_suffix(".png")
        _synthetic_classroom_image(still, width=640, height=360,
                                   rows=args.rows, cols=args.cols)
        seconds = duration_ms / 1000.0
        cmd = [tools.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
               "-loop", "1", "-framerate", "5", "-i", str(still),
               "-f", "lavfi", "-i", f"sine=frequency=330:duration={seconds:.3f}",
               "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-t", f"{seconds:.3f}",
               "-c:a", "aac", "-shortest", "-y", str(media_path)]
        completed = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if completed.returncode != 0:
            print(f"ffmpeg 生成媒体失败: {completed.stderr[:400]}", file=sys.stderr)
            return 3
        print(f"已生成合成测试媒体（示意教室图，非真实课堂）: {media_path}")

    sha256 = _sha256_of(media_path)
    probe = probe_media(tools, media_path, cfg)
    actual_duration = args.duration_ms or probe.duration_ms

    # 1) 合成形状 fixture（人工合成观测，绝不代表真实处理结果）
    demo_cfg = Config(**{**cfg.__dict__, "enable_fixture_provider": True,
                         "definition_id": definitions.DEFINITION_ID_DEMO,
                         "definition_text": definitions.DEFINITION_TEXT_DEMO})
    fixture_provider = FixtureProvider(demo_cfg)
    synthetic_requests: list[dict[str, Any]] = []
    for output_mode, request_id, job_id in (
        ("COUNTS", "req-head-up-001", "job-head-up-001"),
        ("RATIO_ONLY", "req-head-up-ratio-001", "job-head-up-ratio-001"),
    ):
        request_dict = _request_dict(
            client_request_id=request_id, class_session_id=cfg.fixture_demo_class_session_id,
            media_id=cfg.fixture_demo_media_id, sha256=cfg.fixture_demo_sha256,
            duration_ms=60000, mime_type="video/mp4",
            source_url="https://media.example.invalid/fixture.mp4",
            start_ms=0, end_ms=60000, window_ms=10000, output_mode=output_mode,
            definition_id=definitions.DEFINITION_ID_DEMO,
        )
        (out_dir / f"head-up-{'ratio-' if output_mode == 'RATIO_ONLY' else ''}request.json"
         ).write_text(json.dumps(request_dict, ensure_ascii=False, indent=2) + "\n",
                      encoding="utf-8")
        request = wire.parse_job_request(request_dict, idempotency_key=request_id,
                                        allowed_definition_ids=(definitions.DEFINITION_ID_DEMO,),
                                        max_duration_ms=cfg.max_duration_ms,
                                        max_windows=cfg.max_windows)
        result = fixture_provider.run(request, source_url=None)
        envelope = json.loads(json.dumps({
            "schemaVersion": wire.SCHEMA_VERSION,
            "clientRequestId": request_id, "jobId": job_id, "moduleType": wire.MODULE_TYPE,
            "providerId": fixture_provider.provider_id,
            "moduleVersion": fixture_provider.version,
            "classSessionId": cfg.fixture_demo_class_session_id,
            "mediaId": cfg.fixture_demo_media_id, "mediaSha256": cfg.fixture_demo_sha256,
            "durationMs": 60000, "timeBase": wire.TIME_BASE,
            "analysisRange": {"startMs": 0, "endMs": 60000},
            "status": wire.STATUS_SUCCEEDED, "revision": 1,
            "createdAt": "2026-09-13T03:00:00Z", "updatedAt": "2026-09-13T03:01:00Z",
            "payload": result.payload, "error": None,
        }))
        wire.validate_envelope(envelope)
        (out_dir / f"head-up-{'ratio-' if output_mode == 'RATIO_ONLY' else ''}success.json"
         ).write_text(json.dumps(envelope, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        synthetic_requests.append(request_dict)

    # 2) 真实媒体结果（用本地文件跑一遍流水线；无凭证时窗口质量必须显式降级）
    real_request_id = args.client_request_id
    real_request_dict = _request_dict(
        client_request_id=real_request_id, class_session_id=args.class_session_id,
        media_id=args.media_id, sha256=sha256, duration_ms=actual_duration,
        mime_type=_guess_mime(media_path),
        source_url="https://media.example.invalid/headup-fixture.mp4",
        start_ms=0, end_ms=actual_duration, window_ms=args.window_ms,
        output_mode="COUNTS", definition_id=cfg.definition_id,
    )
    real_request = wire.parse_job_request(real_request_dict, idempotency_key=real_request_id,
                                          allowed_definition_ids=cfg.allowed_definition_ids(),
                                          max_duration_ms=cfg.max_duration_ms,
                                          max_windows=cfg.max_windows)
    analyzer = build_analyzer(cfg)
    try:
        run_result = run_job(request=real_request, cfg=cfg, analyzer=analyzer,
                             work_root=Path(cfg.work_dir) / "fixtures", local_media=media_path)
    except TaskError as exc:
        print(f"真实媒体处理失败（不生成真实结果 fixture）: {exc.code} {exc.message}",
              file=sys.stderr)
        run_result = None

    manifest: dict[str, Any] = {
        "generatedBy": f"{cfg.provider_id}/{cfg.module_version}",
        "analyzer": {"name": analyzer.name, "version": analyzer.version},
        "definitionId": cfg.definition_id,
        "windowMs": args.window_ms,
        "syntheticFixtures": {
            "note": "人工合成观测；不是模型对真实媒体的处理结果；仅用于结构/统计联调",
            "mediaSha256": cfg.fixture_demo_sha256,
            "durationMs": 60000,
            "files": ["head-up-request.json", "head-up-success.json",
                      "head-up-ratio-request.json", "head-up-ratio-success.json"],
        },
    }
    if run_result is not None:
        envelope = {
            "schemaVersion": wire.SCHEMA_VERSION,
            "clientRequestId": real_request_id, "jobId": "job-head-up-real-001",
            "moduleType": wire.MODULE_TYPE, "providerId": cfg.provider_id,
            "moduleVersion": cfg.module_version,
            "classSessionId": args.class_session_id, "mediaId": args.media_id,
            "mediaSha256": sha256, "durationMs": actual_duration,
            "timeBase": wire.TIME_BASE,
            "analysisRange": {"startMs": 0, "endMs": actual_duration},
            "status": wire.STATUS_SUCCEEDED, "revision": 1,
            "createdAt": wire.utc_now(), "updatedAt": wire.utc_now(),
            "payload": run_result.payload, "error": None,
        }
        wire.validate_envelope(envelope)
        (out_dir / "head-up-real-success.json").write_text(
            json.dumps(envelope, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (out_dir / "head-up-real-stats.json").write_text(
            json.dumps(run_result.stats, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        manifest["realFixture"] = {
            "note": "本部署对合成测试媒体的真实处理结果（非真实课堂数据）",
            "mediaSha256": sha256, "durationMs": actual_duration,
            "analyzer": analyzer.name,
            "credentialsConfigured": bool(cfg.vision_api_key),
            "qualitySummary": {quality: sum(1 for w in run_result.payload["windows"]
                                            if w["quality"] == quality)
                               for quality in ("VALID", "NO_SUBJECTS", "LOW_QUALITY")},
            "files": ["head-up-real-request.json", "head-up-real-success.json",
                      "head-up-real-stats.json"],
        }
        real_request_doc = dict(real_request_dict)
        real_request_doc["media"] = dict(real_request_doc["media"])
        real_request_doc["media"]["sourceUrl"] = "<部署时替换为受控 HTTPS 下载地址>"
        (out_dir / "head-up-real-request.json").write_text(
            json.dumps(real_request_doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    (out_dir / "media-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if args.media_out:
        media_out = Path(args.media_out).expanduser().resolve()
        media_out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(media_path, media_out)
        print(f"已复制测试媒体: {media_out}")

    if args.serve:
        return _serve_and_submit(cfg, args, media_path, actual_duration, sha256, out_dir)
    print(f"fixture 已生成: {out_dir}")
    return 0


def _serve_and_submit(cfg: Config, args: argparse.Namespace, media_path: Path, duration_ms: int,
                      sha256: str, out_dir: Path) -> int:
    """起一个本地媒体服务 + 模块服务，真实走一遍 HTTP 契约路径（202 → 轮询 → 终态）。"""
    import threading
    import time
    import urllib.request
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    from .server import HeadUpApp, create_server
    from .service import JobService
    from .store import Store

    media_bytes = media_path.read_bytes()

    class _Media(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self) -> None:  # noqa: N802
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(media_bytes)))
            self.end_headers()
            self.wfile.write(media_bytes)

        def log_message(self, fmt: str, *args: Any) -> None:
            del fmt, args

    media_server = ThreadingHTTPServer(("127.0.0.1", 0), _Media)
    threading.Thread(target=media_server.serve_forever, daemon=True).start()
    media_url = f"http://127.0.0.1:{media_server.server_address[1]}/media.bin"

    if not cfg.bearer_tokens:
        print("serve 模式需要 MODULE_BEARER_TOKENS（失败关闭）", file=sys.stderr)
        return 2
    cfg = Config(**{**cfg.__dict__, "server_port": 0, "allow_loopback_http": True,
                    "host_allow_list": ("127.0.0.1",)})
    store = Store(cfg.db_path)
    service = JobService(cfg, store)
    service.start()
    server = create_server(cfg, HeadUpApp(cfg, service))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}"
    token = cfg.bearer_tokens[0]

    request_dict = _request_dict(
        client_request_id=args.client_request_id, class_session_id=args.class_session_id,
        media_id=args.media_id, sha256=sha256, duration_ms=duration_ms,
        mime_type=_guess_mime(media_path), source_url=media_url,
        start_ms=0, end_ms=duration_ms, window_ms=args.window_ms, output_mode="COUNTS",
        definition_id=cfg.definition_id,
    )
    body = json.dumps(request_dict, ensure_ascii=False).encode("utf-8")
    post = urllib.request.Request(f"{base}/api/v1/jobs", data=body, method="POST", headers={
        "Content-Type": "application/json",
        "Authorization": f"Bearer {token}",
        "Idempotency-Key": args.client_request_id,
    })
    try:
        with urllib.request.urlopen(post, timeout=30) as response:
            first_status = response.status
            envelope = json.loads(response.read().decode("utf-8"))
    except Exception as exc:  # noqa: BLE001
        print(f"提交失败: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 4
    print(f"POST /api/v1/jobs -> {first_status}, jobId={envelope['jobId']}")

    deadline = time.time() + args.timeout_s
    while envelope["status"] not in wire.TERMINAL_STATUSES and time.time() < deadline:
        time.sleep(2)
        get = urllib.request.Request(f"{base}/api/v1/jobs/{envelope['jobId']}",
                                     headers={"Authorization": f"Bearer {token}"})
        with urllib.request.urlopen(get, timeout=30) as response:
            envelope = json.loads(response.read().decode("utf-8"))
    print(f"终态: {envelope['status']} revision={envelope['revision']}")
    wire.validate_envelope(envelope)

    # 幂等复验：同键同内容必须 200 且 jobId 不变。
    replay = urllib.request.Request(f"{base}/api/v1/jobs", data=body, method="POST", headers={
        "Content-Type": "application/json", "Authorization": f"Bearer {token}",
        "Idempotency-Key": args.client_request_id,
    })
    with urllib.request.urlopen(replay, timeout=30) as response:
        replay_status = response.status
        replayed = json.loads(response.read().decode("utf-8"))
    same = replayed["jobId"] == envelope["jobId"]
    print(f"同键重提 -> {replay_status}, jobId 复用={same}")

    (out_dir / "head-up-http-success.json").write_text(
        json.dumps(envelope, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    stats = (compute_stats(envelope["payload"], envelope["durationMs"])
             if envelope["status"] == wire.STATUS_SUCCEEDED else None)
    if stats:
        (out_dir / "head-up-http-stats.json").write_text(
            json.dumps(stats, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report = {
        "baseUrl": base, "mediaUrlHost": "127.0.0.1（本地联调）",
        "postStatus": first_status, "replayStatus": replay_status,
        "replayReusedJobId": same, "terminalStatus": envelope["status"],
        "windowQuality": {quality: sum(1 for w in (envelope["payload"] or {}).get("windows", [])
                                       if w["quality"] == quality)
                          for quality in ("VALID", "NO_SUBJECTS", "LOW_QUALITY")},
        "stats": stats,
    }
    (out_dir / "http-run-record.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    server.shutdown()
    server.server_close()
    media_server.shutdown()
    media_server.server_close()
    service.stop()
    print(f"HTTP 联调记录已写出: {out_dir / 'http-run-record.json'}")
    return 0


# --------------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="headup", description="抬头率识别模块（契约 v0.1）")
    parser.add_argument("--config", default=None, help="配置文件路径（默认 config/headup.config.json）")
    parser.add_argument("--verbose", action="store_true")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("serve", help="启动 HTTP 服务")

    run = sub.add_parser("run", help="对本地视频直接跑一遍处理流水线")
    run.add_argument("--file", required=True)
    run.add_argument("--out", "--output", dest="output", default=None)
    run.add_argument("--reports", default=None)
    run.add_argument("--client-request-id", default="req-cli-local-001")
    run.add_argument("--class-session-id", default="session-local-001")
    run.add_argument("--media-id", default="media-local-001")
    run.add_argument("--window-ms", type=int, default=10000)
    run.add_argument("--output-mode", default="COUNTS", choices=list(wire.VALUE_MODES))
    run.add_argument("--start-ms", type=int, default=0)
    run.add_argument("--end-ms", type=int, default=0)
    run.add_argument("--duration-ms", type=int, default=0)
    run.add_argument("--local-file-url", action="store_true",
                     help="把本地文件路径写进 sourceUrl（仅用于离线调试，契约路径不使用）")
    run.add_argument("--metrics-out", default=None,
                     help="把实测性能指标（耗时/调用数/token/成本）写到该 JSON 文件")

    frame = sub.add_parser("extract-frame", help="抽取指定毫秒的一帧（用于单帧提示词试验）")
    frame.add_argument("--file", required=True)
    frame.add_argument("--at", type=int, required=True)
    frame.add_argument("--out", required=True)

    fixture = sub.add_parser("fixture", help="生成合成 fixture 信封")
    fixture.add_argument("--output", default=None)
    fixture.add_argument("--job-id", default="job-head-up-fixture-001")
    fixture.add_argument("--client-request-id", default="req-head-up-fixture-001")
    fixture.add_argument("--window-ms", type=int, default=10000)
    fixture.add_argument("--duration-ms", type=int, default=60000)
    fixture.add_argument("--output-mode", default="COUNTS", choices=list(wire.VALUE_MODES))
    fixture.add_argument("--definition-id", default=None)

    stats = sub.add_parser("stats", help="用 stats-v1 重算信封统计")
    stats.add_argument("--envelope", required=True)

    verify = sub.add_parser("verify-fixtures", help="校验目录内的 HEAD_UP 结果信封")
    verify.add_argument("directory")

    gen = sub.add_parser("generate-fixtures",
                         help="生成交付用 fixture 包（合成形状 + 真实媒体结果）")
    gen.add_argument("--output", required=True, help="输出目录")
    gen.add_argument("--media", required=True, help="测试媒体路径（不存在则用 ffmpeg 合成）")
    gen.add_argument("--media-out", default=None, help="把测试媒体复制到该路径")
    gen.add_argument("--duration-ms", type=int, default=60000)
    gen.add_argument("--window-ms", type=int, default=10000)
    gen.add_argument("--client-request-id", default="req-head-up-real-001")
    gen.add_argument("--class-session-id", default="session-headup-fixture-001")
    gen.add_argument("--media-id", default="media-headup-fixture-001")
    gen.add_argument("--serve", action="store_true",
                     help="再起本地媒体服务与模块服务，真实走一遍 HTTP 契约路径")
    gen.add_argument("--timeout-s", type=float, default=300.0)
    gen.add_argument("--rows", type=int, default=4, help="合成教室图的人形行数")
    gen.add_argument("--cols", type=int, default=5, help="合成教室图的人形列数")

    sub.add_parser("selftest", help="内置自检（无网络/无模型）")
    sub.add_parser("cleanup", help="清理过期的失败任务临时目录（FRAME-5）")

    bench_parser = sub.add_parser("benchmark", help="性能基准：抽帧吞吐 + 可选端到端（FRAME-6）")
    bench_parser.add_argument("--file", required=True, help="待测媒体（建议真实课堂视频）")
    bench_parser.add_argument("--window-ms", type=int, default=10000)
    bench_parser.add_argument("--max-points", type=int, default=0, help="只用网格前 N 个点（0=全部）")
    bench_parser.add_argument("--every", type=int, default=1,
                              help="网格抽稀步长（必须保持等间隔，批量路径才可用）")
    bench_parser.add_argument("--repeats", type=int, default=1, help="每种模式重复次数（看稳定性）")
    bench_parser.add_argument("--extraction", default="both", choices=["both", "seek", "batch"])
    bench_parser.add_argument("--no-equivalence", action="store_true",
                              help="跳过逐点等价性比较（只测速度，不做正确性证据）")
    bench_parser.add_argument("--analyzer", default="", choices=["", "real", "heuristic"],
                              help="额外跑一遍端到端：real=真实模型，heuristic=合成计数（会标注）")
    bench_parser.add_argument("--heuristic-synthetic", action="store_true",
                              help="heuristic 分析器返回合成计数（仅结构演练）")
    bench_parser.add_argument("--start-ms", type=int, default=0)
    bench_parser.add_argument("--end-ms", type=int, default=0)
    bench_parser.add_argument("--client-request-id", default=None)
    bench_parser.add_argument("--work-dir", default=None, help="抽帧临时目录（默认 .work/benchmark）")
    bench_parser.add_argument("--decord-threshold", type=float, default=0.5,
                              help="Decord 决策线：批量单帧耗时低于该秒数即认为不必引入")
    bench_parser.add_argument("--out", required=True, help="报告输出路径（JSON）")
    return parser


def main(argv: list[str] | None = None) -> int:
    _make_output_console_safe()
    args = build_parser().parse_args(argv)
    _setup_logging(args.verbose)
    try:
        cfg = Config.load(args.config)
    except ValueError as exc:
        print(f"配置错误: {exc}", file=sys.stderr)
        return 2
    handlers = {
        "serve": cmd_serve,
        "run": cmd_run,
        "extract-frame": cmd_extract_frame,
        "fixture": cmd_fixture,
        "stats": cmd_stats,
        "verify-fixtures": cmd_verify_fixtures,
        "generate-fixtures": cmd_generate_fixtures,
        "selftest": cmd_selftest,
        "cleanup": cmd_cleanup,
        "benchmark": cmd_benchmark,
    }
    try:
        return handlers[args.command](args, cfg)
    except (ApiError, TaskError) as exc:
        code = getattr(exc, "code", "FAILED")
        print(f"{code}: {exc}", file=sys.stderr)
        return 4


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
