# 抬头率模块（HEAD_UP）·《三项目公共接口契约 v0.1》实现

> **一句话**：把主项目已授权的课堂媒体（HTTPS 受控下载地址）取回来，核验 SHA-256，按 `windowMs`
> 抽代表帧、送视觉模型判定头部朝向，最后按**原媒体毫秒轴**输出"抬头率"标准结果——只做抬头观测，
> 不做报告、不做出席率、不给教学打分。
>
> - 对外接口：契约 §2 的三个路由（`POST /api/v1/jobs`、`GET /api/v1/jobs/{jobId}`、`GET /api/v1/health`）
> - 实现栈：**Python 3.11+，核心路径只用标准库**（`http.server` / `sqlite3` / `urllib`）；图像处理用 Pillow
> - 判定引擎：阿里云百炼（DashScope）**`qwen3-vl-plus`**，OpenAI 兼容 HTTP 路径
> - 外部工具：ffmpeg / ffprobe 做探测与抽帧
>
> **所有交接数值（baseUrl、上限、时间映射、并发、超时、凭证注入、能力边界、未确定项）见
> [`docs/交接表-head-up-v0.1.md`](docs/交接表-head-up-v0.1.md)——那是给主项目登记用的权威表格**，
> 本文只讲怎么跑、怎么看。观测口径见 [`docs/定义说明-head-up.md`](docs/定义说明-head-up.md)。

---

## 1. 这个模块在三个项目里的位置

按契约 §1 的边界划分：

| 项目 | 负责 | 不负责 |
| --- | --- | --- |
| **主项目**（ai_teaching，Java 17） | 创建 `classSessionId`/`mediaId`/`clientRequestId`、授权、受控下载地址、标准接收、持久化、统计、分析模型、报告与页面 | 重做识别算法 |
| 转写模块（videoreact，Java） | 音频解码、带时间戳转写、处理范围与质量 | 报告、权限、直写主库 |
| **抬头模块（本仓库）** | 观测口径、采样与姿态判断、分子/分母或比例、质量与区间、标准结果 | **出席率**、教师评分、课程原因分析、人脸身份、直写主库 |

本模块的硬边界（契约 §1 与抬头专用文档 §1）：

- **不访问主项目数据库**，只处理已分配媒体并返回标准结果；
- 信封里的 `providerId` / `moduleVersion` **取自部署配置**，调用方无法伪造；
- 时间一律映射回**原媒体毫秒轴**（`timeBase = MEDIA_START_MS`）；
- 终态不可变：纠错、换模型、换口径都必须用**新的 `clientRequestId`** 建新 job；
- **不用"识别到的人脸数"冒充"可判断抬头状态的人数"**，也绝不用它推算出勤。

> 契约与主项目文档包（`classroom-analysis-contract-v0.1.md`、`head-up-module-v0.1.md`、
> `fixtures/classroom-analysis-v0.1/`）在 **ai_teaching 仓库**里维护，未复制到本仓库。
> 本仓库 `tests/test_contract.py` 会直接读取那份冻结样例做逐字段比对（本机存在时）。

---

## 2. 快速开始

### 2.1 依赖

| 依赖 | 必需性 | 说明 |
| --- | --- | --- |
| Python 3.11+ | 必需 | 核心路径只用标准库；本机实测 3.12.7 |
| ffmpeg / ffprobe | 真实处理必需 | PATH 里没有时用 `FFMPEG_PATH`/`FFPROBE_PATH` 指向便携版 |
| Pillow | Qwen-VL 路径必需 | 图像缩放/裁剪/编码；缺失时真实判定会显式失败 |
| `DASHSCOPE_API_KEY` | 真实判定必需 | 未配置时**不会编造人数**：任务显式失败或按配置降级 |

本机没有 ffmpeg 时使用本项目私有工具目录：

```powershell
$env:FFMPEG_PATH  = '..\..\.work\tools\ffmpeg\ffmpeg.exe'
$env:FFPROBE_PATH = '..\..\.work\tools\ffmpeg\ffprobe.exe'
```

### 2.2 配置

```powershell
Copy-Item .env.example .env      # .env 已在 .gitignore 中，不会入库
. .\scripts\Use-Env.ps1          # .env 只支持 KEY=VALUE，PowerShell 不会自动读它
```

联调必需项：

```ini
MODULE_BEARER_TOKENS=change-me-to-a-long-random-string   # 留空 = 所有 /api/v1 请求 401（失败关闭）
MODULE_PROVIDER_ID=head-up-local                          # 主项目按值登记
MODULE_VERSION=head-up-0.1.0
DASHSCOPE_API_KEY=sk-…                                    # 没有它就跑不出真实人数
```

### 2.3 启动与自检

```powershell
.\scripts\Run.ps1 -Target serve       # 启动服务（Ctrl+C 停止）
.\scripts\Run.ps1 -Target selftest    # 内置自检：fixture 固定数值（无网络、无模型）
.\scripts\Run.ps1 -Target test        # 全部测试
```

启动后会打印 `providerId` / `moduleVersion` / `definitionId`；`GET /api/v1/health`（**不需要 Bearer**）
会回一段能力边界，用它先和主项目对齐再联调：

```powershell
curl.exe -sS http://127.0.0.1:8080/api/v1/health
```

### 2.4 提交一个任务（契约 fixture 形状）

**`sha256` 必须是媒体真实字节的 SHA-256**（下面的 64 个 `a` 只是占位），`sourceUrl` 必须是公网可达的
HTTPS 短期受控地址（本地联调可开 `MEDIA_ALLOW_LOOPBACK_HTTP=true` 用回环 HTTP）。

```bash
BASE=http://127.0.0.1:8080
TOKEN=change-me-to-a-long-random-string
curl -sS -i -X POST "$BASE/api/v1/jobs" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: req-head-up-20260914-0001" \
  -d '{
    "schemaVersion": "1.0",
    "clientRequestId": "req-head-up-20260914-0001",
    "moduleType": "HEAD_UP",
    "classSessionId": "session-…",
    "mediaId": "media-…",
    "media": {
      "sha256": "…64位小写十六进制…",
      "durationMs": 2700000,
      "mimeType": "video/mp4",
      "sourceUrl": "https://oss.example.com/classroom.mp4?sign=…",
      "urlExpiresAt": "2026-09-14T04:00:00Z"
    },
    "analysisRange": { "startMs": 0, "endMs": 2700000 },
    "parameters": { "windowMs": 10000, "outputMode": "COUNTS", "definitionId": "head-up-attendance-qwen-vl-v2" }
  }'
```

首次 **202**（`status=QUEUED`、`revision=0`、`payload/error` 均为 `null`），记下 `jobId`；随后轮询：

```bash
curl -sS "$BASE/api/v1/jobs/<jobId>" -H "Authorization: Bearer $TOKEN"
```

轮询建议（契约 §2 工程初值，不是 SLA）：**2 秒起、最多 10 秒并加抖动**；提交超时后用
**同一个 `clientRequestId`** 重试。本模块**不返回 `Retry-After`**（不会 429/503），可重试性只体现在
任务 `error.retryable`。

### 2.5 不写一行代码先看效果

```powershell
# 对本地视频直接跑一遍（需要 ffmpeg；配了 DASHSCOPE_API_KEY 才是真实判定）
.\scripts\Run.ps1 -Target cli -CliArgs @('run','--file','课堂.mp4','--window-ms','10000',
                                         '--reports','.work\reports.json','--out','.work\envelope.json')
.\scripts\Run.ps1 -Target cli -CliArgs @('stats','--envelope','.work\envelope.json')  # 用 stats-v1 重算
.\scripts\Run.ps1 -Target frame -CliArgs @('--file','课堂.mp4','--at','600000','--out','.work\f.jpg')
```

`--reports` 会写出**逐窗判读记录**（代表帧路径、模型计数、`unknown` 数、可判断比例、命中阈值、
最终 quality），人工核对判定口径时只看这一份文件即可。

---

## 3. 接口与返回码

完整路径 = `baseUrl` + `/api/v1`。除 `/health` 外都需要 `Authorization: Bearer <MODULE_BEARER_TOKENS 中的一把>`。

| 方法/路径 | 请求要点 | 响应 |
| --- | --- | --- |
| `POST /api/v1/jobs` | `JobRequest`（契约 §3）；`Idempotency-Key` 可选但必须等于 `clientRequestId` | 首次 **202**／同键同内容 **200**，body 为完整 `JobEnvelope` |
| `GET /api/v1/jobs/{jobId}` | 可选头 `X-Client-Request-Id`（带上则必须一致） | **200** `JobEnvelope`；不存在/不匹配 → **404** |
| `GET /api/v1/health` | 无 | **200**，只含身份/接口版本/能力边界，不含密钥与队列详情 |

| HTTP | code | 含义 | 本轮实测 |
| --- | --- | --- | --- |
| 202 | — | 首次受理（`status=QUEUED`） | ✅ |
| 200 | — | 同键复用同一 job；或 GET 查询成功 | ✅ |
| 400 | `MALFORMED_JSON` | 请求体不是合法 JSON | ✅ |
| 401 | `UNAUTHENTICATED` | 缺凭证/凭证无效；**未配置凭证时一律 401** | ✅ |
| 403 | `FORBIDDEN` | `moduleType` 不是 `HEAD_UP` | ✅ |
| 404 | `NOT_FOUND` | 任务不存在**或无权**（同形，不泄漏归属） | ✅ |
| 409 | `IDEMPOTENCY_CONFLICT` | 同 `clientRequestId` 但身份/hash/duration/range/parameters 不同 | ✅ |
| 413 | `PAYLOAD_TOO_LARGE` | 请求体超过 `MAX_BODY_BYTES` | ✅（与转写模块不同：这里真的强制） |
| 422 | `VALIDATION_FAILED` | 字段非法、`Idempotency-Key` 不一致、未登记 `definitionId`、时长/窗口数超限 | ✅ |
| 422 | `UNSUPPORTED_SCHEMA` | `schemaVersion` 不是 `1.0` | ✅ |
| 429 / 503 | `RATE_LIMITED` / `TEMPORARILY_UNAVAILABLE` | — | ❌ 当前不会返回（不拒绝并发提交） |
| 409 | `RESULT_CONFLICT` / `ASSOCIATION_MISMATCH` | 任务级错误，经由终态 `error` 而非 HTTP | ⚠️ 见下 |

**任务级失败**（HTTP 200 + 终态 `FAILED`）出现在处理阶段：`MEDIA_URL_EXPIRED`（可重试）、
`MEDIA_HASH_MISMATCH`、`UNSUPPORTED_MEDIA`、`DECODE_FAILED`、`PROCESSING_FAILED`，
加上本模块补充码 `PAYLOAD_TOO_LARGE`、`VISION_PROVIDER_ERROR`、`VISION_CREDENTIAL_MISSING`、
`FFMPEG_MISSING`。**补充码需主项目适配器识别**，清单与处理建议见交接表 §10。

---

## 4. 处理流水线

```
POST /api/v1/jobs → 202 + JobEnvelope(QUEUED)     受理即返回；处理在 worker 线程里跑
      │
      ├─ 1. 选路       命中已登记演示身份且开启 ENABLE_FIXTURE_PROVIDER ⇒ 合成 provider
      │                其余一律走真实 provider（合成数据永不落到真实课堂身份上）
      ├─ 2. 受控下载   仅 https；拒绝环回/私网/链路本地/CGNAT/保留地址；最多 3 跳重定向每跳复检；
      │                边下边算 SHA-256；强制最大字节数
      ├─ 3. hash 核验  与 media.sha256 比对，不符 ⇒ FAILED MEDIA_HASH_MISMATCH（不可重试）
      ├─ 4. 探测时长   ffprobe 读时长/视频流；偏差超容差仅告警，不裁切、不改信封 durationMs
      ├─ 5. 规划窗口   analysisRange 按 windowMs 切窗（末窗可短），每窗取一帧（默认中点）
      ├─ 6. 抽帧        ffmpeg -ss 精确取帧 → 缩放到 FRAME_MAX_WIDTH（可选分块）
      ├─ 7. 视觉判定    Qwen-VL（OpenAI 兼容）→ 严格 JSON：head_up / head_down / unknown /
      │                legible_ratio / confidence；解析失败按可重试错误处理并切备用模型
      ├─ 8. 质量分类    VALID / NO_SUBJECTS / LOW_QUALITY（三态语义不可混用，见 §5）
      └─ 9. 落库终态    payload 通过自检后写终态；终态不可变，晚到状态被丢弃
```

时间映射的承诺（契约 §1）：

```
当前：整条媒体处理 ⇒  原媒体毫秒 ≡ 处理帧毫秒，offsetMs 恒为 0
将来支持单段裁剪：原媒体毫秒 = 处理帧毫秒 + offsetMs
唯一改动点：src/headup/frames.py: time_mapper(offset_ms)（现由 plan_frames 以 0 调用）
```

---

## 5. 结果怎么读（不变量）

成功终态的 `payload`：

```json
{
  "definitionId": "head-up-attendance-qwen-vl-v2",
  "denominatorDefinition": "代表帧内可判断抬头状态的可观测人数（head_up + head_down）；…",
  "sampleMode": "REPRESENTATIVE_FRAME",
  "valueMode": "COUNTS",
  "windowMs": 10000,
  "processedRanges": [ { "startMs": 0, "endMs": 60000 } ],
  "windows": [
    { "id": "win-001", "startMs": 0, "endMs": 10000, "sampleAtMs": 5000,
      "quality": "VALID", "qualityReason": null,
      "headUpCount": 30, "validObservedCount": 40, "headUpRatio": null }
  ],
  "warnings": []
}
```

必须守住的六条：

1. **三态质量不可混用**：`VALID`（分母 > 0，`0 ≤ 分子 ≤ 分母`）／`NO_SUBJECTS`（固定 `0/0`，
   比例 `null`）／`LOW_QUALITY`（两数 `null` + `qualityReason`，不能假定无人）。
2. **两种模式互斥**：`COUNTS` 下 `headUpRatio` 必须 `null`；`RATIO_ONLY` 下人数必须 `null`
   且比例 ∈ [0,1]。模块绝不擅自切换模式。
3. **`windows` 恰好覆盖 `processedRanges`**，有序、不重叠、无隐式缺口；未处理区间只能出现在
   `processedRanges` 之外，不许插值填充，曲线要断开。
4. **`sampleAtMs` 落在窗口内**；抽帧失败的窗口没有采样点（`null` + `FRAME_EXTRACTION_FAILED`）。
5. **分母不是班级人数**：`validObservedCount` 是"可判断抬头状态的可观测人数"，与应到/实到无关；
   禁止用 `sum(headUpCount)` 或 `sum(validObservedCount)` 编造人数或出席率。
6. **终态不可变**：换口径、换模型、纠错都必须新 `clientRequestId`；旧结果保留。

主项目侧的 `stats-v1`（本模块用同一口径实现，见 `src/headup/pipeline.py: compute_stats`）：

```
R = Σ(r_i × d_i) / Σd_i        （有效窗口按时长加权；Σd_i=0 时 R=null）
有效窗口覆盖 = 有效窗口时长并集 / durationMs
处理覆盖     = processedRanges 并集 / durationMs
相邻变化 = 100 × (r_cur − r_prev)，仅在相邻且都有效时计算，不跨缺口
```

> 一个容易踩的坑：**窗口等长时，Σ(r_i·d)/Σd 与 Σc_i/Σn_i 恒等**——所以别用等长样例去验证
> "有没有做时长加权"。本仓库 `tests/test_contract.py` 用不等长窗口做了可判别用例。
> 另外，时长加权衡量的是"时间占比"，人次汇总衡量的是"人均"，两者都不是"学生总人数"。

---

## 6. 配置项表

全部可用环境变量覆盖；数值以 `src/headup/config.py` 的默认值为准（**改配置前先读该文件**）。

### 6.1 身份与鉴权

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `MODULE_PROVIDER_ID` | `head-up-local` | 本部署供应者标识，主项目按值登记 |
| `MODULE_VERSION` | `head-up-0.1.0` | 实现版本；换模型/换抽帧/换口径都要改 |
| `MODULE_BEARER_TOKENS` | 空 | 逗号分隔机器凭证；**留空 = 所有 /api/v1 请求 401** |
| `SERVER_HOST` / `SERVER_PORT` | `127.0.0.1` / `8080` | 监听地址 |
| `DB_PATH` | `./.data/headup.sqlite3` | 模块自己的任务库（不是主项目库） |
| `WORKERS` | `1` | 处理 worker 数 |
| `HEADUP_CONFIG` | `config/headup.config.json` | 可选 JSON 配置文件（环境变量优先级更高） |

### 6.2 媒体

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `WORK_DIR` | `./.work` | 临时目录（媒体与帧） |
| `FFMPEG_PATH` / `FFPROBE_PATH` | `ffmpeg` / `ffprobe` | 外部工具；还会回退找 `./.work/tools/ffmpeg/bin/` |
| `MEDIA_CONNECT_TIMEOUT` / `MEDIA_DOWNLOAD_TIMEOUT` | `10` / `600` 秒 | 下载超时 |
| `MEDIA_PROBE_TIMEOUT` | `180` 秒 | ffprobe/抽帧超时 |
| `MEDIA_MAX_DOWNLOAD_BYTES` | `2147483648`（2 GiB） | 体积上限 → `PAYLOAD_TOO_LARGE` |
| `MEDIA_MAX_DURATION_MS` | `7200000`（2 小时） | 时长上限 → **422** |
| `MEDIA_HOST_ALLOW_LIST` | 空 | 下载主机白名单；留空=不限（仍拒绝内网） |
| `MEDIA_ALLOW_LOOPBACK_HTTP` | `false` | **仅本地联调**：允许 http + 回环；生产必须 `false` |
| `MEDIA_DURATION_TOLERANCE_MS` | `2000` | 与登记时长容差，超出仅告警 |
| `MEDIA_KEEP_FILES` | `false` | 保留临时媒体排错 |

### 6.3 观测口径与抽帧

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `HEADUP_DEFINITION_ID` | `head-up-attendance-qwen-vl-v2` | 口径 ID；与口径文本成对维护 |
| `HEADUP_DEFINITION_TEXT` | 内置文本 | 覆盖口径说明（与内置文本不一致会启动报错） |
| `HEADUP_SAMPLE_POSITION` | `middle` | 代表帧位置：`start`/`middle`/`end` |
| `FRAME_MAX_WIDTH` | `1600` | 抽帧宽度 |
| `FRAME_TILE_GRID` / `FRAME_TILE_OVERLAP` | `1x1` / `0.08` | 分块计数（`2x2`、`3x2`…） |
| `MIN_LEGIBLE_RATIO` | `0.60` | 可判断比例下限，低于则 `LOW_QUALITY` |
| `MAX_WINDOWS` | `20000` | 窗口数上限 → **422** |

### 6.4 视觉判定

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `VISION_PROVIDER` | `qwen-vl` | `qwen-vl`（DashScope）或 `heuristic`（离线降级） |
| `DASHSCOPE_API_KEY` | 空 | **未配置 ⇒ 真实判定不可用**（任务显式失败，不编造） |
| `VISION_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | OpenAI 兼容入口 |
| `VISION_MODEL` / `VISION_FALLBACK_MODEL` | `qwen3-vl-plus` / `qwen3-vl-flash` | 主/备模型 |
| `VISION_TIMEOUT_SECONDS` / `VISION_MAX_RETRIES` | `120` / `2` | 超时与重试 |
| `VISION_MAX_LONG_SIDE` / `VISION_MAX_IMAGE_BYTES` | `2048` / `9000000` | 送模型前的图像约束 |
| `HEADUP_HEURISTIC_SYNTHETIC` | `false` | **仅演练**：`heuristic` 返回合成计数（验证链路用，禁止真实部署开启） |
| `HEADUP_PERF_METRICS` | `true` | 是否采集实测性能指标（分阶段耗时/调用数/token/字节），落库并经 `GET` 的 `metrics` 增量字段返回 |
| `HEADUP_COST_CURRENCY` | `CNY` | 成本估算币种（仅展示用） |
| `VISION_PRICE_PROMPT_PER_1K` / `VISION_PRICE_COMPLETION_PER_1K` | `0` / `0` | **单价由部署方提供**：两者都为 0 时只报 token、不给金额（模块不内置任何价格） |

### 6.5 合成 provider（仅联调）

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `ENABLE_FIXTURE_PROVIDER` | `false` | 开启后才可能走合成路径 |
| `FIXTURE_PROVIDER_ID` / `FIXTURE_PROVIDER_VERSION` | `head-up-demo` / `demo-1.0` | 与主项目冻结样例一致 |
| `FIXTURE_DEMO_CLASS_SESSION_ID` / `FIXTURE_DEMO_MEDIA_ID` / `FIXTURE_DEMO_SHA256` | `session-demo-001` / `media-demo-001` / 64×`a` | 只对这三个值全中的请求输出合成观测 |

---

## 7. 工程结构

```
src/headup/
  wire.py         契约层：JobRequest/JobEnvelope 校验、幂等指纹、错误码（不含 IO）
  definitions.py  观测口径台账（definitionId ↔ 口径文本，成对维护）
  config.py       部署配置与 /health 能力边界
  store.py        SQLite 任务库：幂等、409、终态不可变、租约回收
  media.py        地址安全策略、受控下载、SHA-256 核验、ffprobe/ffmpeg
  frames.py       窗口规划与时间映射（唯一的 offset 改动点）
  analyze.py      视觉判定（Qwen-VL / 离线降级）、图像预处理与分块
  perf.py         性能与成本可观测：分阶段计时、调用计数、token、字节、成本估算（FRAME-6）
  benchmark.py    可复现基准：批量 vs 逐点抽帧、等价性证明、端到端指标（FRAME-6）
  pipeline.py     代表帧 → 判定 → 质量三态 → payload → stats-v1 统计
  providers.py    真实 provider 与合成 fixture provider（严格分离）
  service.py      提交、worker、终态写入
  server.py       HTTP 三路由、Bearer、错误映射
  cli.py          serve / run / extract-frame / fixture / stats / verify-fixtures /
                  generate-fixtures / selftest / cleanup / benchmark
tests/            313 个用例（契约、HTTP 链路、Qwen-VL 客户端协议、媒体安全、存储、流水线、性能指标）
docs/             交接表、定义说明、接入指南、交付样例（含自校验脚本、测试媒体与真实模型测试记录）
scripts/          Run.ps1（统一入口）、Use-Env.ps1（载入 .env）
```

---

## 7.1 真实模型实测结论（2026-09-14）

凭据到位后做过一轮真实 `qwen3-vl-plus` 测试，完整记录见
[`docs/fixtures/head-up-module-v0.1/测试记录-真实模型.md`](docs/fixtures/head-up-module-v0.1/测试记录-真实模型.md)。
要点：

| 项 | 实测结果 |
| --- | --- |
| 端到端 | 50 秒 5 窗真实媒体 → 202 → `SUCCEEDED` → 统计，**9.9–14.2 秒**跑完 |
| 代表性结果 | 5 个窗口全部 `VALID`，平均抬头率 **0.715**（同一媒体重跑为 0.687） |
| 成本 | 1140 prompt tokens/帧；45 分钟课（270 帧）≈ **¥0.45**（plus）/ **¥0.11**（flash） |
| 降级口径 | 合成示意图被判"非真人"⇒ 全窗不可判读，**没有编造人数** |
| **发现 1** | 模型对"只见后脑/背对镜头"的人**会猜成抬头**（俯拍机位尤其明显）⇒ **v0.2 Round 1 已启用严格口径提示词** `headup-strict-v0.2`，真实 A/B 显示平均抬头人数 7.9 → 6.1、UNKNOWN 1.4 → 3.1 |
| **发现 2** | 同一帧多次调用结果**不完全一致**（±1 人量级）；均值应看作 **±3 个百分点**的估计 |
| **发现 3** | 换模型会显著改变数值：plus 0.715 vs flash 0.588 |

⇒ 报告必须显示"本次运行的数值"并声明估计性质；`moduleVersion` 必须随模型变化；
是否收紧提示词（禁止推测）属于口径变更，列为待评审项。

---

## 7.2 性能与成本可观测（FRAME-6）

**指标只报实测值，且不进契约 payload**（payload 保持确定性）：

| 位置 | 内容 |
| --- | --- |
| `GET /api/v1/jobs/{jobId}` 增量字段 `metrics` | `stageTimingsMs`（下载/探测/抽帧/代表帧判定/复核/聚合/清理）、`counters`、`models`、`tokens`、`diskBytes`、`derived.reviewRatio`、`cost`、`notes`（抽帧方式、真实 HTTP 请求数、错误阶段…） |
| `head_up_job.metrics_json` | 同上，失败运行也写（"在哪个阶段花了多久才失败"同样有用） |
| `headup run --metrics-out <file>` | 单机 CLI 路径的同一套指标 |
| `GET /api/v1/health` → `performance` | 指标开关与成本模型是否配置单价 |

两个调用口径不能混：`vision.calls` 是**逻辑调用**（一次 `analyze`/`analyze_window` = 一个窗口观测），
`notes.visionRequests` 是**真实 HTTP 请求数**（含计数不自洽重试与备用模型重试，计费看这个）。

抽帧方式同样如实上报：`notes.extractionMode ∈ {BATCH, BATCH+SEEK_TAIL, SEEK_FALLBACK, SEEK}`，
批量首帧等价性校验不通过就整段回退逐点 seek（`SEEK_FALLBACK` 会连同失败时的平均像素差一起记录）。

可复现基准（FRAME-6）：

```powershell
python -m headup benchmark --file <video> --extraction both --window-ms 10000 --repeats 3 `
    --out .work/real-test/report.json          # 批量 vs 逐点：耗时/帧数/字节/逐点等价性 + Decord 决策
```

实测结论（45 分钟 2700.467s / 1280x720 / 271 采样点，详见
[`docs/rounds/FRAME-6-性能与稳定性Benchmark.md`](docs/rounds/FRAME-6-性能与稳定性Benchmark.md)）：
整节课批量抽帧 **6.2s**（0.023s/帧、落盘 49MB）、逐点 seek **31.4s**（0.117s/帧，与采样位置无关），
**加速 5.07×**；两组帧 271/271 点等价（最大平均像素差 0.0137），实际解码 pts 偏移 ≤ 一帧。
真实模型整节课（flash、271 窗）端到端 **839.9s ≈ 14 分钟**、逻辑调用 513 次 / HTTP 549 次、
prompt 1.12M tokens、成本 ≈ **$0.0509（¥0.367）**。**不引入 Decord**（批量吞吐约为决策线的 22 倍）。

---

## 8. 与主项目联调的顺序

| 步骤 | 做什么 | 预期 | 本轮真实状态 |
| --- | --- | --- | --- |
| 1 | 主项目导入本仓库 `docs/fixtures/head-up-module-v0.1/` 的合成样例（不要求本服务在线） | 结构/统计/曲线/缺失态正确 | 样例已交付，**主项目侧尚未导入** |
| 2 | 按 `head-up-request.json` 形状向 `/api/v1/jobs` 提交 | 202/200/409/422/404/401/413 分流正确 | ✅ 本地实测（`http-run-record.json`） |
| 3 | 真实媒体端到端（M4） | 抽帧→真实模型→终态→统计跑通 | ✅ **已用真实凭据跑通**（见 §7.1）；⚠️ 起点/中点/末尾容差仍未与主项目商定 |
| 4 | 异常路径：URL 过期续期、hash 不符、无视频流、部分处理、全遮挡 | 分类正确、终态不可变、同键不重复计费 | ⚠️ 代码路径明确，部分有单测，**未用真实配额触发** |
| 5 | 换第二段媒体复跑 + 人工交叉验收（M5） | 可重复交付 | ⚠️ 未执行（真实课堂录像仍未到手） |

联调前请先做两件事：

1. 读 `/api/v1/health`，把 `providerId` / `moduleVersion` / `definitionId` **按实际值登记**（不要抄默认值）；
2. 与主项目确认 [`docs/交接表-head-up-v0.1.md`](docs/交接表-head-up-v0.1.md) §12 的 10 个待确认问题
   （容差、补充错误码、`/health` 是否免鉴权、`windowMs` 取值、`MIN_LEGIBLE_RATIO`、保留策略等）。

---

## 9. 已知限制与未验证项

**已知限制（实现事实）**

1. **单帧代表窗**：每窗只有一帧估计值，不是连续行为测量；窗内姿态变化只看得到一点。
2. **单机单 worker（默认）**：长媒体会串行排队；不拒绝并发提交（所以没有 429）。
3. **`ANALYSIS_CONCURRENCY` 未生效**：任务内窗口当前顺序处理，该值只在 `/health` 声明。
4. **无回调**：只支持轮询（契约里回调是可选项）。
5. **不取消、不分页**：契约首版未要求；单结果一次返回。
6. **不做子范围裁剪优化**：给了 `analysisRange` 子范围时，只处理该范围的窗口（比转写模块更精确），
   但抽帧仍会 seek 原文件。
7. **分块计数是可选路径**：`FRAME_TILE_GRID!=1x1` 会改变有效口径，开启需在部署记录里注明。
8. **凭证轮换需重启**，且是部署级（不按 job 细分范围）。
9. **`UNSUPPORTED_MEDIA` 承担了"部署安全策略"语义**（明文 http/白名单/内网地址），
   与"媒体格式不支持"共用一个码，主项目只能看 `message` 区分（见交接表 §9）。
10. **不实现**：出席率、实到人数、人脸身份、学生跨帧档案、教师评分、报告生成、通知。

**未验证项（不要当已完成）**

1. **真实课堂识别精度完全未知**：未用**真实课堂录像**与人工标注对照过。
   2026-09-14 已用真实凭据跑通真实模型（素材是 CC 授权课堂照片拼接的视频），
   端到端、统计、成本都有实测数据，但**准确率仍无数据**（见
   [`测试记录-真实模型.md`](docs/fixtures/head-up-module-v0.1/测试记录-真实模型.md)）。
2. **输出不可逐位复现**：同一帧重复调用有 ±1 人量级抖动，均值应视为 ±3 个百分点量级的估计值；
   靠近阈值的窗口可能在 `VALID`/`LOW_QUALITY` 间翻转。
3. **模型会"猜"看不清朝向的人**：俯拍/背对镜头画面里，现行提示词会把只见后脑的人算成抬头；
   严格提示词可消除该问题但会降低有效覆盖——**属口径变更，待三方评审**。
4. 真实远场课堂画面下的 `MIN_LEGIBLE_RATIO=0.60` 是否合适，未验证。
5. 45 分钟级长视频、2 小时/20000 窗口上限、密集人群（50–100 人）计数偏差，均未实测。
6. 与主项目的时间容差未确定。
7. 并发 worker、崩溃恢复、凭证过期续期、长稳运行只做了单测级验证，**没做服务级压测**。
8. 生产部署形态（HTTPS 终止、反代、容器、日志采集、保留策略）未定，仓库不含部署配置。
9. 隐私合规（教室录像的告知/最小化/留存期）需业务侧确认，本模块只处理匿名聚合计数、不存人脸。

---

## 10. 本机环境注意（实测结论）

1. **本机 ffmpeg 不在 PATH**时，便携版放在本项目`.work/tools/ffmpeg/`；
   `scripts/Run.ps1` 会自动探测并设置 `FFMPEG_PATH`/`FFPROBE_PATH`（已配置时不覆盖）。
2. **测试临时目录不要用 `tempfile.mkdtemp`**：本机受管环境下 mkdtemp 生成的目录 ACL 受限，
   SQLite 会报 `unable to open database file`。测试统一用 `tests/support.py: temp_root()`
   （在仓库 `.t/` 下自建目录）。
   副作用：历史遗留在 `.t/` 下的空目录可能删不掉（`Access to the path … is denied`），
   它们已被 `.gitignore` 忽略，不影响提交与运行。
3. **`log_message` 覆写要用 `*args`**：写成 `*args_` 再 `del args_` 会在每个响应上报
   `UnboundLocalError`，表现是客户端 `RemoteDisconnected`（本项目踩过，已修）。
4. **Pillow 装在 Anaconda 的 Python 里**（`D:\anaconda\python.exe`，3.12.7）；`py -3.13` 没有它。
5. 测试用合成媒体由 ffmpeg 生成（`-loop 1` 静态图 + `sine` 音轨），**不含任何真实人物**；
   真实场景测试视频由 CC 授权课堂照片拼接，来源与授权见交付样例 README。
6. **本机 `curl.exe` 的 HTTPS 是坏的**（`schannel: SECURE_NO_CREDENTIALS`），
   下载外部图片/视频请用 Python `urllib`（`requests` 也可能受影响）；本机到 DashScope 的 HTTPS
   用 Python 调用正常（已实测）。
7. **Wikimedia 缩略图端点不接受超过原图宽度的请求**（如原图 1600px 却要 1600px 缩略图会 400）；
   用 `https://commons.wikimedia.org/wiki/Special:FilePath/<文件名>?width=1280` 更稳。

---

## 11. 相关文档

| 文档 | 用途 |
| --- | --- |
| [`docs/rounds/`](docs/rounds/) | **v0.2 分轮实施报告**：Round 1 严格观察口径、Round 2 三档质量与课程均值口径、Round 3 自适应三帧复核 |
| [`docs/接入指南-主项目-head-up-v0.1.md`](docs/接入指南-主项目-head-up-v0.1.md) | **主项目适配器规格 + 联调验收单**：两条接入路径、结果映射、统计口径、错误矩阵、逐条打勾清单 |
| [`docs/fixtures/head-up-module-v0.1/测试记录-真实模型.md`](docs/fixtures/head-up-module-v0.1/测试记录-真实模型.md) | **2026-09-14 真实模型实测**：素材、结果、复现性抖动、提示词对照、成本、待决策项 |
| [`docs/交接表-head-up-v0.1.md`](docs/交接表-head-up-v0.1.md) | **契约 §7 交接表**：全部必填数值、能力边界、补充错误码、待确认问题 |
| [`docs/定义说明-head-up.md`](docs/定义说明-head-up.md) | `definitionId` 配套说明：判定规则、分母、排除、遮挡、选帧、阈值、局限 |
| [`docs/fixtures/head-up-module-v0.1/README.md`](docs/fixtures/head-up-module-v0.1/README.md) | 交付样例说明与按里程碑的联调矩阵 |
| `docs/fixtures/head-up-module-v0.1/verify_head_up_fixtures.py` | 仅标准库的样例自校验 |
| 契约与冻结样例（主项目文档包） | `classroom-analysis-contract-v0.1.md`、`head-up-module-v0.1.md`、`fixtures/classroom-analysis-v0.1/` |

**本模块不负责**：课程摘要与报告、教学评价、出席率与实到人数、教师授权、报告发布（契约 §1 与抬头专用文档 §1）。
