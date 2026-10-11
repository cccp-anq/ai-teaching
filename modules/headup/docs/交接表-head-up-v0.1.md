# 交接表 · 抬头率模块 v0.1（契约 §7 必填项）

日期：2026-09-14。状态：**待主项目登记**；本表是我们的声明值，未填数值一律写"未确定"，不编造。
对应契约：[`classroom-analysis-contract-v0.1.md`](<主项目文档包>/classroom-analysis-contract-v0.1.md)、
抬头专用文档 [`head-up-module-v0.1.md`](<主项目文档包>/head-up-module-v0.1.md)。

> ⚠️ **所有数值以本部署实际 `/api/v1/health` 输出为准**。下表是本地默认值示例；主项目登记时必须
> 按真实部署的响应抄录 `providerId` / `moduleVersion`，不要抄本文。

---

## 1. 路由与基地址

| 项 | 值 |
| --- | --- |
| baseUrl（本地默认） | `http://127.0.0.1:8080` |
| 完整路径 | `baseUrl + /api/v1/jobs`、`/api/v1/jobs/{jobId}`、`/api/v1/health` |
| 传输 | 生产必须 HTTPS（反代终止）；开发回环 HTTP 需显式配置 |
| 请求体 | `application/json; charset=utf-8`，camelCase |
| 认证 | `Authorization: Bearer <MODULE_BEARER_TOKENS 中的一把>`；**未配置凭证时一律 401（失败关闭）** |
| 健康检查 | `/api/v1/health` 与 `/health` **不要求 Bearer**（便于存活探测，见 §9 偏差 1） |

## 2. 部署身份

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `providerId`（真实路径） | `head-up-local` | 环境变量 `MODULE_PROVIDER_ID`；主项目按值登记 |
| `moduleVersion`（真实路径） | `head-up-0.1.0` | 环境变量 `MODULE_VERSION`；换模型/换抽帧/换口径都要改 |
| `providerId`（合成路径） | `head-up-demo` | **仅当** `ENABLE_FIXTURE_PROVIDER=true` 且请求命中演示身份时使用 |
| `moduleVersion`（合成路径） | `demo-1.0` | 与主项目文档包冻结样例一致 |
| `definitionId` | `head-up-qwen-vl-v1` | 观测口径 ID；口径文本与其成对维护（见 §7） |
| `apiVersion` / `schemaVersion` | `1.0` / `1.0` | 契约线格式版本 |

## 3. 能力边界

| 项 | 声明 |
| --- | --- |
| 支持 `moduleType` | 仅 `HEAD_UP`（其它类型 403） |
| `outputMode` | `COUNTS`（首选）与 `RATIO_ONLY`；**不支持的模式返回 422，不静默切换** |
| `sampleMode` | 固定 `REPRESENTATIVE_FRAME`（每窗一帧） |
| **观测口径版本** | `promptVersion = headup-strict-v0.2`（严格观察口径，默认）：只见后脑/背对镜头必须 UNKNOWN，禁止推测 |
| **指标版本** | `metricVersion = head-up-rate-v0.2`：`head_up_rate = up/evaluable`、`coverage = evaluable/visible`，均由程序计算 |
| **质量分档（v0.2）** | `coverage >= 0.70 → VALID`；`0.50 ≤ coverage < 0.70 → REVIEW`（契约新增值）；`coverage < 0.50 → LOW_QUALITY`；`visible=0 → NO_SUBJECTS`。阈值可配置（`HEADUP_QUALITY_VALID_THRESHOLD` / `..._REVIEW_THRESHOLD`） |
| **课程均值（stats-v2）** | `meanRate = SUM(head_up_count)/SUM(evaluable_count)`，**只含 VALID 窗口**；`v1DurationWeightedMeanRate` 保留 v1 时长加权值作对照。⚠️ 与契约 v0.1 的 stats-v1 口径不同，需主项目确认展示哪一个 |
| **采样策略版本** | `samplingPolicyVersion = 10s-single-frame-v0.2`；触发复核的窗口为三帧估计（`resultKind=WINDOW_ESTIMATE_MULTI_FRAME`） |
| **自适应复核（Round 3）** | 仅对不稳定窗口追加调用：REVIEW / UNKNOWN 占比 ≥0.50 / 与前一有效窗口比率突变 ≥0.20（阈值均可配）。复核用 `t±500ms` **相邻帧**（重新抽帧，不复用同一张图），取**中位数帧**；三帧仍不足 ⇒ `LOW_QUALITY` + `REVIEW_INSUFFICIENT_EVIDENCE`。稳定窗口始终 1 次调用 |
| **实测调用量** | 真实 50 秒 5 窗素材：9 次调用（1.8/窗，触发 3 次复核）；关闭复核则恒为 1 次/窗 |
| `windowMs` | 请求给定，末窗可短；窗口数上限 `MAX_WINDOWS=20000` |
| 代表帧位置 | `HEADUP_SAMPLE_POSITION`（`start`/`middle`/`end`，默认 `middle`） |
| 处理范围 | 当前等于 `analysisRange`；支持返回部分处理并在 `warnings` 标 `PARTIAL_PROCESSING` |
| 单结果返回 | 是（不分页、不截断）；`windows` 条数上限同 `MAX_WINDOWS` |
| 多镜头/变速/拼接 | **不支持**（契约 §1）；检测到无法恢复的时间映射应返回 `UNSUPPORTED_MEDIA` |
| 出席率 / 实到人数 | **不做**（契约与抬头专用文档明确排除；分母不是应到/实到） |
| 人脸身份识别 / 学生档案 | **不做** |
| 教师评分 / 课程评价 | **不做** |

## 4. 观测口径（必须与主项目登记的 definitionId 一致）

| 项 | 值 |
| --- | --- |
| `definitionId` | `head-up-qwen-vl-v1`（真实路径）／`head-up-demo-v1`（合成路径） |
| 分子 `headUpCount` | 代表帧中判定为**抬头**（面向讲台/正前方/黑板/屏幕）的人数 |
| 分母 `validObservedCount` | 代表帧中**可判断抬头状态**的人数 = `head_up + head_down` |
| 排除对象 | 侧背对镜头、被前排或设备遮挡、画面过糊/过小到无法判断（记为 `unknown`，**不进分母**） |
| 遮挡如何判无效 | 可判断人数为 0 => `LOW_QUALITY(UNREADABLE_ALL_SUBJECTS)`；整帧无人 => `NO_SUBJECTS(NO_VISIBLE_PERSON)` |
| 低可判断比例阈值 | **v0.2 起以 `coverage` 分档**：`>=0.70 VALID`／`0.50–0.70 REVIEW`／`<0.50 LOW_QUALITY`；`MIN_LEGIBLE_RATIO` 仅作为旧口径兼容字段 |
| 每窗如何选帧 | 窗口内取 1 帧（默认中点）；触发复核的窗口为 `t/t±500ms` 三帧估计 |
| 窗口是否代表估计 | **是**：`SINGLE_FRAME` 或 `WINDOW_ESTIMATE_MULTI_FRAME`（时间点附近窗口估计），不是连续行为测量 |
| 判定实现 | 阿里云百炼（DashScope），OpenAI 兼容路径；**主统计模型 Flash**（`VISION_MODEL=qwen3-vl-flash`），Plus 仅作对照且不允许混入同一曲线 |
| 生成参数（Round 4 实测） | 只发送 `temperature=0`；**不发送 `seed`**（20 次对照与基线等价，无稳定性收益）；不发送 thinking 开关 |
| 单帧随机性（实测） | `head_up` σ≈0.37 人、极差 1 人；`coverage` σ≈0.026；质量状态连续调用翻转 15–30%（集中在阈值附近） |
| 请求用量（实测） | 严格提示词下 **1377 prompt tokens/帧**、约 60 completion tokens；45 分钟课（270 帧）≈ ¥0.14（flash）/ ¥0.55（plus） |
| 真实调用验证 | ✅ **2026-09-14 已用真实凭据跑通**：50 秒 5 窗真实画面媒体，202 → `SUCCEEDED` → 统计，端到端 8.1–36.3 秒（含复核；波动来自服务端延迟） |
| 输出可复现性 | ⚠️ **同帧 ±1 人抖动，整段均值 ±3 个百分点**；阈值附近窗口质量可能翻转。报告须标"本次运行估计值" |
| 成本实测 | 1140 prompt tokens/帧 ⇒ 45 分钟课（270 帧）≈ ¥0.45（plus）/ ¥0.11（flash）；耗时约视频时长的 1/4–1/6 |
| 分块 | `FRAME_TILE_GRID`（默认 `1x1` 整帧；可 `2x2`/`3x2` 分块计数后求和）。**实测 `2x2` 在本轮素材上更差**（legible 0.47 vs 0.78，耗时 ×4） |
| 判定提示词版本 | `headup-strict-v0.2`（严格观察口径，默认）；`headup-prompt-1` 保留可显式启用。见 `src/headup/prompts.py` |
| 版本固化（Round 4） | `payload.generation` 提供 `modelName` / `modelVersion` / `promptVersion` / `samplingPolicyVersion` / `metricVersion` / `statisticsVersion`；逐窗提供 `modelUsed` / `modelRequestId` |
| `modelVersion` 说明 | 服务端**不返回不可变快照号**（实测 `system_fingerprint=null`）⇒ 记录"请求名 + 首次请求 id"，不伪造快照号 |
| Flash/Plus 分离 | 逐窗记录模型；出现非主模型窗口时输出 `FALLBACK_MODEL_USED:<windowIds>` 告警并在 `generation.windowsWithOtherModel` 列出；**禁止静默混入同一曲线** |

> 实测细节与可复现步骤见 [`fixtures/head-up-module-v0.1/测试记录-真实模型.md`](fixtures/head-up-module-v0.1/测试记录-真实模型.md)。

详见 [`docs/定义说明-head-up.md`](定义说明-head-up.md)（判定规则、分母、排除、遮挡、选帧、阈值、局限）。

## 5. 时间映射

| 项 | 规则 |
| --- | --- |
| `timeBase` | 固定 `MEDIA_START_MS` |
| 当前映射 | 整条媒体处理 ⇒ `原媒体毫秒 ≡ 处理帧毫秒`（`offsetMs = 0`） |
| 唯一改动点 | `src/headup/frames.py: time_mapper(offset_ms)`（将来支持单段裁剪只改这里） |
| 抽样点 | `sampleAtMs ∈ [startMs, endMs)`，落在窗口内，使用原媒体毫秒 |
| 区间语义 | `[startMs, endMs)` 左闭右开 |

## 6. 幂等、版本与终态

| 项 | 规则 |
| --- | --- |
| 幂等键 | `clientRequestId`（库上唯一约束）；`Idempotency-Key` 若出现必须等于它，否则 **422** |
| 指纹组成 | `schemaVersion`、`clientRequestId`、`moduleType`、`classSessionId`、`mediaId`、`media.sha256/durationMs/mimeType`、`analysisRange`、`parameters` |
| **不**参与指纹 | `sourceUrl`、`urlExpiresAt`（可续期的下载凭据） |
| 同键同内容 | **200**，复用同一 `jobId`（含仅续期下载凭据；非终态继续处理，终态原样返回） |
| 同键异内容 | **409 IDEMPOTENCY_CONFLICT**，不静默改任务 |
| 终态 | `SUCCEEDED`/`FAILED` 后 `revision=1` 且不可变；纠错/换模型/换口径必须新 `clientRequestId` |
| 状态迁移 | `QUEUED → RUNNING → 终态`；终态后迟到状态不回退 |
| 结果唯一键 | `(providerId, jobId, revision)`；本版同 job 不支持修改终态 |
| 重启恢复 | 未终态任务通过租约被 worker 重新认领（`attempts` 递增） |

## 7. 凭证注入与轮换

| 项 | 做法 |
| --- | --- |
| 模块侧 Bearer | 环境变量 `MODULE_BEARER_TOKENS`（逗号分隔多把，支持轮换期双活）；仅从环境/密钥管理注入 |
| 视觉模型凭证 | 环境变量 `DASHSCOPE_API_KEY`；**不进 GitHub、不进 fixture、不进日志、不回包** |
| 轮换 | 需重启进程（部署级，不按 job 细分范围） |
| 主项目→模块 | HTTPS + Bearer；模块不需要任何课程/报告读取能力 |
| 模块→主项目 | 不回调（未实现）；只做被动查询 |

## 8. 容量与超时（声明值，均为**已强制执行**）

| 配置 | 环境变量 | 默认值 | 行为 |
| --- | --- | --- | --- |
| 请求体上限 | `MAX_BODY_BYTES` | 2 MiB | 超限 → **413 PAYLOAD_TOO_LARGE** |
| 媒体体积上限 | `MEDIA_MAX_DOWNLOAD_BYTES` | 2 GiB | 超限 → 任务 `FAILED` + `PAYLOAD_TOO_LARGE` |
| 媒体时长上限 | `MEDIA_MAX_DURATION_MS` | 2 小时 | 超限 → **422** |
| 窗口数上限 | `MAX_WINDOWS` | 20000 | 超限 → **422** |
| 下载连接/读超时 | `MEDIA_CONNECT_TIMEOUT` / `MEDIA_DOWNLOAD_TIMEOUT` | 10s / 600s | 超时 → 任务 `FAILED` + `PROCESSING_FAILED`(retryable) |
| 探测/抽帧超时 | `MEDIA_PROBE_TIMEOUT` | 180s | 超时 → `DECODE_FAILED` |
| 视觉模型超时 | `VISION_TIMEOUT_SECONDS` | 120s | 超时 → `VISION_PROVIDER_ERROR`(retryable) |
| 视觉模型重试 | `VISION_MAX_RETRIES` | 2（每个模型） | 先主模型后 `VISION_FALLBACK_MODEL` |
| worker 数 | `WORKERS` | 1 | 任务级并发；`ANALYSIS_CONCURRENCY` 为声明项（当前窗口顺序处理） |
| 处理耗时 | — | **未确定** | 未做压测；单帧模型调用约 2–8s 量级（未系统实测） |
| 吞吐 / SLA | — | **未确定** | 契约 §6 明确本轮不编造未测 SLA |
| 结果响应时间 | 立即返回 202 | — | 处理在 worker 线程中异步进行 |

## 9. 与转写模块不一致的地方（需联调评审）

| # | 差异 | 我们的实现 | 理由 |
| --- | --- | --- | --- |
| 1 | `/health` 是否要 Bearer | **不要求**（其余路由一律要求） | 存活探测/反代探针通常不带凭证；响应不含密钥与队列详情 |
| 2 | 请求体超限返回形态 | **413 PAYLOAD_TOO_LARGE**（真正强制） | 契约 §5 列出了 413；转写模块当前不会返回 413 |
| 3 | 媒体体积超限返回形态 | 任务级 `PAYLOAD_TOO_LARGE`（HTTP 200 + 终态 FAILED） | 契约 §5 任务级错误码未列该码 ⇒ **补充码，见表 §10** |
| 4 | 下载地址非 https / 主机不在白名单 / 解析到内网 | 任务级 `UNSUPPORTED_MEDIA`（不可重试） | 归为部署安全策略而非任务内容错误；同样属补充语义，见表 §10 |
| 5 | `GET /api/v1/jobs/{jobId}` 的**增量字段** | 额外返回 `progress`（内部阶段）、`samples`（逐窗口样本）、`metrics`（实测性能指标） | 契约 v0.1 未定义这三个字段；主项目**可以完全忽略**，忽略后契约字段逐字段与冻结样例一致 |

### 9.1 增量字段说明（FRAME-5/6，主项目可忽略）

| 字段 | 内容 | 是否进契约 payload |
| --- | --- | --- |
| `progress` | `currentStage`（PENDING/PROBING_VIDEO/EXTRACTING_PRIMARY/ANALYZING_PRIMARY/REVIEWING/AGGREGATING/SUCCESS）、`errorStage` | 否 |
| `samples` | 逐窗口样本（与 `payload.windows` 同源，含质量/来源/五版本，**不含临时帧路径**）；`timestampMs` 为 `null` 表示该窗口**没有采样点**（抽帧失败，看 `qualityReason=FRAME_EXTRACTION_FAILED`，用 `windowStartMs/windowEndMs` 定位），排序按 `COALESCE(timestampMs, windowStartMs)` | 否 |
| `metrics` | 实测性能：`stageTimingsMs`、`counters`、`models`、`tokens`、`diskBytes`、`derived.reviewRatio`、`cost`、`notes`（抽帧方式、真实 HTTP 请求数、样本写入条数 `samplesWritten`、落库失败窗口 `samplesPersistFailed`） | 否（payload 保持确定性） |

> 成本只有在部署方配置了 `VISION_PRICE_PROMPT_PER_1K` / `VISION_PRICE_COMPLETION_PER_1K` 时才给出金额，
> 否则 `cost = null`，只报实测 token —— 模块不内置任何价格，估算 ≠ 账单。
>
> 逐窗口样本**逐行隔离写入**：单个窗口写不进去只影响该窗口，其余窗口照写，失败窗口会记进
> `metrics.notes.samplesPersistFailed`（不会再出现"任务成功、整批审计样本却丢失"的情况）。

## 10. 任务级错误码（含补充码）

契约 §5 已列举：`MEDIA_URL_EXPIRED`、`MEDIA_HASH_MISMATCH`、`UNSUPPORTED_MEDIA`、`DECODE_FAILED`、`PROCESSING_FAILED`。

**本模块补充（需主项目适配器识别，否则会落到"未知错误"分支）**：

| code | 触发 | retryable | 主项目该做什么 |
| --- | --- | --- | --- |
| `PAYLOAD_TOO_LARGE` | 媒体体积超本部署上限 | false | 缩小媒体或与模块协商上限 |
| `VISION_PROVIDER_ERROR` | 视觉模型超时/限流/返回不可解析 | true/false | 看 `message`；临时类可换新 job 重试 |
| `VISION_CREDENTIAL_MISSING` | 本部署未配置 `DASHSCOPE_API_KEY` | false | 属部署问题；修配置后换新 key 重提 |
| `FFMPEG_MISSING` | 本机缺少 ffmpeg/ffprobe | false | 属部署问题；装工具或改 `FFMPEG_PATH` |

> 未知 `code` 请不要当作成功或静默忽略：`message` 里不会含堆栈、凭证或媒体地址。

## 11. 媒体获取（契约 §3）

| 项 | 声明 |
| --- | --- |
| 协议 | 仅 `https`；`http` 仅在 `MEDIA_ALLOW_LOOPBACK_HTTP=true` 的本地联调下允许 |
| 主机白名单 | `MEDIA_HOST_ALLOW_LIST`（留空=不限，但仍拒绝内网/环回/链路本地/组播/CGNAT/保留地址） |
| 重定向 | 最多 3 跳，**每跳重新校验地址** |
| hash 核验 | 边下边算 SHA-256，与 `media.sha256` 比对；不符 → `MEDIA_HASH_MISMATCH`（不可重试） |
| 时长核验 | ffprobe 读实际时长；与登记值偏差超过 `MEDIA_DURATION_TOLERANCE_MS`（默认 2000ms）**仅告警**，信封 `durationMs` 仍为请求值，不静默裁切 |
| 临时文件 | 默认任务结束即删除（`MEDIA_KEEP_FILES=true` 可保留排错）；代表帧默认不保留（`FRAMES_RETAIN`） |
| 日志 | 不打印 `sourceUrl`、Bearer、`DASHSCOPE_API_KEY`、完整转写文本 |

## 12. 待主项目确认的问题（联调前必须回答）

1. **容差**：M4 用真实媒体核对起点/中点/末尾时，允许的时间偏差是多少？（与转写模块共用同一个容差更省事）
2. **补充错误码**：§10 的 4 个码是改本模块还是主项目适配器吸收？
3. **`/health` 免鉴权**是否接受？若不接受，我们改为强制 Bearer（改动很小）。
4. **代表帧位置**：`middle` 是否可接受？若主项目希望更贴近"课中真实状态"，可改 `start`/`end` 或将来升级为多帧聚合（需新 `definitionId`）。
5. **`windowMs` 生产取值**：fixture 用 10000；真实课堂建议 10000～30000。主项目侧是否有页面/统计上的约束？
6. **`MIN_LEGIBLE_RATIO=0.60`**：这个阈值会把"画面糊/遮挡多"的窗口判为 `LOW_QUALITY`。主项目是否接受该默认，还是希望按课程类型区分？
7. **分母定义确认**：`validObservedCount = head_up + head_down`（排除不可判断者），与主项目页面文案是否一致？
8. **凭证粒度**：一把部署级 Bearer 是否足够，还是需要按 providerId/任务范围细分？
9. **保留策略**：代表帧是否需要保留供人工复核（`FRAMES_RETAIN` + `FRAMES_PUBLIC_BASE`）？若要，主项目如何受权访问？
10. **真实精度验收**：需要几段真实课堂视频、每段多少窗口的人工标注，才认这个模块"精度验收通过"？（当前**未做过任何真实课堂数据验证**）
11. **提示词是否收紧**（2026-09-14 实测新增）：现行 `headup-prompt-1` 会把"只见后脑/背对镜头"的人算成
    抬头（俯拍机位实测 4 人）；严格版（看不见眼睛一律 `unknown`）能降为 0，但会让部分窗口从 VALID
    变 LOW_QUALITY。**是否升级为 `headup-prompt-2` 需三方决定**——这属于观测口径变更，不是实现细节。
12. **抖动如何呈现**：同帧判读有 ±1 人抖动、整段均值 ±3 个百分点。是①在报告里声明"本次运行估计值"，
    还是②对临界窗口做 2 次判定取一致结果（成本 ×2，且仍非完全确定）？
13. **模型选择**：`qwen3-vl-plus`（0.715）与 `qwen3-vl-flash`（0.588）在同一媒体上差 12.7 个百分点，
    但成本差 4 倍（¥0.45 vs ¥0.11 / 45 分钟课）。主项目页面上要不要显示实际使用的模型？
