"""部署配置。

全部可用环境变量覆盖；改配置前先读本文件的默认值。
契约相关项（providerId / moduleVersion / bearerTokens）必须与主项目登记值一致。

失败关闭原则（与转写模块一致）：
- bearerTokens 为空 => 所有 /api/v1 请求 401；
- enableFixtureProvider 默认为 false => 合成 provider 不参与任何请求。
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping

from . import definitions, prompts
from .counts import METRIC_VERSION, SAMPLING_POLICY_VERSION

_TRUE = {"1", "true", "yes", "on"}
_FALSE = {"0", "false", "no", "off"}


def _bool(env: Mapping[str, str], name: str, default: bool) -> bool:
    raw = env.get(name)
    if raw is None or raw.strip() == "":
        return default
    value = raw.strip().lower()
    if value in _TRUE:
        return True
    if value in _FALSE:
        return False
    raise ValueError(f"环境变量 {name} 不是合法布尔值: {raw!r}")


def _int(env: Mapping[str, str], name: str, default: int) -> int:
    raw = env.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return int(raw.strip())
    except ValueError as exc:  # pragma: no cover - 配置错误直接抛出
        raise ValueError(f"环境变量 {name} 不是合法整数: {raw!r}") from exc


def _float(env: Mapping[str, str], name: str, default: float) -> float:
    raw = env.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return float(raw.strip())
    except ValueError as exc:  # pragma: no cover
        raise ValueError(f"环境变量 {name} 不是合法数字: {raw!r}") from exc


def _list(env: Mapping[str, str], name: str, default: list[str]) -> list[str]:
    raw = env.get(name)
    if raw is None or raw.strip() == "":
        return list(default)
    return [item.strip() for item in raw.split(",") if item.strip()]


def _optional_int(value: Any) -> int | None:
    """空值表示"不发送该参数"（Round 4：seed 默认不发送）。"""
    if value is None:
        return None
    text = str(value).strip()
    if text == "":
        return None
    return int(text)


def _resolve_definition_text(definition_id: str, configured: str) -> str:
    """口径 ID 与口径文本必须成对一致，避免主项目登记到不匹配的口径说明。"""
    configured = (configured or "").strip()
    known = definitions.text_for(definition_id)
    if configured:
        if known and configured != known:
            raise ValueError(
                f"HEADUP_DEFINITION_TEXT 与登记口径 {definition_id} 的内置文本不一致："
                "要么使用内置文本，要么同时更新 src/headup/definitions.py 与本文档")
        return configured
    if known:
        return known
    raise ValueError(
        f"未知的 definitionId={definition_id!r}：必须同时提供 HEADUP_DEFINITION_TEXT，"
        "并在 docs/定义说明-head-up.md 里写清判定规则、分母与遮挡判据")


@dataclass(frozen=True)
class Config:
    # ---- 部署身份与鉴权（契约 §7 交接表必填项）----
    provider_id: str = "head-up-local"
    module_version: str = "head-up-0.1.0"
    api_version: str = "1.0"
    bearer_tokens: tuple[str, ...] = ()
    server_host: str = "127.0.0.1"
    server_port: int = 8080
    db_path: str = "./.data/headup.sqlite3"
    workers: int = 1

    # ---- 上限（声明值；被强制执行时在 README/交接表标注）----
    max_body_bytes: int = 2_097_152
    max_media_bytes: int = 2_147_483_648
    max_duration_ms: int = 7_200_000
    max_windows: int = 20_000
    max_concurrent_jobs: int = 4
    poll_interval_ms: int = 2000
    poll_interval_max_ms: int = 10000

    # ---- 媒体（契约 §3 media 字段）----
    work_dir: str = "./.work"
    ffmpeg_path: str = "ffmpeg"
    ffprobe_path: str = "ffprobe"
    host_allow_list: tuple[str, ...] = ()
    allow_loopback_http: bool = False
    media_connect_timeout_s: float = 10.0
    media_read_timeout_s: float = 600.0
    media_total_timeout_s: float = 900.0
    media_probe_timeout_s: float = 180.0
    duration_tolerance_ms: int = 2000
    keep_media_files: bool = False
    download_retries: int = 2

    # ---- 观测口径（抬头专用文档 §2/§3 + v0.2 严格观察口径）----
    definition_id: str = definitions.DEFINITION_ID_REAL
    definition_text: str = definitions.DEFINITION_TEXT_REAL
    prompt_version: str = prompts.DEFAULT_PROMPT_VERSION
    strict_counts: bool = True            # 要求 visible/evaluable 自洽计数，不自洽则重试
    counts_retry_extra: int = 1           # 计数不自洽时的额外重试次数（0 = 只标记无效）
    sample_position: str = "middle"       # start | middle | end（代表帧在窗口内的位置）
    # ---- FRAME-2/3：采样策略（网格唯一来源；offset 只过滤不平移，默认 0/0）----
    sampling_start_offset_ms: int = 0
    sampling_end_offset_ms: int = 0
    sampling_use_batch_extraction: bool = False   # 批量仅在等价性证明通过后启用
    frame_max_width: int = 1600
    frame_tile_grid: str = "1x1"          # 1x1 | 2x1 | 2x2 | 3x2 …
    frame_tile_overlap: float = 0.08
    min_frame_pixels: int = 320 * 240
    min_legible_ratio: float = 0.60
    # ---- v0.2 Round 2：三档质量阈值（以 coverage = evaluable/visible 为准）----
    quality_valid_threshold: float = 0.70    # coverage >= 0.70  → VALID
    quality_review_threshold: float = 0.50   # coverage >= 0.50  → REVIEW（否则 LOW_QUALITY）
    quality_threshold_hysteresis: float = 0.0  # >0 时在阈值附近用滞回减少翻转（默认关闭）
    # ---- v0.2 Round 3：自适应三帧复核（仅对不稳定窗口，禁止全量二次调用）----
    review_enabled: bool = True
    review_unknown_ratio_threshold: float = 0.50   # UNKNOWN 占比 >= 该值 ⇒ 复核
    review_sudden_change_threshold: float = 0.20   # 与前一有效窗口比率突变 >= 该值 ⇒ 复核
    review_adjacent_frame_offset_ms: int = 500     # 相邻帧偏移（t±500ms）
    review_total_max_attempts: int = 3             # 一次复核的帧数上限（含代表帧）
    review_on_low_quality: bool = False            # 默认不复核 LOW_QUALITY（不扩大调用量）
    # FRAME-4：复核用**一次多图请求**（决策③）；不支持时回退逐帧单图
    review_multi_image: bool = True
    quality_read_retries: int = 1
    analysis_concurrency: int = 2
    frames_retain: int = 0                # 每个 job 保留多少张代表帧（0 = 全删）
    frames_public_base: str = ""          # 留空表示不提供帧调试入口
    # ---- FRAME-5：工作目录生命周期（plan §16）----
    failed_task_retention_hours: float = 12.0   # 失败任务临时目录保留时长；成功即删
    # ---- FRAME-6：性能与成本可观测性 ----
    perf_metrics_enabled: bool = True           # 分阶段耗时/调用数/字节数落库并随 GET 返回
    cost_currency: str = "CNY"
    # 单价必须由部署方提供：模块不内置任何价格，未配置时只报 token 用量（cost=null）
    vision_price_prompt_per_1k: float = 0.0
    vision_price_completion_per_1k: float = 0.0

    # ---- 视觉判定（阿里云百炼 / DashScope）----
    vision_provider: str = "qwen-vl"      # qwen-vl | heuristic
    vision_api_key: str = ""
    vision_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    vision_model: str = "qwen3-vl-plus"
    vision_fallback_model: str = "qwen3-vl-flash"
    vision_timeout_s: float = 120.0
    vision_max_retries: int = 2
    vision_temperature: float = 0.0
    vision_max_tokens: int = 512
    # Round 4 探测结论：seed 与 enable_thinking=false 对该任务没有稳定性收益（20 次对照等价），
    # 因此默认不发送；如需实验可显式打开。
    vision_seed: int | None = None
    vision_disable_thinking: bool = False
    vision_max_image_bytes: int = 9_000_000
    vision_max_long_side: int = 2048
    heuristic_synthetic: bool = False     # 仅演练：heuristic 分析器返回合成计数

    # ---- 合成 provider（仅联调/演示）----
    enable_fixture_provider: bool = False
    fixture_provider_id: str = "head-up-demo"
    fixture_provider_version: str = "demo-1.0"
    fixture_demo_class_session_id: str = "session-demo-001"
    fixture_demo_media_id: str = "media-demo-001"
    fixture_demo_sha256: str = "a" * 64

    extra: dict[str, Any] = field(default_factory=dict)

    # ------------------------------------------------------------------
    @classmethod
    def load(cls, path: str | os.PathLike[str] | None = None,             env: Mapping[str, str] | None = None) -> "Config":
        import os as _os  # 局部导入，便于测试注入

        env = dict(_os.environ if env is None else env)
        raw: dict[str, Any] = {}
        cfg_path = Path(path) if path else Path(str(env.get("HEADUP_CONFIG", "config/headup.config.json")))
        if cfg_path.is_file():
            raw = json.loads(cfg_path.read_text(encoding="utf-8"))
            if not isinstance(raw, dict):
                raise ValueError(f"配置文件必须是 JSON 对象: {cfg_path}")

        def pick(env_name: str, json_name: str, default: Any) -> Any:
            if env_name in env and str(env[env_name]).strip() != "":
                return env[env_name]
            if json_name in raw:
                return raw[json_name]
            return default

        def as_bool(value: Any) -> bool:
            if isinstance(value, bool):
                return value
            return str(value).strip().lower() in _TRUE

        def as_tuple(value: Any) -> tuple[str, ...]:
            if value is None:
                return ()
            if isinstance(value, str):
                return tuple(item.strip() for item in value.split(",") if item.strip())
            return tuple(str(item) for item in value)

        tokens = as_tuple(pick("MODULE_BEARER_TOKENS", "bearerTokens", []))
        host_allow = as_tuple(pick("MEDIA_HOST_ALLOW_LIST", "hostAllowList", []))
        definition_id = str(pick("HEADUP_DEFINITION_ID", "definitionId",
                                 definitions.DEFINITION_ID_REAL))
        definition_text = _resolve_definition_text(
            definition_id, str(pick("HEADUP_DEFINITION_TEXT", "definitionText", "")))

        return cls(
            provider_id=str(pick("MODULE_PROVIDER_ID", "providerId", "head-up-local")),
            module_version=str(pick("MODULE_VERSION", "moduleVersion", "head-up-0.1.0")),
            api_version=str(pick("MODULE_API_VERSION", "apiVersion", "1.0")),
            bearer_tokens=tokens,
            server_host=str(pick("SERVER_HOST", "serverHost", "127.0.0.1")),
            server_port=int(pick("SERVER_PORT", "serverPort", 8080)),
            db_path=str(pick("DB_PATH", "dbPath", "./.data/headup.sqlite3")),
            workers=max(1, int(pick("WORKERS", "workers", 1))),
            max_body_bytes=int(pick("MAX_BODY_BYTES", "maxBodyBytes", 2_097_152)),
            max_media_bytes=int(pick("MEDIA_MAX_DOWNLOAD_BYTES", "maxMediaBytes", 2_147_483_648)),
            max_duration_ms=int(pick("MEDIA_MAX_DURATION_MS", "maxDurationMs", 7_200_000)),
            max_windows=int(pick("MAX_WINDOWS", "maxWindows", 20_000)),
            max_concurrent_jobs=int(pick("MAX_CONCURRENT_JOBS", "maxConcurrentJobs", 4)),
            poll_interval_ms=int(pick("POLL_INTERVAL_MS", "pollIntervalMs", 2000)),
            poll_interval_max_ms=int(pick("POLL_INTERVAL_MAX_MS", "pollIntervalMaxMs", 10000)),
            work_dir=str(pick("WORK_DIR", "workDir", "./.work")),
            ffmpeg_path=str(pick("FFMPEG_PATH", "ffmpegPath", "ffmpeg")),
            ffprobe_path=str(pick("FFPROBE_PATH", "ffprobePath", "ffprobe")),
            host_allow_list=host_allow,
            allow_loopback_http=as_bool(pick("MEDIA_ALLOW_LOOPBACK_HTTP", "allowLoopbackHttp", False)),
            media_connect_timeout_s=float(pick("MEDIA_CONNECT_TIMEOUT", "mediaConnectTimeoutS", 10)),
            media_read_timeout_s=float(pick("MEDIA_DOWNLOAD_TIMEOUT", "mediaReadTimeoutS", 600)),
            media_total_timeout_s=float(pick("MEDIA_TOTAL_TIMEOUT", "mediaTotalTimeoutS", 900)),
            media_probe_timeout_s=float(pick("MEDIA_PROBE_TIMEOUT", "mediaProbeTimeoutS", 180)),
            duration_tolerance_ms=int(pick("MEDIA_DURATION_TOLERANCE_MS", "durationToleranceMs", 2000)),
            keep_media_files=as_bool(pick("MEDIA_KEEP_FILES", "keepMediaFiles", False)),
            download_retries=int(pick("MEDIA_DOWNLOAD_RETRIES", "downloadRetries", 2)),
            definition_id=definition_id,
            definition_text=definition_text,
            prompt_version=str(pick("HEADUP_PROMPT_VERSION", "promptVersion",
                                    prompts.DEFAULT_PROMPT_VERSION)),
            strict_counts=as_bool(pick("HEADUP_STRICT_COUNTS", "strictCounts", True)),
            counts_retry_extra=int(pick("HEADUP_COUNTS_RETRY_EXTRA", "countsRetryExtra", 1)),
            sample_position=str(pick("HEADUP_SAMPLE_POSITION", "samplePosition", "middle")),
            sampling_start_offset_ms=int(pick("HEADUP_SAMPLING_START_OFFSET_MS",
                                              "samplingStartOffsetMs", 0)),
            sampling_end_offset_ms=int(pick("HEADUP_SAMPLING_END_OFFSET_MS",
                                            "samplingEndOffsetMs", 0)),
            sampling_use_batch_extraction=as_bool(pick("HEADUP_SAMPLING_BATCH",
                                                       "samplingUseBatchExtraction", False)),
            frame_max_width=int(pick("FRAME_MAX_WIDTH", "frameMaxWidth", 1600)),
            frame_tile_grid=str(pick("FRAME_TILE_GRID", "frameTileGrid", "1x1")),
            frame_tile_overlap=float(pick("FRAME_TILE_OVERLAP", "frameTileOverlap", 0.08)),
            min_frame_pixels=int(pick("MIN_FRAME_PIXELS", "minFramePixels", 320 * 240)),
            min_legible_ratio=float(pick("MIN_LEGIBLE_RATIO", "minLegibleRatio", 0.60)),
            quality_valid_threshold=float(pick("HEADUP_QUALITY_VALID_THRESHOLD",
                                               "qualityValidThreshold", 0.70)),
            quality_review_threshold=float(pick("HEADUP_QUALITY_REVIEW_THRESHOLD",
                                                "qualityReviewThreshold", 0.50)),
            quality_threshold_hysteresis=float(pick("HEADUP_QUALITY_HYSTERESIS",
                                                    "qualityThresholdHysteresis", 0.0)),
            review_enabled=as_bool(pick("HEADUP_REVIEW_ENABLED", "reviewEnabled", True)),
            review_unknown_ratio_threshold=float(pick("HEADUP_REVIEW_UNKNOWN_RATIO_THRESHOLD",
                                                      "reviewUnknownRatioThreshold", 0.50)),
            review_sudden_change_threshold=float(pick("HEADUP_REVIEW_SUDDEN_CHANGE_THRESHOLD",
                                                      "reviewSuddenChangeThreshold", 0.20)),
            review_adjacent_frame_offset_ms=int(pick("HEADUP_REVIEW_ADJACENT_FRAME_OFFSET_MS",
                                                     "reviewAdjacentFrameOffsetMs", 500)),
            review_total_max_attempts=int(pick("HEADUP_REVIEW_TOTAL_MAX_ATTEMPTS",
                                               "reviewTotalMaxAttempts", 3)),
            review_on_low_quality=as_bool(pick("HEADUP_REVIEW_ON_LOW_QUALITY",
                                               "reviewOnLowQuality", False)),
            review_multi_image=as_bool(pick("HEADUP_REVIEW_MULTI_IMAGE",
                                            "reviewMultiImage", True)),
            quality_read_retries=int(pick("QUALITY_READ_RETRIES", "qualityReadRetries", 1)),
            analysis_concurrency=max(1, int(pick("ANALYSIS_CONCURRENCY", "analysisConcurrency", 2))),
            frames_retain=int(pick("FRAMES_RETAIN", "framesRetain", 0)),
            frames_public_base=str(pick("FRAMES_PUBLIC_BASE", "framesPublicBase", "")),
            failed_task_retention_hours=float(pick("HEADUP_FAILED_TASK_RETENTION_HOURS",
                                                   "failedTaskRetentionHours", 12.0)),
            perf_metrics_enabled=as_bool(pick("HEADUP_PERF_METRICS", "perfMetricsEnabled", True)),
            cost_currency=str(pick("HEADUP_COST_CURRENCY", "costCurrency", "CNY")),
            vision_price_prompt_per_1k=float(pick("VISION_PRICE_PROMPT_PER_1K",
                                                  "visionPricePromptPer1K", 0.0)),
            vision_price_completion_per_1k=float(pick("VISION_PRICE_COMPLETION_PER_1K",
                                                      "visionPriceCompletionPer1K", 0.0)),
            vision_provider=str(pick("VISION_PROVIDER", "visionProvider", "qwen-vl")),
            vision_api_key=str(pick("DASHSCOPE_API_KEY", "visionApiKey", "")),
            vision_base_url=str(pick("VISION_BASE_URL", "visionBaseUrl",
                                     "https://dashscope.aliyuncs.com/compatible-mode/v1")),
            vision_model=str(pick("VISION_MODEL", "visionModel", "qwen3-vl-plus")),
            vision_fallback_model=str(pick("VISION_FALLBACK_MODEL", "visionFallbackModel",
                                           "qwen3-vl-flash")),
            vision_timeout_s=float(pick("VISION_TIMEOUT_SECONDS", "visionTimeoutS", 120)),
            vision_max_retries=int(pick("VISION_MAX_RETRIES", "visionMaxRetries", 2)),
            vision_temperature=float(pick("VISION_TEMPERATURE", "visionTemperature", 0.0)),
            vision_max_tokens=int(pick("VISION_MAX_TOKENS", "visionMaxTokens", 512)),
            vision_seed=_optional_int(pick("VISION_SEED", "visionSeed", "")),
            vision_disable_thinking=as_bool(pick("VISION_DISABLE_THINKING",
                                                 "visionDisableThinking", False)),
            vision_max_image_bytes=int(pick("VISION_MAX_IMAGE_BYTES", "visionMaxImageBytes",
                                            9_000_000)),
            vision_max_long_side=int(pick("VISION_MAX_LONG_SIDE", "visionMaxLongSide", 2048)),
            heuristic_synthetic=as_bool(pick("HEADUP_HEURISTIC_SYNTHETIC", "heuristicSynthetic",
                                             False)),
            enable_fixture_provider=as_bool(pick("ENABLE_FIXTURE_PROVIDER", "enableFixtureProvider",
                                                 False)),
            fixture_provider_id=str(pick("FIXTURE_PROVIDER_ID", "fixtureProviderId",
                                         "head-up-demo")),
            fixture_provider_version=str(pick("FIXTURE_PROVIDER_VERSION",
                                              "fixtureProviderVersion", "demo-1.0")),
            fixture_demo_class_session_id=str(pick("FIXTURE_DEMO_CLASS_SESSION_ID",
                                                   "fixtureDemoClassSessionId",
                                                   "session-demo-001")),
            fixture_demo_media_id=str(pick("FIXTURE_DEMO_MEDIA_ID", "fixtureDemoMediaId",
                                           "media-demo-001")),
            fixture_demo_sha256=str(pick("FIXTURE_DEMO_SHA256", "fixtureDemoSha256", "a" * 64)),
        )

    # ------------------------------------------------------------------
    def allowed_definition_ids(self) -> tuple[str, ...]:
        """本部署登记的观测口径：真实口径 + 已登记的内置口径（含合成演示口径）。"""
        ids = [self.definition_id]
        if self.enable_fixture_provider:
            ids.extend(definitions.DEFINITIONS)
        seen: list[str] = []
        for item in ids:
            if item not in seen:
                seen.append(item)
        return tuple(seen)

    def health(self) -> dict[str, Any]:
        """GET /api/v1/health 的能力边界（不含密钥与队列详情）。"""
        return {
            "status": "UP",
            "providerId": self.provider_id,
            "moduleVersion": self.module_version,
            "apiVersion": self.api_version,
            "moduleType": "HEAD_UP",
            "routes": [
                "POST /api/v1/jobs",
                "GET /api/v1/jobs/{jobId}",
                "GET /api/v1/health",
            ],
            "definitionId": self.definition_id,
            "promptVersion": self.prompt_version,
            "metricVersion": METRIC_VERSION,
            "quality": {
                "coverageValidThreshold": self.quality_valid_threshold,
                "coverageReviewThreshold": self.quality_review_threshold,
                "courseMeanAggregation": "sum_ratio（SUM(head_up_count)/SUM(evaluable_count)，只含 VALID 窗口）",
                "note": "REVIEW 与 LOW_QUALITY 都不进入课程均值；REVIEW 触发三帧复核",
            },
            "review": {
                "enabled": self.review_enabled,
                "unknownRatioThreshold": self.review_unknown_ratio_threshold,
                "suddenChangeThreshold": self.review_sudden_change_threshold,
                "adjacentFrameOffsetMs": self.review_adjacent_frame_offset_ms,
                "maxFramesPerReview": self.review_total_max_attempts,
                "reviewLowQuality": self.review_on_low_quality,
                "note": "只对不稳定窗口追加调用；正常窗口仍为 1 帧",
            },
            "samplingPolicyVersion": SAMPLING_POLICY_VERSION,
            "performance": {
                "metricsEnabled": self.perf_metrics_enabled,
                "exposedAt": "GET /api/v1/jobs/{jobId} 的增量字段 metrics（契约未定义，可忽略）",
                "costModel": {
                    "currency": self.cost_currency,
                    "configured": bool(self.vision_price_prompt_per_1k
                                       or self.vision_price_completion_per_1k),
                    "note": ("成本 = 实测 token × 部署方配置单价；未配置单价时只报 token，"
                             "不猜价格"),
                },
            },
            "windowMs": {
                "mode": "clamped_to_media",
                "note": "请求给出 windowMs；末窗可短",
            },
            "sampleMode": "REPRESENTATIVE_FRAME",
            "outputModes": ["COUNTS", "RATIO_ONLY"],
            "limits": {
                "maxBodyBytes": self.max_body_bytes,
                "maxMediaBytes": self.max_media_bytes,
                "maxDurationMs": self.max_duration_ms,
                "maxWindows": self.max_windows,
                "workers": self.workers,
                "analysisConcurrency": self.analysis_concurrency,
            },
            "auth": {
                "bearerRequired": True,
                "tokensConfigured": len(self.bearer_tokens) > 0,
                "healthRequiresBearer": False,
            },
            "vision": {
                "provider": self.vision_provider,
                "model": self.vision_model,
                "fallbackModel": self.vision_fallback_model,
                "credentialConfigured": bool(self.vision_api_key),
                "generationParameters": {
                    "temperature": self.vision_temperature,
                    "maxTokens": self.vision_max_tokens,
                    "seed": self.vision_seed,
                    "disableThinking": self.vision_disable_thinking,
                    "note": ("Round 4 实测：temperature=0 基线与 seed=42 / enable_thinking=false "
                             "在 20 次重复下统计等价（极差 1 人），故默认不发送 seed 与 thinking 开关"),
                },
            },
            "media": {
                "ffmpegConfigured": bool(self.ffmpeg_path),
                "hostAllowListConfigured": bool(self.host_allow_list),
                "allowLoopbackHttp": self.allow_loopback_http,
            },
            "fixtureProvider": {
                "enabled": self.enable_fixture_provider,
                "providerId": self.fixture_provider_id,
                "note": "合成 provider 只输出人工合成观测，绝不代表真实模型处理结果",
            },
        }
