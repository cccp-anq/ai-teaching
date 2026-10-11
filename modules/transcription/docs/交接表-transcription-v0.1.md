# 转写模块交接表（transcription v0.1）

> 本文填写《三项目公共接口契约 v0.1》§7 交接表中「转写」一行要求的全部必填项，并补充《转写项目接口与开发交接 v0.1》§5 的验收所需信息。
>
> **取值纪律**：每一项数值都来自本仓库的真实配置或代码；凡未实测、未实现、无法确定的一律写「未确定」并说明原因，**不用推测值填空**（契约明确「不以空白视为同意」）。
>
> 图例：✅ 本机实测（本轮实际跑过） · 📖 读实现确认（未运行验证） · ⚠️ 未确定 · ❌ 未实现 / 当前不会发生
>
> 取值来源文件：
> `src/main/resources/application.yml`、`job/web/JobController.java`、`job/web/BearerAuthFilter.java`、
> `job/web/ContractErrors.java`、`job/web/JobExceptionHandler.java`、`job/service/JobService.java`、
> `job/service/JobRunner.java`、`job/service/JobRequestValidator.java`、`job/service/RequestFingerprint.java`、
> `job/service/TranscriptTimeMapper.java`、`media/MediaDownloader.java`、`media/FfmpegMediaTool.java`、
> `asr/client/WandAsrClient.java`、`asr/client/TencentAsrClient.java`、`docs/TokenHub接口探测记录.md`。
>
> 文档版本：2026-09-13。**本文不是已联签的规范**，数值随部署环境变量变化，主项目登记前需按部署实际值复核。

---

## 1. baseUrl、路由与返回码

### 1.1 baseUrl

| 项 | 值 | 依据 |
| --- | --- | --- |
| 监听端口 | `${SERVER_PORT:8080}`，默认 **8080** | `application.yml` `server.port` |
| 本地联调 baseUrl | `http://127.0.0.1:8080` | ✅ 本轮以 `SERVER_PORT=18080` 实测 |
| 生产 baseUrl | **未确定**：需由部署方给定 HTTPS 域名与端口 | 契约 §6 要求主项目→模块走 HTTPS；本仓库不含部署域名/证书配置 |
| 路由前缀 | `baseUrl` + `/api/v1` | `JobController` 的 `@RequestMapping("/api/v1")` |

> 契约 §2 把 `baseUrl` 定义为「各模块独立前缀」，完整路径 = `baseUrl` + `/api/v1/...`。

### 1.2 三个路由

| 方法/路径 | 鉴权 | 请求 | 响应 |
| --- | --- | --- | --- |
| `POST {baseUrl}/api/v1/jobs` | `Authorization: Bearer <token>`；`Idempotency-Key` 可选但给了就必须等于 `clientRequestId` | `JobRequest`（契约 §3） | 首次 **202**；同键同内容复用 **200**；body 为完整 `JobEnvelope` |
| `GET {baseUrl}/api/v1/jobs/{jobId}` | 同上 | 可选头 `X-Client-Request-Id`，带上则必须与该任务一致 | **200** `JobEnvelope`；不存在或键不匹配 **404** |
| `GET {baseUrl}/api/v1/health` | **同上（也受 Bearer 保护）** | 无 | **200**，见 §1.4 |

补充事实（📖/✅）：

- 只保护前缀 `/api/v1/`；其他路径（如 `/`）不经过鉴权过滤器。
- `GET /api/v1/jobs/{jobId}` 对非终态任务同样返回 **200**（不是 202），状态由 `status` 字段表达。
- 本模块**不实现**可选回调（契约 §2「未协商支持则不调用」），也不提供取消、分页、SSE/推送。
- 旧沙盒时代的 `/api/videos/{videoId}/transcription*` 路由**已不存在**（`JobController` 注释明确说明「刻意不带」）；`scripts/Smoke.ps1` 仍指向旧路由，属待清理的历史脚本。

### 1.3 返回码语义（含实测证据）

| HTTP | code | 触发条件 | 本轮验证 |
| --- | --- | --- | --- |
| **202** | —（body 为 `QUEUED` 信封） | 首次受理新任务 | ✅ 实测 `HTTP 202`，`status=QUEUED, revision=0, payload=null, error=null` |
| **200** | — | 同 `clientRequestId` 且业务指纹一致 → 复用同一 `jobId`；`GET` 查询也走 200 | ✅ 实测重提返回同一 `jobId`，未新建 |
| **400** | `MALFORMED_JSON` | 请求体不是合法 JSON 或字段类型不匹配 | ✅ 实测（`"{not json"` → 400 `MALFORMED_JSON`） |
| **401** | `UNAUTHENTICATED` | 缺少/无效 Bearer；**未配置任何凭证时对所有 `/api/v1` 请求一律 401**（失败关闭） | ✅ 实测无 token → 401；另以空 `MODULE_BEARER_TOKENS` 启动复测：不带/带任意/带空 Bearer 全部 401。响应 `requestId` 为空串 |
| **403** | `FORBIDDEN` | `moduleType` 不是 `TRANSCRIPTION`（如 `HEAD_UP`） | ✅ 实测 `HEAD_UP` → 403 |
| **404** | `NOT_FOUND` | 任务不存在，或 `X-Client-Request-Id` 与任务不一致（不区分「不存在」与「无权」，不泄漏归属） | ✅ 两种情形均实测为 404 |
| **409** | `IDEMPOTENCY_CONFLICT` | 同一 `clientRequestId` 但业务指纹不同（身份/hash/duration/range/parameters 任一变化） | ✅ 实测换 `sha256` → 409，原任务未被改写 |
| **409** | `RESULT_CONFLICT` | 契约 §4 的「同键不同内容」结果冲突 | ❌ **当前不会返回**：本版终态不可变、不接收结果写入，没有产生该码的路径 |
| **409** | `ASSOCIATION_MISMATCH` | 契约 §5 的归属不一致 | ❌ **当前不会返回**：本模块不持有主项目归属登记，无法校验（只在 `ContractErrors` 中定义） |
| **413** | `PAYLOAD_TOO_LARGE` | 契约要求「请求体超限返回 413」 | ❌ **当前不会返回**：`max-body-bytes` 未被任何代码读取强制执行（见 §5.3，已实测 2 MB 请求体被正常受理） |
| **422** | `VALIDATION_FAILED` | 字段缺失/取值非法：ID 超 128 字符、`sha256` 非 64 位小写十六进制、`durationMs` 超上限、`sourceUrl`/`urlExpiresAt` 非法、`analysisRange` 违反 `0 ≤ start < end ≤ durationMs`、`language` 不在支持列表、`enableSpeakerLabels=true`、`Idempotency-Key` 与 `clientRequestId` 不一致 | ✅ 实测：`enableSpeakerLabels=true`、`language=ja-JP`、头键不一致 → 均 422 且带 `details[].path` |
| **422** | `UNSUPPORTED_SCHEMA` | `schemaVersion` 不是 `1.0`（缺失走 `VALIDATION_FAILED`） | ✅ 实测 `2.0` → 422 `UNSUPPORTED_SCHEMA` |
| **429** | `RATE_LIMITED` | 契约 §5 的限流 | ❌ **当前不会返回**（只定义了常量，无产生点） |
| **503** | `TEMPORARILY_UNAVAILABLE` | 契约 §5 的临时不可用 | ❌ **当前不会返回**；`GET /api/v1/health` 目前恒为 200（见 §1.4） |
| 500 | `PROCESSING_FAILED`（HTTP 拒绝体） | 兜底未预期异常，不回显堆栈/密钥/媒体 URL | 📖 |

> ⚠️ **与契约 §5 的一处偏差（需主项目适配决策）**：下载阶段的失败码会被**原样透传进任务 `error.code`**。例如媒体体积超限时返回的是「HTTP 200 + 任务 `FAILED` + `error.code=PAYLOAD_TOO_LARGE`」，而不是 HTTP 413；同理下载地址非 https 会得到任务级 `VALIDATION_FAILED`、主机不在白名单会得到任务级 `FORBIDDEN`。契约 §5 的任务级错误码只列了 `MEDIA_URL_EXPIRED / MEDIA_HASH_MISMATCH / UNSUPPORTED_MEDIA / DECODE_FAILED / PROCESSING_FAILED`。**本轮不擅自改码，交由联调评审决定是改实现还是主项目适配。**

### 1.4 `/health` 实际返回（✅ 实测原文）

```json
{
  "status": "UP", "moduleType": "TRANSCRIPTION",
  "providerId": "transcription-local", "moduleVersion": "transcription-1.0.0",
  "schemaVersion": "1.0", "timeBase": "MEDIA_START_MS",
  "checkedAt": "2026-09-13T14:17:40.666054400Z",
  "capabilities": {
    "languages": ["zh-CN", "zh", "en", "auto"],
    "speakerLabels": false, "confidenceFromEngine": false,
    "maxDurationMs": "7200000", "maxBodyBytes": "1048576", "maxSegments": "20000",
    "ffmpegConfigured": true
  }
}
```

诚实说明：

- `status` 恒为 `UP`，**不做依赖探测**（不检查 ffmpeg 是否可执行、不检查识别凭证是否可用、不检查数据库连通）。契约允许 200 或 503，本版只会 200。
- `capabilities.languages` 是**硬编码的四个值**，与真正的受理规则（§4）**不一致**，联调时以 §4 的校验器列表为准。已列入待修项。
- `ffmpegConfigured=true` 只表示「配置了 ffmpeg 路径且非空」，**不等于 ffmpeg 可执行**。
- 不含队列长度、任务计数、凭证状态等运维细节（符合契约要求）。

---

## 2. providerId 与 moduleVersion

| 交接项 | 环境变量 | 默认值 | 绑定键 |
| --- | --- | --- | --- |
| `providerId` | `MODULE_PROVIDER_ID` | `transcription-local` | `videoreact.contract.provider-id` |
| `moduleVersion` | `MODULE_VERSION` | `transcription-1.0.0` | `videoreact.contract.module-version` |
| `payload.engineVersion` | `WAND_MODEL`（提供方 `wand` 时）/ `ALIYUN_ASR_MODEL`（`aliyun-filetrans` 时）/ `ASR_ENGINE_MODEL_TYPE`（`tencent` 时） | `wand-asr-v1` / `qwen-audio-3.0-asr-flash-filetrans` / `16k_zh_en_2.0` | `videoreact.asr.provider.{wand,aliyun}.model` / `videoreact.asr.engine.model-type` |

说明：

- 信封里的 `providerId`/`moduleVersion` **取自部署配置，不取自请求体**（✅ 有单测 `identityComesFromConfig` 钉住），调用方无法伪造。
- 契约要求 `providerId` 必须与凭证绑定：本模块的凭证是**部署级**的一串 Bearer token，**不区分 providerId**（同一个 token 可访问本部署全部任务）。是否需要在凭证与 providerId 之间建立更强绑定，需主项目确认。
- **部署登记值未确定**：上述是默认值；实际联调必须登记部署时真正注入的值（例如 `MODULE_PROVIDER_ID=transcription-classroom-prod`）。**主项目不得按本文默认值硬编码**，应先读 `/health` 的 `providerId`/`moduleVersion` 再登记。
- `moduleVersion` 的取值纪律（代码注释约定）：换识别模型或换抽音轨策略都应改动它；本版为 `transcription-1.0.0`，尚未随真实算法变更迭代过。

---

## 3. 支持的媒体格式与编码

**结论：本模块对容器/编码不做白名单——接收任意容器，由 ffmpeg 解码。因此「实际接受范围 = 部署所用 ffmpeg 构建的解码能力」，这是一个随部署变化的开区间，不是固定清单。**

| 项 | 事实 | 验证 |
| --- | --- | --- |
| 请求侧校验 | `media.mimeType` 只要求**非空字符串**，不做取值校验；`sha256` 必须是 64 位小写十六进制；`durationMs` 必须为正整数且 ≤ 上限 | 📖 + ✅（422 实测覆盖 sha256/range 路径） |
| 判定「可用」的实际规则 | ① 受控下载成功且 sha256 一致；② `ffprobe` 能读出 `format.duration > 0`（读不出 → `DECODE_FAILED`，不可重试）；③ 至少存在一条 `codec_type=audio` 的流（无音轨 → `UNSUPPORTED_MEDIA`）；④ `ffmpeg` 能抽出 **> 44 字节**（WAV 头）的音频（否则 `UNSUPPORTED_MEDIA`） | 📖 |
| 抽出后送给识别引擎的规格 | **16 kHz / 单声道 / `pcm_s16le` / WAV**（`-vn -ac 1 -ar 16000 -c:a pcm_s16le -f wav`），以内联 base64 提交 | 📖 |
| 本轮实测过的容器 | 仅 **WAV（`pcm_s16le`）**：合成 3 秒 440 Hz 单音，实测走通「下载 → hash 核验 → ffprobe → 抽音轨」四步 | ✅ |
| 其他容器（MP4/MKV/MOV/FLV…） | **未确定**：契约路由下未实测。旧沙盒阶段曾用 4 MB MP4 验证 TokenHub 直传与 SRT 解析（`docs/TokenHub接口探测记录.md` §4.2/§4.4），但那是**旧接口 + 直传路径**，不等于本契约路径的容器解码范围 | ⚠️ |
| 变速/剪辑拼接/多镜头融合 | 首版不支持；本实现也不做任何此类处理（识别的是整条音轨） | 📖 |
| ffmpeg 来源 | 部署方提供，默认路径 `./.work/tools/ffmpeg/bin/ffmpeg.exe` 与 `ffprobe.exe`（`FFMPEG_PATH`/`FFPROBE_PATH`）；本机为便携版 | ✅ 路径存在且可执行 |

**给主项目的建议**：不要按「支持格式清单」登记，而要登记**判定规则**（能探测出时长 + 有音轨 + 能抽出音频即可），并在 M4 用真实课堂录像实测一遍。若主项目需要一份白名单用于前置校验，那需要先确定生产 ffmpeg 构建，再单独出清单——**本轮没有这个清单**。

---

## 4. 支持的语言

实际受理规则来自 `JobRequestValidator.SUPPORTED_LANGUAGES`（大小写不敏感）：

```
auto, zh, zh-cn, zh-hans, en, en-us, zh-en
```

| 项 | 事实 |
| --- | --- |
| 必填 | 是。缺失/空白 → 422 `VALIDATION_FAILED`（`parameters.language`） |
| 不在列表 | 422 `VALIDATION_FAILED`，`details[].message = 本部署不支持的语言标签: <值>`（✅ 实测 `ja-JP` → 422） |
| 回包 `payload.language` | 请求什么就回什么；`auto` 或空白**一律回 `und`**（引擎不回报实际语种，不冒充 `zh-CN`） |
| `/health` 自述 | 只列 `["zh-CN","zh","en","auto"]` —— **与上面的受理规则不一致**，以本文（校验器）为准 |
| 真实识别效果 | **未确定**：本模块不做语种判定，识别能力取决于引擎（TokenHub `wand-asr-v1`）。`zh-hans`/`en-us`/`zh-en` 是**受理**而非「已实测能识别好」的承诺 |
| 多语混说 | 未验证 |

---

## 5. 容量边界（4 个上限）

### 5.1 汇总表

| 交接项 | 配置键 | 环境变量 | 默认值 | 超限行为 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 最大媒体时长 | `videoreact.media.max-duration-ms` | `MEDIA_MAX_DURATION_MS` | **7200000 ms（2 小时）** | 提交时 **422 `VALIDATION_FAILED`**（`media.durationMs` 超过本部署上限） | 📖 实现路径明确 |
| 最大下载体积 | `videoreact.media.max-download-bytes` | `MEDIA_MAX_DOWNLOAD_BYTES` | **2147483648 字节（2 GiB）** | 任务 **FAILED** `PAYLOAD_TOO_LARGE`（HTTP 仍是 200）；先看 `Content-Length`，流式写入时再强制一次 | 📖 未实测 |
| 最大请求体 | `videoreact.contract.max-body-bytes` | `MAX_BODY_BYTES` | 声明 **1048576 字节（1 MiB）** | **❌ 未被强制执行**：该值只出现在 `/health` 输出里，代码中没有任何读取点，也没有请求体过滤器 | ✅ **实测反证**：2 MB 请求体被正常受理并返回 202 |
| 最大片段数 | `videoreact.contract.max-segments` | `MAX_SEGMENTS` | 声明 **20000** | **❌ 未被强制执行**：同样只在 `/health` 输出，无校验点 | 📖 |
| （结果体积） | — | — | **未确定** | 无任何 payload 体积限制；本版单次完整返回，不分页、不截断 | ⚠️ |
| （片段条数实际可控性） | 受识别引擎分段策略影响 | — | **未确定** | 2 小时课堂音频会产生多少片段未实测 | ⚠️ |

### 5.2 与契约的差异（必须让主项目知道）

契约 §6 要求「超过上限返回 413/422」。当前实现的实际语义是：

- **时长超限** → 提交即 422（符合契约）。
- **下载体积超限** → HTTP 200 + 任务 FAILED + `error.code=PAYLOAD_TOO_LARGE`（**不符合**「413」的字面要求）。
- **请求体超限** → **没有上限**，不会 413。

### 5.3 请求体上限的实测记录

在本机以 `MAX_BODY_BYTES` 默认值（未覆盖）启动，提交一个约 2 MB 的合法请求体（多带一个被忽略的字段），返回 **HTTP 202**，任务被正常受理。因此「最大请求体 = 1 MiB」目前是**配置声明，不是生效行为**，登记值应写「未确定」。

---

## 6. 时间戳映射规则与精度

### 6.1 映射规则（本模块对外承诺的核心语义）

**当前实现：整条音轨提取，不做任何裁剪。因此**

```
原媒体毫秒  ≡  音频（识别结果）毫秒
偏移 offsetMs 恒为 0
```

- `timeBase` 固定 `MEDIA_START_MS`，所有 `segments.startMs/endMs` 都是**原媒体**毫秒轴。
- `payload.processedRanges` 固定为 `[{startMs: analysisRange.startMs, endMs: analysisRange.endMs}]` —— 本实现不裁剪，所以「已处理」= 「请求的范围」。
- 提取音频时**不删静音、不做任何时间平移**；首段之前的静音保留在轴上（契约 §4 要求）。
- 音频抽取格式选 **WAV 而非 MP3**，就是为了避开编码器 priming 采样导致的首段整体前移（代码注释明确了这一取舍）。

**将来支持单段裁剪时的唯一改动点**（现在就把接口留好了）：

```
原媒体毫秒 = 片段毫秒 + offsetMs        // offsetMs = 片段在原媒体中的起点
改动点：TranscriptTimeMapper.offsetFor(long clipStartMs)   // 现返回 clipStartMs，本版恒以 0 调用
```

`JobRunner` 现在传的是 `TranscriptTimeMapper.offsetFor(0L)`。若将来引入裁剪，只需让该方法的入参变成真实的片段起点，**调用方与公式都不散落在别处**。

### 6.2 精度

| 环节 | 精度事实 | 状态 |
| --- | --- | --- |
| 容器时长（`durationMs`） | `ffprobe` 给的是**秒级浮点字符串**（如 `"3.000000"`），本模块换算为毫秒并**四舍五入**：`Math.round(duration * 1000)`。因此容器时长与毫秒整数之间存在 ≤0.5 ms 的取整差 | 📖 + ✅（实测 ffprobe 输出 `duration: "3.000000"`） |
| 片段起始/结束 | 走 TokenHub **异步**通道时，时间戳来自下载回来的 **SRT**（`hh:mm:ss,mmm`）解析为整数毫秒，**不做二次取整**；若引擎返回内联 `sentences`，则直接取其数值字段 | 📖（实测报文见 `docs/TokenHub接口探测记录.md` §4.2/§4.3） |
| 引擎自身的时间分辨率 | **未测定**。实测样例（3.362 s 音频）给出 `462 / 1187 / 3362` 这类毫秒值，但引擎内部对齐粒度未知 | ⚠️ |
| 与主项目的容差 | **未确定——这是本次交接最需要主项目确认的一项**。契约要求 M4 用同一段真实媒体核对**起点/中点/末尾**各一处并**记录共同接受的容差**；本模块只保证「恒等映射、不猜偏移」，不声明任何容差数值 | ⚠️ |
| 登记时长与真实时长的偏差 | `videoreact.media.duration-tolerance-ms`（`MEDIA_DURATION_TOLERANCE_MS`）默认 **2000 ms**：超出时**只加 warning**（`DURATION_MISMATCH:registered=…,actual=…`），**不裁切、不拒绝**，信封 `durationMs` 仍回请求值。真实课堂媒体的偏差量未实测 | 📖 + ⚠️ |

---

## 7. 并发与超时

### 7.1 并发（首版限制，须明确告知主项目）

| 项 | 事实 |
| --- | --- |
| 执行模型 | `JobService` 内一个 **`Executors.newSingleThreadExecutor`（单线程、守护线程 `transcription-worker`）**，任务**严格顺序**处理 |
| 并发度 | **1**（硬编码，**不可配置**） |
| 「并发上限」这个数值 | **未确定 / 无意义**：本模块**不拒绝**并发提交，只会把任务排队；因此不存在「超过 N 个并发就 429/503」的行为 |
| 对主项目的含义 | 多任务同时提交只会有 1 个在跑，其余排队等待；**长媒体会显著拉长后续任务的等待时间**。主项目若需要并行，必须自行限流或与模块协商改为多 worker（含 lease 锁）后再放开 |
| 提交接口是否阻塞 | 不阻塞：受理后立即返回 202，处理在 worker 线程里跑（✅ 实测提交 <1 s 返回 202） |
| 重启恢复 | ❌ **未实现调度**：`JobService.resumeNonTerminal(limit)` 存在，但**全代码没有任何调用点**（无 `@Scheduled`）。进程重启后残留的 `QUEUED`/`RUNNING` 任务**不会被推进**，会永久停在那里，只能换 `clientRequestId` 重提 |
| 幂等与并发 | 唯一约束 `uk_transcription_job_client_request`（`client_request_id`）+ 指纹比对；同键重提不会重复计费（✅ 有单测） |

### 7.2 超时

| 环节 | 配置键 / 环境变量 | 默认值 | 说明 |
| --- | --- | --- | --- |
| 媒体下载 · 连接 | `videoreact.media.connect-timeout-seconds` / `MEDIA_CONNECT_TIMEOUT` | **10 s**（代码取 `max(5, 值)`） | 📖 |
| 媒体下载 · 单次请求 | `videoreact.media.download-timeout-seconds` / `MEDIA_DOWNLOAD_TIMEOUT` | **600 s**（代码取 `max(30, 值)`） | 📖；⚠️ 该超时对**流式读取 body 期间**是否持续生效未实测 |
| 媒体下载 · 重定向上限 | 硬编码 `MAX_REDIRECTS = 3` | **3 跳**（含初始请求最多 4 次 HTTP） | 每一跳都重新做地址校验 📖 |
| ffprobe / ffmpeg | `videoreact.media.probe-timeout-seconds` / `MEDIA_PROBE_TIMEOUT` | **180 s**（探测与抽音轨共用一个值） | 超时即 `destroyForcibly` 并失败 📖 |
| 识别引擎 · 连接 | `videoreact.asr.provider.connect-timeout-seconds` / `ASR_CONNECT_TIMEOUT_SECONDS` | **10 s** | 📖 |
| 识别引擎 · 读 | `videoreact.asr.provider.read-timeout-seconds` / `ASR_READ_TIMEOUT_SECONDS` | **120 s** | 📖（注意 `.env.example` 里写的是 30，与 `application.yml` 不一致） |
| 识别任务 · 轮询间隔 | `videoreact.asr.poll.interval` / `ASR_POLL_INTERVAL` | **5 s** | 📖 |
| 识别任务 · 等待上限 | 硬编码 `MAX_PROVIDER_POLLS = 120` × 轮询间隔 | **≈ 600 s（10 分钟）** | 📖 超过则任务 FAILED `PROCESSING_FAILED`、`retryable=true` |
| 识别任务 · 声明的超时配置 | `videoreact.asr.poll.timeout` / `ASR_POLL_TIMEOUT` 默认 `PT2H` | **该配置在契约路径下未被使用** | ⚠️ 见下 |
| 建议轮询时长（交接项） | `videoreact.contract.max-poll-seconds` / `MAX_POLL_SECONDS` 默认 **1800** | **未被任何代码使用**（连 `/health` 都没输出） | ⚠️ 因此「建议主项目最大轮询时长」这一交接项**目前没有可信数值**，暂按任务的 10 分钟处理上限对齐，需主项目与模块共同确认 |
| 主项目侧的轮询间隔 | 契约建议 2 s 起、最多 10 s 加抖动 | 由主项目决定 | 本模块不返回 `Retry-After`（不会 429/503） |

> ⚠️ **已知不一致（重要）**：`ASR_POLL_ENABLED` / `ASR_POLL_INITIAL_DELAY` / `ASR_POLL_BATCH_SIZE` / `ASR_POLL_TIMEOUT` 四个配置在契约路径下**没有任何使用点**（只有 `interval` 被 `JobRunner` 用到）。任何声称「轮询兜底 / 2 小时超时」的说法都与实现不符：实际等待上限是 10 分钟。

---

## 8. 凭证注入

| 项 | 值 / 行为 |
| --- | --- |
| 环境变量 | **`MODULE_BEARER_TOKENS`**（绑定 `videoreact.contract.bearer-tokens`） |
| 形式 | **逗号分隔的多个 token**（支持一次配多把，便于轮换期双活），Spring 绑定为 `List<String>` |
| 默认值 | **空** |
| 空值语义 | **失败关闭**：不配置任何凭证时，**所有 `/api/v1/**` 请求一律 401 `UNAUTHENTICATED`**，不放行（✅ 本轮实测：以 `MODULE_BEARER_TOKENS=` 空值启动，不带凭证、带任意 Bearer、带空 Bearer 三种请求**全部 401**） |
| 传输方式 | `Authorization: Bearer <token>`（仅此前缀形式；`Bearer` 后多余空白会被 trim，空 token 视为未提供） |
| 比对方式 | **常量时间**比对（`MessageDigest.isEqual`，且不短路），避免响应时间侧信道 |
| 401 响应体 | `{"code":"UNAUTHENTICATED","message":"缺少或无效的机器凭证","retryable":false,"requestId":"","details":[]}`——不透露"哪一位不对" |
| 轮换 | 支持**双活式轮换**：新旧 token 同时写入 `MODULE_BEARER_TOKENS`，主项目切换后移除旧值。⚠️ **需重启进程生效**（配置在启动时绑定，无热加载）；**无过期时间、无按 job 授权范围** |
| 凭证作用域 | **部署级**：一个 token 可提交/查询本部署的**任意**任务；查询时可选带 `X-Client-Request-Id` 做交叉校验（不匹配按 404 处理） |
| 禁止事项（契约 §6） | 凭证不得进 JSON fixture、前端、URL 或 Git；本仓库 `.env` 已在 `.gitignore` 中，日志不打印凭证值 |
| 识别引擎凭证 | 与机器凭证**相互独立**：`WAND_API_KEY`（TokenHub）或 `TENCENT_SECRET_ID`/`TENCENT_SECRET_KEY`（腾讯云标准 ASR）。未配置时任务失败为 `PROCESSING_FAILED`（如实测报文：`提交识别任务失败: TokenHub API Key 未配置…`，`retryable=false`） |

---

## 9. 运行说明

### 9.1 前置条件

| 项 | 要求 | 本轮核验 |
| --- | --- | --- |
| JDK | **17**（`maven.compiler.release=17`）；Spring Boot **4.0.8** | ✅ 本机 `mvn clean test` 通过 |
| Maven | 需使用工作区专用 settings：`-s .mvn/settings-sandbox.xml`（本机默认本地仓库不可写） | ✅ |
| ffmpeg / ffprobe | **必需**（媒体探测与音轨提取）。默认路径 `./.work/tools/ffmpeg/bin/{ffmpeg,ffprobe}.exe`，可用 `FFMPEG_PATH`/`FFPROBE_PATH` 覆盖 | ✅ 便携版已就位 |
| 数据库 | 内嵌 H2 文件库，默认 `jdbc:h2:file:./.data/transcription`（`MODE=MySQL`，`AUTO_SERVER=TRUE`）；可用 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/`DB_DRIVER` 换成 MySQL（需自行加 `flyway-mysql` 依赖） | 📖 |
| Flyway | 启动时自动迁移；迁移脚本 `src/main/resources/db/migration/V1__create_transcription_job.sql` 仅一张表 `transcription_job` | ✅ 启动成功即代表迁移可执行 |
| 机器凭证 | `MODULE_BEARER_TOKENS` **必须配置**，否则所有请求 401 | ✅ 实测 |
| 识别凭证 | `WAND_API_KEY`（提供方 `wand`，开箱可用）；`DASHSCOPE_API_KEY` + `DASHSCOPE_WORKSPACE_ID`（提供方 `aliyun-filetrans`，**另外还缺音频暂存**，见 §11.1 第 5 条）；腾讯云 SecretId/SecretKey（`tencent`，契约路径不可用） | ⚠️ 本机三者均未配置，真实识别未跑通 |
| 网络 | 生产要求模块能被主项目 HTTPS 访问；模块自身需能出网下载媒体（HTTPS）并调用识别引擎 | 📖 |

### 9.2 启动

```powershell
# 1) 载入 .env 到当前会话（Spring 不会自动读 .env）
. .\scripts\Use-Env.ps1

# 2) 启动（前台，Ctrl+C 停止）
.\scripts\Start.ps1
#   等价于：mvn -s .mvn/settings-sandbox.xml spring-boot:run

# 也常用（离线、指定端口）：
$env:SERVER_PORT="18080"; mvn -B -o -s .mvn/settings-sandbox.xml spring-boot:run
```

`.env` 中与契约联调**必需/相关**的项（其余见 §5、§7 的配置表）：

```ini
MODULE_BEARER_TOKENS=<随机长串，逗号分隔可配多把>   # 不配 → 全 401
MODULE_PROVIDER_ID=transcription-local              # 部署登记值，联调时必须与主项目登记一致
MODULE_VERSION=transcription-1.0.0
WAND_API_KEY=<TokenHub Key>                         # ASR_PROVIDER=wand（默认）时必需
ASR_PROVIDER=wand

# 换用阿里云百炼（当前还需先补音频暂存，否则每个任务都会失败，见 §11.1 第 5 条）
# ASR_PROVIDER=aliyun-filetrans
# DASHSCOPE_API_KEY=<百炼 API Key>
# DASHSCOPE_WORKSPACE_ID=<业务空间 ID>
# ALIYUN_ASR_MODEL=qwen-audio-3.0-asr-flash-filetrans
```

### 9.3 提交与查询（curl 形状，字段按契约 fixture 形状）

```bash
BASE=http://127.0.0.1:8080
TOKEN=<MODULE_BEARER_TOKENS 中的一把>

# 提交：首次 202；同 clientRequestId 同内容重提 200
curl -sS -i -X POST "$BASE/api/v1/jobs" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: req-20260913-0001" \
  -d '{
    "schemaVersion": "1.0",
    "clientRequestId": "req-20260913-0001",
    "moduleType": "TRANSCRIPTION",
    "classSessionId": "session-demo-001",
    "mediaId": "media-demo-001",
    "media": {
      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "durationMs": 60000,
      "mimeType": "video/mp4",
      "sourceUrl": "https://media.example.com/classroom-60s.mp4?sign=...",
      "urlExpiresAt": "2026-09-13T04:00:00Z"
    },
    "analysisRange": { "startMs": 0, "endMs": 60000 },
    "parameters": { "language": "zh-CN", "enableSpeakerLabels": false }
  }'

# 查询：200；终态 SUCCEEDED 带 payload，FAILED 带 error
curl -sS "$BASE/api/v1/jobs/job-<返回的 jobId>" \
  -H "Authorization: Bearer $TOKEN"

# 健康与能力边界（同样需要 Bearer）
curl -sS "$BASE/api/v1/health" -H "Authorization: Bearer $TOKEN"
```

注意事项（✅ 本轮实测过其中的返回码）：

- `Idempotency-Key` **必须与 body 的 `clientRequestId` 完全一致**，否则 422（不是 409）。
- 媒体地址必须 **HTTPS 且解析到公网地址**；内网/环回/链路本地/CGNAT 一律拒绝。仅本地联调可在显式开关 `MEDIA_ALLOW_LOOPBACK_HTTP=true` 下用 `http://127.0.0.1:...`（✅ 本轮就是这样跑通了「下载 → hash → ffprobe → 抽音轨」）。
- `media.sha256` 用 64 个 `a` 只是 fixture 形状的占位；真实提交必须是**真实字节的 SHA-256**，否则任务 FAILED `MEDIA_HASH_MISMATCH`（不可重试）。
- 下载链接过期（HTTP 401/403/404/410）→ 任务 FAILED `MEDIA_URL_EXPIRED`（可重试）。主项目续期后**用同一个 `clientRequestId` 重提**：指纹不含 `sourceUrl`/`urlExpiresAt`，因此会命中同一任务并更新下载凭据继续处理（✅ 有单测钉住）。

### 9.4 fixture 的生成与校验

- **本仓库不包含 fixture 文件**。契约样例（`transcription-request.json` / `transcription-success.json` / `transcription-failed.json`）与校验脚本 `verify_contract.py` 由**主项目文档包** `docs/integration/fixtures/classroom-analysis-v0.1/` 提供。
- 校验方式（在主项目文档包目录下，Python 3 标准库即可）：

  ```powershell
  python docs/integration/fixtures/classroom-analysis-v0.1/verify_contract.py
  ```

  该校验覆盖 fixture 自身的结构与关键不变量（`verify_contract.py` 不连接本模块服务，**不能替代 HTTP 联调**）。
- **本模块侧生成一份同形状的提交样例**：直接取主项目 `transcription-request.json`，把 `media.sourceUrl`/`urlExpiresAt`/`sha256`/`durationMs` 换成真实值（保持 §9.3 的字段形状）后 POST 到 `/api/v1/jobs` 即可。本轮就是以这种方式（60 s / `0–60000` / `zh-CN` / `enableSpeakerLabels=false`）实测通过 202/200/409/422/404/401 的。
- **fixture 与真实媒体的边界**（契约 §7 与 fixture 说明均强调）：fixture 是**人工合成**的（hash 为占位、URL 不可下载、时间/ID 为演示值），**不能当作真实处理证据**，也不代表真实识别已跑通。

---

## 10. 能力边界与不支持项（明确不支持）

| 项 | 行为 |
| --- | --- |
| **说话人标签** | **不支持**。`parameters.enableSpeakerLabels=true` → **422 `VALIDATION_FAILED`**（`details[].path=parameters.enableSpeakerLabels`，消息「首版不支持说话人标签，请传 false（不能静默忽略）」）。✅ 实测。所有 `segments.speakerLabel` 恒为 `null` |
| **置信度** | 引擎不提供置信度，因此 `segments.confidence` **恒为 `null`**——不会填 `1` 冒充 |
| **课程摘要 / 教学内容总结** | 不做（属主项目 M3 分析层） |
| **教学评价 / 教师排名 / 抬头率** | 不做（抬头率属抬头模块） |
| **教师授权 / 权限判定** | 不做。本模块不判断「谁能看这个任务」，只做部署级机器鉴权 |
| **报告发布 / 报告结构** | 不做，也不写主项目数据库（契约 §1：模块不访问主库） |
| **回调（callback）** | 不实现。主项目只能用提交 + 轮询 |
| **取消任务 / 结果分页 / 推送（SSE/WebSocket）** | 不支持 |
| **单段裁剪 / 变速 / 剪辑拼接 / 多镜头融合** | 不支持裁剪与变速。**注意**：请求里给了 `analysisRange` 子范围时，本模块**仍会对整条音轨做识别**，再把片段裁剪到该范围内返回（`processedRanges` 如实等于 `analysisRange`）。因此子范围**不会降低识别成本**，只影响返回内容 |
| **原始媒体长期存储** | 不做。临时文件在任务结束时清理（`MEDIA_KEEP_FILES=true` 可保留用于排错） |
| **结果修订（同 job 改终态）** | 不支持。终态不可变；纠错/换模型必须用新 `clientRequestId` 建新 job（契约 §4） |
| **回答「这段音频有没有语音」之外的音频理解** | 不做 |

---

## 11. 已知限制与未验证项

### 11.1 已知限制（实现事实，不是"未测"）

1. **单线程顺序处理**：并发度固定为 1，不可配置；长媒体会阻塞后续任务（§7.1）。
2. **重启后不恢复**：`resumeNonTerminal` 无调度调用点，残留的 `QUEUED`/`RUNNING` 任务不会继续（§7.1）。主项目若轮询不到终态，需要换 `clientRequestId` 重提。
3. **请求体与片段数上限未强制**（§5）：`max-body-bytes`、`max-segments` 只是声明。
4. **413 不会返回**；体积类失败走任务级 `PAYLOAD_TOO_LARGE`（§1.3 的偏差提示）。
5. **提供方能力并不对等，`ASR_PROVIDER` 不是随便换的**：

   | 提供方 | 契约路径可用？ | 原因 |
   | --- | --- | --- |
   | `wand`（TokenHub） | ✅ | 支持内联 base64 音频，契约路径直接可用 |
   | `tencent`（标准 ASR） | ❌ | `TencentAsrClient.submit` 只设置 `Url`，而契约路径只走内联（`AsrSubmitCommand.ofBytes`），会提交一个空 URL |
   | `aliyun-filetrans` | ⚠️ 代码就绪但**当前会失败** | 阿里云长音频档官方明确**不接受 Base64/二进制流，只收公网 URL**（FAQ 原文）；因此必须先有音频暂存（`AudioStaging`），而当前只提供"明确失败"的实现 |

   **当前唯一开箱可用的提供方是 `wand`（TokenHub）。** 阿里云那条路缺的不是代码而是
   一个基于对象存储的 `AudioStaging` 实现，取舍见 [`docs/阿里云模型选型.md`](阿里云模型选型.md) §4。

6. **`aliyun-filetrans` 的音频暂存是显式缺席，不是忘了写**：`AsrConfiguration.audioStaging()` 返回的
   `InlineAudioStaging` 对任何暂存请求明确抛错，启动时 `AsrStartupCheck` 打一行 ERROR 说明
   "该状态下每个转写任务都会失败"。之所以不让它回退到 `sourceUrl`：那样模块会在
   **没有核验引擎实际读到哪份字节**的前提下声称完成了 sha256 校验，这种失真不报错、只失效。
   `JobRunner.submitToProvider` 已经按 `AsrProviderClient.acceptsInlineAudio()` 分成两条路，
   接入真实暂存后**不需要改动 `JobRunner`**。
7. **课程热词未接线**：`HotwordListBuilder` 与 `videoreact.course-hotwords.*` 配置仍在，但 `JobRunner`
   不构造热词（`hotwordList` 传 `null`），且 TokenHub 请求体没有热词入口。
   注意换到阿里云后这条**才有使用点**（阿里云支持热词与 Prompt 上下文），TokenHub 路径下热词无效。
8. **`/health` 不做依赖探测**，恒 200 `UP`。
9. **凭证轮换需重启**，且 token 无按 job/providerId 的细粒度范围。
10. **识别引擎失败的可重试标注偏保守**：引擎明确返回 `failed` 时标 `retryable=false`；轮询超时/非成功终态标 `retryable=true`。

### 11.2 未验证项（诚实清单，不要当已完成）

**已从"未验证"移出（2026-09-15，真实凭据）：**

- ✅ **阿里云协议全链路已真机跑通**：`AliyunAsrClient` 提交 → 轮询 → 下载结果 JSON →
  解析出毫秒分段，端到端返回正确文本。用的是阿里云官方 3.9 秒样例音频。
- ✅ **`channel_id` 必须发数组**——真机踩到的 bug：发标量时提交返回 HTTP 200，
  任务却在异步阶段以 `parameter.channel_id must be an array` 失败。已修并加回归测试。
- ✅ **轮询是 GET**（原先 GET/POST 存疑）；`X-DashScope-Async` 必需（缺它 403）；
  `language_hints` 与 `diarization_enabled` 均被 filetrans 接受并生效。
- 逐项对照见 [`docs/阿里云模型选型.md`](阿里云模型选型.md) §5。

**仍未验证：**

1. **契约路由的完整真实媒体链路两侧都还没走通过一次**：
   - TokenHub 侧卡在有效 `WAND_API_KEY`（本机为空，且账号此前无额度）；
   - 阿里云侧**协议已通**，但卡在音频暂存（§11.1 第 5 条）。
   即"`/api/v1/jobs` → 真实下载 → 真实 ffmpeg → 真实引擎 → 落库"这条**端到端**尚未跑通。
   *旧沙盒接口*时代曾真实跑通识别（3.4 s 语音样本、4 MB MP4），但那不是 `/api/v1` 契约路由。
2. **非 WAV 容器在契约路径下的解码范围**（MP4 等）未实测（§3）。
3. **2 小时上限是否真的可用**：未实测。顺带一个未验证风险：2 小时 16 k 单声道 WAV ≈ **230 MB**，内联 base64 后 ≈ **307 MB**；本模块**没有内联体积守卫**，TokenHub `data` 通道的实际上限未实测（探测记录 §7.3 亦列为未确认）。
4. **TokenHub 的实际大小/时长上限**未确定（文档未给，只实测过 4 MB；探测记录 §7.1）。
5. **与主项目的时间容差**未确定（§6.2）。
6. **真实课堂音视频的字准率、远场/噪声表现、语种能力**未验证；本模块只转发引擎结果，不做质量评估。
   ⚠️ 阿里云真机测试用的是**一句话的样例音频**，不足以支撑任何质量结论，
   也不能据此认为 `qwen-audio-3.0-asr-flash-filetrans` 比 TokenHub 更准。
7. **幂等并发竞态未做真实并发验证**：唯一约束 + 指纹逻辑有单测，但没有多线程/多实例压测。
8. **重启/崩溃恢复、凭证过期续期、长稳运行**未做服务级验证。
9. **`GET /api/v1/jobs` 的响应时间/吞吐基线**未测（契约明确「本轮不编造未测 SLA」）。
10. **生产部署形态**（HTTPS 终止、反代、容器化、证书、日志采集）未定，本仓库不含任何部署配置。

### 11.3 测试现状（避免沿用旧文档的夸大说法）

- 当前工作区有 **11 个测试类、137 个用例**（其中 2 个真机用例默认跳过），本轮 `mvn clean verify` 实测
  **Tests run: 137, Failures: 0, Errors: 0, Skipped: 2**。
- 覆盖面（按类）：

  | 测试类 | 用例 | 覆盖 |
  | --- | --- | --- |
  | `contract/TranscriptionContractEndToEndTest` | 9 | 真实 HTTP 路由 + Bearer 鉴权 + 真实媒体下载与 sha256 核验 + 真实 ffprobe/ffmpeg + 真实 H2 落库 + 契约不变量 |
  | `contract/TranscriptionFixtureGenerationTest` | 6 | 生成 `docs/fixtures/transcription-module-v0.1/` 的四个 fixture |
  | `job/wire/JobEnvelopeInvariantsTest` | 30 | 信封不变量，含负例（revision、payload/error 配对、片段越界） |
  | `asr/client/AliyunAsrClientTest` | 23 | 阿里云协议：请求形状、状态迁移、结果 JSON 解析、错误与重试分类、真机报文回归 |
  | `job/service/TranscriptTimeMapperTest` | 17 | 时间映射与范围裁剪 |
  | `job/service/JobRequestValidatorTest` | 15 | 请求校验（语言、时长、键头一致） |
  | `job/service/JobServiceIdempotencyTest` | 11 | 提交幂等、凭据续期复用、409、终态不可变、404 不泄漏归属、身份取自配置 |
  | `job/service/RequestFingerprintTest` | 11 | 指纹构成（故意不含 `sourceUrl`/`urlExpiresAt`） |
  | `asr/config/AsrProviderWiringTest` | 8 | 三个提供方的装配分支、别名与大小写容忍、未知值快速失败、yml 配置块齐全 |
  | `job/service/LanguageHintsTest` | 3 | 语种码收敛 |
  | `asr/staging/InlineAudioStagingTest` | 2 | 暂存缺席时必须响亮失败 |
  | `asr/client/ManualAliyunLiveTest` | 2 | **真机联调，默认跳过**（设 `MANUAL_ASR_TEST_KEY` 后运行） |

- **`mock` 框架不可用**：本机 Mockito 的 inline MockMaker 需要 self-attach 外部进程，会失败；
  因此全部测试用手写假实现，**没有引入 mock 框架**。这是环境约束，不是设计偏好。
- **契约 fixture 校验**用的是主项目下发的官方 `verify_contract.py`（在 `.work/fixture-verify/`），
  校验器**不复制进 `tools/`**，而是按固定顺序找原件——复制会留下一份会悄悄变旧的规则。
  两套校验本轮均 PASS：本模块 fixture（12 项，含 8 个负例）与官方基线（11 个 JSON / 20 个负例）。
- 仍然**没有**：多线程/多实例并发压测、长稳运行、生产部署形态验证。
- 历史文档中的「117 个测试」属于**旧沙盒代码**的统计，对应当前代码树已不成立。

---

## 12. 联调前必须与主项目确认的问题

1. **时间容差**：M4 用同一段真实媒体核对起点/中点/末尾后，共同接受的偏差是多少（本模块目前不声明容差）。
2. **体积/条数超限的返回形态**：是「HTTP 413」还是接受「HTTP 200 + 任务 `FAILED` + `PAYLOAD_TOO_LARGE`」（当前实现是后者）。
3. **请求体上限**：主项目是否需要真正的 1 MiB 强制？如果需要，本模块需要新增请求体过滤器（本轮未改代码）。
4. **片段数上限与结果体积**：2 小时课堂的实际片段量级未知，是否需要上限与分页协商。
5. **并发与排队**：单 worker 顺序处理是否可接受；不可接受时再讨论多 worker + lease 锁。
6. **重启恢复**：主项目是否需要模块侧自动恢复非终态任务（当前不会）。
7. **凭证粒度**：是否需要把 token 与 `providerId`/`moduleType`/已分配 job 绑定（契约 §6 提到模块→主项目回调凭证要绑定，本模块的入口凭证目前是部署级）。
8. **`/health` 是否要免鉴权**、是否需要返回 503（依赖不健康时）。
9. **提供方**：生产是否只用 TokenHub（`wand`）；若要用腾讯云标准 ASR，需要先补内联提交能力（当前只支持 URL 提交）。
10. **部署登记值**：`baseUrl`、`providerId`、`moduleVersion`、`MODULE_BEARER_TOKENS` 的实际值需由部署方提供并在双方登记。

---

## 附：本轮实测记录（可复现）

| # | 操作 | 结果 |
| --- | --- | --- |
| 1 | `mvn clean test`（`-s .mvn/settings-sandbox.xml`，离线） | ✅ 编译通过，11 个用例全绿 |
| 2 | 启动：`SERVER_PORT=18080`、`MODULE_BEARER_TOKENS=local-doc-verify-token`、`MEDIA_ALLOW_LOOPBACK_HTTP=true` | ✅ 启动成功，Flyway 迁移可执行 |
| 3 | `GET /api/v1/health` 不带 token | ✅ 401 `UNAUTHENTICATED` |
| 4 | `GET /api/v1/health` 带 token | ✅ 200，返回 §1.4 的原文 |
| 5 | `POST /api/v1/jobs`（60 s / 0–60000 / `zh-CN` / `enableSpeakerLabels=false`） | ✅ 202 `QUEUED` |
| 6 | 同键同内容重提 | ✅ 200，同一 `jobId` |
| 7 | 同键换 `sha256` | ✅ 409 `IDEMPOTENCY_CONFLICT` |
| 8 | `enableSpeakerLabels=true` / `language=ja-JP` / `Idempotency-Key` 与 body 不一致 | ✅ 均 422 `VALIDATION_FAILED`（带 `details[].path`） |
| 9 | `schemaVersion=2.0` | ✅ 422 `UNSUPPORTED_SCHEMA` |
| 10 | `moduleType=HEAD_UP` | ✅ 403 `FORBIDDEN` |
| 11 | `GET` 不存在的 jobId / 带错误 `X-Client-Request-Id` | ✅ 均 404 `NOT_FOUND` |
| 12 | 非法 JSON | ✅ 400 `MALFORMED_JSON` |
| 13 | 约 2 MB 请求体 | ✅ **202**（反证请求体上限未强制） |
| 14 | 本地回环 HTTP 提交合成 WAV（3 s / `pcm_s16le` / 真实 sha256），`WAND_API_KEY` 为空 | ✅ 走通「下载 → hash 核验 → ffprobe → ffmpeg 抽音轨」，第 4 步提交识别时因未配置密钥失败：`PROCESSING_FAILED`、`retryable=false` |
| 15 | 以 `MODULE_BEARER_TOKENS=`（空）启动，再请求 `/api/v1/health`：不带 `Authorization`、带任意 Bearer、带空 Bearer | ✅ 三种情况**全部 401**，失败关闭语义成立（§8） |

> 第 14 项**只证明流水线前半段可用**，不构成真实识别已跑通的证据。
>
> 实测环境说明：以上均在**本机开发回环**上以 HTTP 完成，与生产部署（HTTPS、域名、真实凭证、真实课堂媒体）不是同一环境；测试产生的 job 记录留在本机开发库 `.data/transcription`（H2）中，可用 `clientRequestId` 前缀 `doc-verify-` 识别。
