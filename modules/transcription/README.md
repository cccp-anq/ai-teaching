# 转写模块（《三项目公共接口契约 v0.1》实现）

> **一句话**：把主项目已授权的课堂媒体（HTTPS 受控下载地址）取回来，核验 SHA-256、抽出音轨、送识别引擎，
> 最后按**原媒体毫秒轴**输出带时间戳的转写结果——只做转写，不做报告。
>
> - 对外接口：契约 §2 的三个路由（`POST /api/v1/jobs`、`GET /api/v1/jobs/{jobId}`、`GET /api/v1/health`）
> - 实现栈：Spring Boot 4.0.8 + Java 17 + H2（Flyway 迁移）+ ffmpeg/ffprobe
> - 识别引擎：默认腾讯云 **TokenHub `wand-asr-v1`**（Bearer Key，异步提交 + 轮询 + SRT 解析）；
>   另已接入**阿里云百炼非实时语音识别**（`aliyun-filetrans`，12 小时长音频档），见 §2.1 与 §7
>
> **所有交接数值（baseUrl、上限、语言、时间映射、并发、超时、凭证注入、能力边界、未确定项）见 [`docs/交接表-transcription-v0.1.md`](docs/交接表-transcription-v0.1.md)——那是给主项目登记用的权威表格**，本文只讲怎么跑、怎么看。
>
> 识别引擎的实测结论（含它的能力与限制）见 [`docs/TokenHub接口探测记录.md`](docs/TokenHub接口探测记录.md)。
> 阿里云模型的选型理由、协议形状与"文档值 vs 实测值"见 [`docs/阿里云模型选型.md`](docs/阿里云模型选型.md)。

## 1. 这个模块是什么，在三个项目里的位置

按《三项目公共接口契约 v0.1》§1 的边界划分：

| 项目 | 负责 | 不负责 |
| --- | --- | --- |
| **主项目** | 创建 `classSessionId` / `mediaId` / `clientRequestId`、授权任务、受控下载地址、标准接收、持久化、统计、分析模型、报告与页面 | 重做识别算法 |
| **转写（本模块）** | 媒体解码与音频处理、带时间戳转写、处理范围与质量告警、任务查询与标准结果 | 生成课程报告、决定教师权限、直写主库 |
| 抬头模块 | 观测口径、采样与姿态判断、比例/人数、质量与区间 | 计算出席率、给教学打分 |
| 主项目计划 | 见《主项目实施计划》M1～M5，本模块对应 **M4（两模块适配器 + 真实媒体时间验证）** | — |

本模块的硬边界（契约 §1）：

- **不访问主项目数据库**，只处理主项目已分配的媒体并返回标准结果；
- 信封里的 `providerId` / `moduleVersion` **取自部署配置**，调用方无法伪造；
- 时间一律映射回**原媒体毫秒轴**（`timeBase = MEDIA_START_MS`），不吃掉偏移；
- 终态不可变：纠错、换模型、重新分段都必须用**新的 `clientRequestId`** 建新 job。

> 契约与专用 payload 文档（`classroom-analysis-contract-v0.1.md`、`transcription-module-v0.1.md`、fixture 说明）由**主项目文档包**维护，未随本仓库提交；请与本文 §3 的接口表一起核对，冲突时以契约与 `application.yml` 为准。

### 1.1 与历史「ASR 沙盒」的关系

本仓库早期是「腾讯云 ASR 沙盒」，对外暴露过一套自定义接口
（`/api/videos/{videoId}/transcription*`）。**那套接口已经删除**，取而代之的是契约统一的
`/api/v1/jobs` 三个路由——主项目才能用同一套适配器接多个模块。

仍在仓库里、但属于历史的文件：`scripts/Smoke.ps1`（调旧路由，已不可用）、
`docs/首次开通清单.md`（腾讯云标准 ASR 的开通指引）、`.env.example`（含若干已失效的键）。
**这些不影响契约路由的使用**，但不要照着它们来联调。识别引擎的实测记录（哪些结论经过真实验证、哪些仍是未知）
完整保留在 [`docs/TokenHub接口探测记录.md`](docs/TokenHub接口探测记录.md)。

---

## 2. 快速开始

### 2.1 配置 `.env`（必需项）

```powershell
Copy-Item .env.example .env      # .env 已在 .gitignore 中，不会入库
```

**.env 只支持 `KEY=VALUE`，Spring 不会自动读它**，需要用 `scripts/Use-Env.ps1` 载入。

契约联调**必需/相关**的项：

```ini
# ── 机器凭证（必须配！留空 = 失败关闭：所有 /api/v1 请求 401）──
# 逗号分隔可配多把，便于轮换期双活；必须是随机长串，禁止进 Git / fixture / 日志
MODULE_BEARER_TOKENS=change-me-to-a-long-random-string

# ── 部署身份（主项目按值登记，改这里就等于换了一次实现版本）──
MODULE_PROVIDER_ID=transcription-local
MODULE_VERSION=transcription-1.0.0

# ── 识别引擎（默认提供方 wand = TokenHub）──
ASR_PROVIDER=wand
WAND_API_KEY=sk-xxxxxxxx
WAND_BASE_URL=https://tokenhub.tencentmaas.com
WAND_MODEL=wand-asr-v1

# ── 若要改用阿里云百炼（aliyun-filetrans，12 小时长音频档）──
# ASR_PROVIDER=aliyun-filetrans
# DASHSCOPE_API_KEY=sk-xxxxxxxx
# DASHSCOPE_WORKSPACE_ID=<业务空间 ID>
# ALIYUN_ASR_MODEL=qwen-audio-3.0-asr-flash-filetrans
```

> ⚠️ **TokenHub 需要先开通后付费或领取额度**，否则提交会返回 402（`401007`）：
> <https://console.cloud.tencent.com/tokenhub/inference>
>
> ⚠️ **`tencent` 提供方在契约路径不可用**：契约路径走内联 base64，而 `TencentAsrClient` 只实现了 URL 提交。
> 设为 `tencent` 会提交空 URL，属于已知缺口，见 §7。
>
> ⚠️ **`aliyun-filetrans` 目前也跑不通，但原因不同**：阿里云长音频档官方明确
> **不接受 Base64/二进制流，只收公网 URL**，因此必须先有音频暂存（`AudioStaging`）。
> 当前 `audioStaging` 只提供"明确失败"的实现，启动时 `AsrStartupCheck` 会打一行 ERROR 说清楚。
> 选型理由与三条可选路线见 [`docs/阿里云模型选型.md`](docs/阿里云模型选型.md) §4。

### 2.2 启动

前置条件：**JDK 17**、**ffmpeg 与 ffprobe**（默认找 `./.work/tools/ffmpeg/bin/`，可用 `FFMPEG_PATH` / `FFPROBE_PATH` 覆盖）。

```powershell
# 方式 A：脚本（自动载入 .env，前台运行，Ctrl+C 停止）
.\scripts\Start.ps1

# 方式 B：直接用 maven（务必带 -s .mvn/settings-sandbox.xml，见 §8）
. .\scripts\Use-Env.ps1
mvn -B -s .mvn/settings-sandbox.xml spring-boot:run

# 换端口：
$env:SERVER_PORT="18080"; mvn -B -o -s .mvn/settings-sandbox.xml spring-boot:run
```

启动成功后，`GET /api/v1/health`（**需要 Bearer**）会回一段能力边界，用它先和主项目对齐再联调：

```bash
curl -sS http://127.0.0.1:8080/api/v1/health -H "Authorization: Bearer $TOKEN"
```

### 2.3 用 curl 提交一个任务

字段形状按契约 fixture（60 秒媒体、`analysisRange` 覆盖全片、`language=zh-CN`、`enableSpeakerLabels=false`）。
**`sha256` 必须是真实字节的 SHA-256**（下面的 64 个 `a` 只是占位），`sourceUrl` 必须是公网可达的 HTTPS 短期受控地址。

```bash
BASE=http://127.0.0.1:8080
TOKEN=change-me-to-a-long-random-string

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
```

首次返回 **202**，body 是 `JobEnvelope`（`status=QUEUED`、`revision=0`、`payload`/`error` 均为 `null`），
记下其中的 `jobId`。

三条容易踩的规则：

1. `Idempotency-Key` 一旦给出，**必须与 body 的 `clientRequestId` 完全一致**，否则 422（不是 409）。
2. 同 `clientRequestId` + 同内容重提 → **200**，复用同一个 job（不重复计费）；**内容变了还复用旧键 → 409**，请换新键。
3. 续期下载链接后**继续用原键重提**：指纹不含 `sourceUrl`/`urlExpiresAt`，会命中同一任务并更新凭据继续处理（任务已终态则原样返回）。

### 2.4 查询结果

```bash
curl -sS "$BASE/api/v1/jobs/<jobId>" -H "Authorization: Bearer $TOKEN"
```

结果信封（成功终态，示意）：

```json
{
  "schemaVersion": "1.0", "clientRequestId": "req-20260913-0001", "jobId": "job-…",
  "moduleType": "TRANSCRIPTION", "providerId": "transcription-local",
  "moduleVersion": "transcription-1.0.0",
  "classSessionId": "session-demo-001", "mediaId": "media-demo-001",
  "mediaSha256": "…", "durationMs": 60000, "timeBase": "MEDIA_START_MS",
  "analysisRange": { "startMs": 0, "endMs": 60000 },
  "status": "SUCCEEDED", "revision": 1,
  "createdAt": "2026-09-13T04:00:01Z", "updatedAt": "2026-09-13T04:02:30Z",
  "payload": {
    "language": "zh-CN", "engineVersion": "wand-asr-v1",
    "processedRanges": [ { "startMs": 0, "endMs": 60000 } ],
    "segments": [
      { "id": "seg-001", "startMs": 11965, "endMs": 14515, "text": "…",
        "confidence": null, "speakerLabel": null }
    ],
    "warnings": []
  },
  "error": null
}
```

读结果时的三条不变量：

- `segments.startMs/endMs` 是**原媒体毫秒**；`processedRanges` 表示"处理完成"，`segments` 表示"有文本"，**两者不能混用**；
- 合法静音可以 `SUCCEEDED` + `segments=[]` + `warnings=["NO_SPEECH"]`（这与"解码失败被包装成静音"必须区分）；
- 失败终态 `payload=null`，`error={code, message, retryable}`；`message` 不含堆栈、密钥与媒体 URL。

轮询建议（契约 §2 的工程初值，不是 SLA）：**2 秒起、最多 10 秒并加抖动**；提交超时后用**同一个 `clientRequestId`** 重试。
本模块**不返回 `Retry-After`**（不会 429/503），可重试性只体现在任务 `error.retryable`。

### 2.5 构建与测试

```powershell
.\scripts\Mvn.ps1 test        # 或 mvn -B -s .mvn/settings-sandbox.xml test
.\scripts\Mvn.ps1 package
```

当前测试规模（诚实版）：**12 个测试类、148 个用例**（其中 2 个真机用例默认跳过），
本轮实测 `mvn clean verify` 为 **Tests run: 148, Failures: 0, Errors: 0, Skipped: 2**。

| 测试类 | 用例 | 覆盖 |
| --- | --- | --- |
| `contract/TranscriptionContractEndToEndTest` | 9 | 真实 HTTP 路由 + Bearer + 真实媒体下载与 sha256 + 真实 ffmpeg + H2 落库 |
| `contract/TranscriptionFixtureGenerationTest` | 6 | 生成 `docs/fixtures/transcription-module-v0.1/` 的四个 fixture |
| `job/wire/JobEnvelopeInvariantsTest` | 30 | 信封不变量（含负例：revision、payload/error 配对、越界片段） |
| `asr/client/AliyunAsrClientTest` | 23 | 阿里云协议：请求形状、状态迁移、结果 JSON 解析、错误分类、真实报文回归 |
| `job/service/TranscriptTimeMapperTest` | 17 | 时间映射与范围裁剪 |
| `job/service/JobRequestValidatorTest` | 15 | 请求校验（语言、时长、键头一致） |
| `job/service/JobServiceIdempotencyTest` | 11 | 幂等 / 409 / 404 / 终态不可变 |
| `job/service/RequestFingerprintTest` | 11 | 指纹构成（故意不含 sourceUrl/urlExpiresAt） |
| `asr/staging/StagingPropertiesTest` | 10 | 暂存配置归一化、双端点推导、缺项提示、不泄漏凭证 |
| `asr/config/AsrProviderWiringTest` | 9 | 提供方装配分支、别名与大小写、未知值快速失败、yml 配置块齐全 |
| `job/service/LanguageHintsTest` | 3 | 语种码收敛 |
| `asr/staging/InlineAudioStagingTest` | 2 | 暂存缺席时必须响亮失败 |
| `asr/client/ManualAliyunLiveTest` | 2 | **真机联调，默认跳过** |

真机联调（会真实调用阿里云，提交约 4 秒音频，成本可忽略）：

```powershell
$env:MANUAL_ASR_TEST_KEY = "sk-..."
mvn -B -s .mvn/settings-sandbox.xml test "-Dtest=ManualAliyunLiveTest"
```

> **这个测试不是装饰**：2026-09-15 正是靠真机发现 `channel_id` 必须发数组——
> 发标量时**提交返回 HTTP 200、任务却在异步阶段才失败**，离线测试永远抓不到。
> 全部实测结论回填在 [`docs/阿里云模型选型.md`](docs/阿里云模型选型.md) §5。

> 沙盒限制：**Mockito 在本机不可用**（inline MockMaker 需要 self-attach 外部进程，会失败），
> 因此全部测试用手写假实现，不引入 mock 框架。

另有两套 **fixture 校验**（Python），校验函数用的是主项目下发的官方 `verify_contract.py`：

```powershell
# 本模块生成的 fixture（用官方校验函数跑）
python docs/fixtures/transcription-module-v0.1/tools/verify_module_fixtures.py docs/fixtures/transcription-module-v0.1

# 官方基线原件
python .work/fixture-verify/verify_contract.py .work/fixture-verify
```

> 校验器**不复制**到 `tools/` 下，而是按固定顺序找官方原件
> （`tools/` → `docs/fixtures/` → `.work/fixture-verify/`）。
> 复制一份会留下一份会**悄悄变旧**的校验器：主项目改了规则之后，
> 这里仍拿着旧规则打印 PASS，而 PASS 恰恰最不会被人复查。

> 环境提示：`target/` 陈旧时增量编译可能报构造器不匹配之类的怪错，先 `mvn clean test` 再判断。

---

## 3. 接口与返回码

完整路径 = `baseUrl` + `/api/v1`。**三个路由都需要 `Authorization: Bearer <MODULE_BEARER_TOKENS 中的一把>`**，包括 `/health`。

| 方法/路径 | 请求要点 | 响应 |
| --- | --- | --- |
| `POST /api/v1/jobs` | `JobRequest`（契约 §3）；`Idempotency-Key` 可选但必须等于 `clientRequestId` | 首次 **202**／重复 **200**，body 为完整 `JobEnvelope` |
| `GET /api/v1/jobs/{jobId}` | 可选头 `X-Client-Request-Id`（带上则必须一致） | **200** `JobEnvelope`；不存在或键不匹配 → **404** |
| `GET /api/v1/health` | 无 | **200**（当前恒 200），只含身份/接口版本/能力边界，不含密钥与队列详情 |

| HTTP | code | 含义 | 本轮实测 |
| --- | --- | --- | --- |
| 202 | — | 首次受理（`status=QUEUED`） | ✅ |
| 200 | — | 幂等复用同一 job；或 `GET` 查询成功 | ✅ |
| 400 | `MALFORMED_JSON` | 请求体不是合法 JSON / 字段类型不符 | ✅ |
| 401 | `UNAUTHENTICATED` | 缺少或无效 Bearer；**未配置凭证时一律 401（失败关闭，已实测）** | ✅ |
| 403 | `FORBIDDEN` | `moduleType` 不是 `TRANSCRIPTION` | ✅ |
| 404 | `NOT_FOUND` | 任务不存在**或无权**（二者同形，不泄漏归属） | ✅ |
| 409 | `IDEMPOTENCY_CONFLICT` | 同 `clientRequestId` 但内容（身份/hash/duration/range/parameters）不同 | ✅ |
| 422 | `VALIDATION_FAILED` | 字段缺失或取值非法（含 `enableSpeakerLabels=true`、不支持的语言、超时长、键头不一致） | ✅ |
| 422 | `UNSUPPORTED_SCHEMA` | `schemaVersion` 不是 `1.0` | ✅ |
| 413 | `PAYLOAD_TOO_LARGE` | — | ❌ **当前不会返回**（请求体上限未强制，实测 2 MB 请求体被正常受理） |
| 429 / 503 | `RATE_LIMITED` / `TEMPORARILY_UNAVAILABLE` | — | ❌ 当前不会返回 |
| 409 | `RESULT_CONFLICT` / `ASSOCIATION_MISMATCH` | — | ❌ 当前不会返回（只定义了常量） |

> ⚠️ **需要主项目适配的一处偏差**：媒体**体积**超限不会返回 HTTP 413，而是「HTTP 200 + 任务 `FAILED` + `error.code=PAYLOAD_TOO_LARGE`」；
> 下载地址非 HTTPS 会得到任务级 `VALIDATION_FAILED`、主机不在白名单会得到任务级 `FORBIDDEN`。
> 契约 §5 的任务级错误码没有列这三个。**本轮未改代码，留待联调评审决定改实现还是主项目适配。**

---

## 4. 处理流水线

### 4.1 六步

```
POST /api/v1/jobs  →  202 + JobEnvelope(QUEUED)        受理即返回，处理在单 worker 线程里跑
        │
        ├─ 1. 受控下载   仅 HTTPS（本地回环需显式开关）；拒绝环回/私网/链路本地/组播/CGNAT；
        │                最多 3 次重定向且每跳重新校验地址；边下边算 SHA-256；强制最大字节数。
        ├─ 2. hash 核验  与 media.sha256 比对；不一致 → FAILED MEDIA_HASH_MISMATCH（不可重试）。
        ├─ 3. 探测+抽音轨 ffprobe 读时长/音轨；时长读不出 → DECODE_FAILED；无音轨 → UNSUPPORTED_MEDIA；
        │                ffmpeg 抽整条音轨为 16k/单声道/pcm_s16le/WAV（-vn，不裁剪、不删静音）。
        ├─ 4. 提交识别   提供方支持内联 → 音频 base64 内联提交（不外泄受控 sourceUrl）；
        │                提供方只收 URL（阿里云 filetrans）→ 先经 AudioStaging 暂存音轨换预签名地址，
        │                再用该地址提交；暂存对象保留到轮询结束才删。
        ├─ 5. 轮询终态   每 5 秒查询一次，最多 120 次（≈10 分钟）；TokenHub 异步成功只给 SRT，
        │                需下载并解析出带时间戳的分段；阿里云成功只给 transcription_url，
        │                需再下一个 JSON 并解析 transcripts[].sentences[]。
        └─ 6. 时间映射+落库  片段回映射到原媒体轴（整条音轨 ⇒ 偏移恒为 0），裁剪到 analysisRange 内，
                         text 为空的片段丢弃，id 生成 seg-001…；写入终态后不再改动。
```

时间映射的核心承诺（契约 §1/§4）：

```
当前：整条音轨提取 ⇒  原媒体毫秒 ≡ 音频毫秒，offsetMs 恒为 0
将来支持单段裁剪时：原媒体毫秒 = 片段毫秒 + offsetMs
唯一改动点：TranscriptTimeMapper.offsetFor(clipStartMs)（现由 JobRunner 以 0 调用）
```

### 4.2 失败分类

| `error.code` | 触发条件 | `retryable` | 主项目该做什么 |
| --- | --- | --- | --- |
| `MEDIA_URL_EXPIRED` | 下载被拒（HTTP 401/403/404/410），链接过期 | `true` | 续期后**用同一个 `clientRequestId`** 重提 |
| `MEDIA_HASH_MISMATCH` | 内容与登记的 SHA-256 不一致 | `false` | 先修数据，重试无效 |
| `UNSUPPORTED_MEDIA` | 无音轨，或抽出的音频无有效内容 | `false` | 换媒体或先修转码 |
| `DECODE_FAILED` | 容器损坏、读不出时长、ffmpeg 退出码非 0 | `false` | 数据问题，重试无效 |
| `PROCESSING_FAILED` | 识别引擎失败/未进入成功终态/轮询超时/内部错误 | 视情况 | 看 `message` 与 `retryable`；超时类可重试 |
| `PAYLOAD_TOO_LARGE` | 下载体积超过本部署上限 | `false` | 缩小媒体或与模块协商上限（**非标准任务码**，见 §3 偏差提示） |

以下几项**不是失败**，而是 `warnings`（`SUCCEEDED` 时出现）：

| warning | 含义 |
| --- | --- |
| `NO_SPEECH` | 合法静音：处理完成但没有文本（`segments=[]`） |
| `DURATION_MISMATCH:registered=…,actual=…` | 真实时长与登记值偏差超过容差**仅告警**，信封 `durationMs` 仍是请求值，不静默裁切 |
| `SEGMENTS_CLIPPED_TO_RANGE:n` / `SEGMENTS_DROPPED_OUT_OF_RANGE:n` / `SEGMENTS_DROPPED_BLANK:n` | 片段被裁剪/丢弃的计数（**非契约列举的 warning 名**，属实现细节，主项目不应据此做业务判断） |

### 4.3 幂等与终态

- 幂等键：`clientRequestId`（库上唯一约束）。指纹 = `schemaVersion`、`clientRequestId`、`moduleType`、
  `classSessionId`、`mediaId`、`media.sha256/durationMs/mimeType`、`analysisRange`、`parameters`；
  **不含** `sourceUrl`/`urlExpiresAt`（它们是可续期的下载凭据）。
- 终态（`SUCCEEDED` / `FAILED`）**不可变**，`revision` 为 1；非终态 `revision=0`；
  迟到的失败不会覆盖已成功的结果。修改结果只能新建 job（新 `clientRequestId`）。
- 状态迁移只有 `QUEUED → RUNNING → 终态`；本版不支持取消。

---

## 5. 配置项表

全部可用环境变量覆盖；数值以 `src/main/resources/application.yml` 为准（**改配置前先读该文件**）。

### 5.1 契约与鉴权（`videoreact.contract`）

| 配置键 | 环境变量 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `provider-id` | `MODULE_PROVIDER_ID` | `transcription-local` | 本部署供应者标识，主项目按值登记 |
| `module-version` | `MODULE_VERSION` | `transcription-1.0.0` | 实现版本；换模型/换抽音轨策略都要改 |
| `bearer-tokens` | `MODULE_BEARER_TOKENS` | 空 | 逗号分隔的机器凭证；**留空 = 所有 `/api/v1` 请求 401** |
| `max-body-bytes` | `MAX_BODY_BYTES` | `1048576` | 声明值，**仅在 `/health` 输出，未强制执行** |
| `max-segments` | `MAX_SEGMENTS` | `20000` | 声明值，**未强制执行** |
| `max-poll-seconds` | `MAX_POLL_SECONDS` | `1800` | 声明值，**当前无任何使用点** |

### 5.2 媒体（`videoreact.media`）

| 配置键 | 环境变量 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `work-dir` | `MEDIA_WORK_DIR` | `./.work/media` | 媒体与音频临时目录（任务结束清理） |
| `ffmpeg-path` / `ffprobe-path` | `FFMPEG_PATH` / `FFPROBE_PATH` | `./.work/tools/ffmpeg/bin/ffmpeg.exe` / `…ffprobe.exe` | 外部工具路径 |
| `connect-timeout-seconds` | `MEDIA_CONNECT_TIMEOUT` | `10` | 下载连接超时（秒，代码取 `max(5,值)`） |
| `download-timeout-seconds` | `MEDIA_DOWNLOAD_TIMEOUT` | `600` | 单次下载请求超时（秒） |
| `probe-timeout-seconds` | `MEDIA_PROBE_TIMEOUT` | `180` | ffprobe/ffmpeg 超时（秒，两者共用） |
| `max-download-bytes` | `MEDIA_MAX_DOWNLOAD_BYTES` | `2147483648`（2 GiB） | 单次下载上限；超限 → 任务失败 `PAYLOAD_TOO_LARGE` |
| `max-duration-ms` | `MEDIA_MAX_DURATION_MS` | `7200000`（2 小时） | 媒体时长上限；超限 → **422** |
| `host-allow-list` | `MEDIA_HOST_ALLOW_LIST` | 空 | 下载主机白名单；留空表示不限（仍拒绝内网/环回） |
| `allow-loopback-http` | `MEDIA_ALLOW_LOOPBACK_HTTP` | `false` | **仅本地联调**可开：允许 http + 环回地址；生产必须 `false` |
| `duration-tolerance-ms` | `MEDIA_DURATION_TOLERANCE_MS` | `2000` | 与登记时长的容差；超出只告警不裁切 |
| `keep-media-and-audio` | `MEDIA_KEEP_FILES` | `false` | 保留临时媒体/音频用于排错 |

### 5.3 识别引擎（`videoreact.asr`）

| 配置键 | 环境变量 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `provider.type` | `ASR_PROVIDER` | `wand` | `wand`=TokenHub（**契约路径当前唯一可用**）；`tencent`=标准 ASR（只实现 URL 提交）；`aliyun-filetrans`=阿里云百炼（**缺音频暂存，见 §7**） |
| `provider.wand.base-url` | `WAND_BASE_URL` | `https://tokenhub.tencentmaas.com` | TokenHub 网关 |
| `provider.wand.api-key` | `WAND_API_KEY` | 空 | **未配置时任务失败**（`PROCESSING_FAILED`） |
| `provider.wand.model` | `WAND_MODEL` | `wand-asr-v1` | 模型 ID，也是 `payload.engineVersion` 的来源 |
| `provider.aliyun.workspace-id` | `DASHSCOPE_WORKSPACE_ID` | 空 | 业务空间 ID；配了就改写为专属域名 `https://{id}.cn-beijing.maas.aliyuncs.com/api/v1` |
| `provider.aliyun.base-url` | `DASHSCOPE_BASE_URL` | `https://dashscope.aliyuncs.com/api/v1` | 仅在未配 `workspace-id` 时生效（末尾斜杠会被吃掉） |
| `provider.aliyun.api-key` | `DASHSCOPE_API_KEY` | 空 | 百炼 API Key；未配置时提交前即报错 |
| `provider.aliyun.model` | `ALIYUN_ASR_MODEL` | `qwen-audio-3.0-asr-flash-filetrans` | 12 小时 / 2GB 长音频档 |
| `provider.aliyun.language-hints` | `ALIYUN_ASR_LANGUAGE_HINTS` | 空 | 语种短码（`zh,en`）；**详情页写"仅 paraformer-v2 支持"，故默认留空不发** |
| `provider.aliyun.diarization-enabled` | `ALIYUN_ASR_DIARIZATION` | `false` | 说话人分离；**仅单声道**（本模块抽的就是单声道） |
| `provider.aliyun.speaker-count` | `ALIYUN_ASR_SPEAKER_COUNT` | `0` | 说话人数参考值 2–100；`0` 表示不传、由算法自判 |
| `provider.aliyun.vocabulary-id` | `ALIYUN_ASR_VOCABULARY_ID` | 空 | 定制热词表 ID；为空表示不传 |
| `provider.aliyun.channel-id` | `ALIYUN_ASR_CHANNEL_ID` | `0` | 要识别的音轨索引 |
| `provider.connect-timeout-seconds` | `ASR_CONNECT_TIMEOUT_SECONDS` | `10` | 引擎连接超时 |
| `provider.read-timeout-seconds` | `ASR_READ_TIMEOUT_SECONDS` | `120` | 引擎读超时（`.env.example` 里写 30，以本表为准） |
| `provider.tencent.secret-id` / `secret-key` / `region` | `TENCENT_SECRET_ID` / `TENCENT_SECRET_KEY` / `TENCENT_REGION` | 空 / 空 / `ap-guangzhou` | 标准 ASR 凭证（当前只有 URL 提交路径） |
| `provider.endpoint` | `ASR_ENDPOINT` | `asr.tencentcloudapi.com` | 标准 ASR 云 API 域名 |
| `poll.interval` | `ASR_POLL_INTERVAL` | `5s` | 契约路径**唯一被使用**的轮询配置 |
| `poll.enabled` / `initial-delay` / `batch-size` / `timeout` | `ASR_POLL_ENABLED` / `ASR_POLL_INITIAL_DELAY` / `ASR_POLL_BATCH_SIZE` / `ASR_POLL_TIMEOUT` | `true` / `10s` / `20` / `PT2H` | **契约路径下没有使用点**——不要据此认为"2 小时超时"生效；实际上限是 120 × 5s ≈ 10 分钟 |
| `engine.*` | `ASR_ENGINE_MODEL_TYPE`、`ASR_CHANNEL_NUM` … | `16k_zh_en_2.0`、`1` … | 标准 ASR 引擎参数（`wand` 路径不使用） |
| `debug.dump-raw-result` / `dump-dir` | `ASR_DEBUG_DUMP_RAW_RESULT` / `ASR_DEBUG_DUMP_DIR` | `false` / `./.work/asr-raw` | 落盘引擎原始返回，排错用 |

### 5.4 服务与数据库

| 配置键 | 环境变量 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `server.port` | `SERVER_PORT` | `8080` | 监听端口 |
| `spring.datasource.url` | `DB_URL` | `jdbc:h2:file:./.data/transcription;MODE=MySQL;…` | 模块**自己的库**（契约：不访问主库） |
| `spring.datasource.username` / `password` / `driver-class-name` | `DB_USERNAME` / `DB_PASSWORD` / `DB_DRIVER` | `sa` / 空 / `org.h2.Driver` | 数据源 |
| `spring.flyway.*` | — | 启用，`classpath:db/migration` | 迁移脚本 `V1__create_transcription_job.sql`（单表 `transcription_job`） |

> ⚠️ `videoreact.course-hotwords.*`（课程热词）与 `.env.example` 里的
> `ASR_PROBE_BEFORE_SUBMIT` / `ASR_MAX_FILE_SIZE_BYTES` / `ASR_AUDIO_URL` 等键**在契约路径下均未接线或已失效**，见 §7。

---

## 6. 与主项目联调的顺序

按 fixture 说明里的**按里程碑联调矩阵**推进，不要跳步：

| 步骤 | 做什么 | 预期 | 本轮真实状态 |
| --- | --- | --- | --- |
| 1 | **先用 fixture 导入**（不要求本服务在线）：主项目 M2 导入 `transcription-success.json` / `transcription-failed.json`，跑 `verify_contract.py` | JSON 结构、统计与不变量通过 | 主项目侧；fixture 为人工合成，**不能当真实处理证据** |
| 2 | **契约形状对齐**：主项目用 fixture 的 `transcription-request.json` 形状 POST `/api/v1/jobs` | 202/200/409/422/404/401 分流正确 | ✅ 本模块已按形状实测这些返回码（见交接表附录） |
| 3 | **同一媒体核对起点/中点/末尾**（M4）：真实媒体 → 本模块 → 结果；比对三处时间与主项目播放器跳转 | 偏差在**双方商定的容差**内 | ⚠️ **未执行**：需要真实媒体 + 真实识别凭证 + 双方约定容差。**容差数值目前未确定** |
| 4 | 真实媒体下的异常路径：URL 过期续期、hash 不符、无音轨、部分处理、静音成功 | 分类正确、终态不可变、同键不重复计费 | ⚠️ 仅代码路径明确，未用真实媒体执行 |
| 5 | 第二段独立媒体复跑 + 人工交叉验收（M5） | 可重复交付 | ⚠️ 未执行 |

联调前请先做两件事：

1. 读 `/api/v1/health` 并把 `providerId` / `moduleVersion` **按实际值登记**（不要抄本文默认值）；
2. 与主项目确认 [`docs/交接表-transcription-v0.1.md`](docs/交接表-transcription-v0.1.md) §12 的待确认问题（容差、超限返回形态、请求体上限、并发、重启恢复、凭证粒度）。

---

## 7. 已知限制与未验证项

**已知限制（实现事实）**

1. **单线程顺序处理**：并发度固定 1，不可配置；长媒体会阻塞后续任务排队；不拒绝并发提交（因此没有 429）。
2. **重启后不恢复**：`resumeNonTerminal()` 没有调度调用点，残留的 `QUEUED`/`RUNNING` 任务不会被推进，只能换键重提。
3. **请求体/片段数上限未强制**：`max-body-bytes`(1 MiB)、`max-segments`(20000) 只在 `/health` 里声明；**413 永远不会返回**（实测 2 MB 请求体被受理）。
4. **`tencent` 提供方不可用**：契约路径走内联 base64，`TencentAsrClient` 只实现了 `Url` 提交（会是 `null`）。
5. **`aliyun-filetrans` 缺音频暂存**：阿里云长音频档只收公网 URL，而 `audioStaging` 当前只有
   "明确失败"的实现。**配置项已全部占位**（`videoreact.staging.*`，见 `.env.example` 第四节），
   差的是上传实现本身。开通步骤与安全要求见 [`docs/OSS暂存接入方案.md`](docs/OSS暂存接入方案.md)。
   启动时 `AsrStartupCheck` 会打 ERROR 说明该状态下所有任务都会失败；
   若你已填 `STAGING_TYPE=oss`，它还会额外提醒"实现尚未完成"，避免你以为已经配好了。
6. **课程热词未接线**：`HotwordListBuilder` 与 `course-hotwords` 配置存在，但 `JobRunner` 不构造热词。
   注意换到阿里云后这条**才有使用点**（TokenHub 路径不吃热词）。
7. **子范围不做裁剪**：给了 `analysisRange` 子范围仍会识别**整条音轨**，只是把片段裁剪到范围内返回（省不了成本）。
8. **不排序**：片段按引擎返回顺序输出，未按 `startMs` 重新排序。
9. **`/health` 不做依赖探测**，恒 200；`ffmpegConfigured` 只表示配了路径。
10. **凭证轮换需重启**，且是部署级（不按 job/providerId 细分范围）。
11. **不实现**：回调、取消、分页、说话人标签、置信度、摘要/评价/抬头率/教师授权/报告发布。

### 7.1 构建期的两个已知噪音（都是有意保留的，别去"修"）

**① `Using H2 2.4.240 which is newer than the version Flyway has been verified with. The latest verified version of H2 is 2.3.232.`**

这是 **Spring Boot 4.0.8 与 Flyway 11.x 之间的版本错配**，不是我们的配置问题：

| 项 | 版本 | 来源 |
| --- | --- | --- |
| H2 | `2.4.240` | Spring Boot 4.0.8 的依赖管理 |
| Flyway | `11.14.1` | Spring Boot 4.0.8 的依赖管理 |
| Flyway **已验证**的 H2 上限 | `2.3.232` | Flyway 内置常量 |

**两条常规出路都被堵死了**：

- *升级 Flyway*？查过 **11.18.0 仍然只认 `2.3.232`**（直接从 jar 里读的常量），升级不解决。
- *降级 H2 到 2.3.232*？代价是**把数据完整性 bug 一起带回来**。H2 2.4.240 修的是：
  Issue #4247「Compaction causes missing chunks and **data loss**」、
  Issue #4208「**Lost update** when attempting to atomically increment … SELECT FOR UPDATE」、
  Issue #4021「未能正确报告**违反唯一约束**的名称」。
  为一个警告回退这些修复不划算。

**所以决定是：保留 H2 2.4.240，接受这条警告。** 影响范围有限——H2 只用于本地开发与测试
（生产按 `DB_URL` 指向 MySQL，届时用的是 `flyway-mysql`，不涉及这条版本检查）。另外
`V1__create_transcription_job.sql` 至今跑得正常，`ContractInvariants`（官方
`verify_contract.py` 的 Java 移植）与两套 fixture 校验也在真实 H2 上通过，
所以这属于"未认证组合"，目前没有观察到实际不兼容。

**复查时机**：每升级一次 Spring Boot 或 Flyway，看一眼 Flyway 的已验证上限有没有提到 2.4.x。
提上去了这条警告会自动消失，届时可以考虑去掉这里的说明。

**② 中文日志乱码。**

有两层，别混为一谈：

- **Maven 输出层**：Java 17 在 Windows 上**不按 `file.encoding` 决定 stdout 编码，而是读控制台代码页**
  （本机是 936/GBK）。所以 pom 里给 surefire 显式指定了
  `-Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8`（与已有的
  `project.build.sourceEncoding=UTF-8` 对齐）。**源码一直是 UTF-8 且编译正常**，
  乱码只发生在控制台输出这一层。
- **终端显示层**：如果终端本身是 GBK，UTF-8 输出仍会花。这不是缺陷，是终端问题——
  Windows Terminal / VS Code 终端默认 UTF-8，无需处理；`cmd` 里可以 `chcp 65001`。

> 顺带一个同类坑：`javac` 不带 `-encoding UTF-8` 时在 Windows 上默认用 GBK 读源码，
> 会把中文注释判成非法字符。Maven 侧没问题（`sourceEncoding` 已固定），
> 但手工调 `javac` 排查问题时要记得加。

**③ 弃用 API 已全部消除（2026-09-16）。**

两类，都是 Spring 7 / Jackson 3 的更名，**语义完全不变**：

| 旧 | 新 | 说明 |
| --- | --- | --- |
| `JsonNode.asText()` | `JsonNode.stringValue()` | Jackson 3 弃用；`asText()` 的字节码就是转发给 `stringValue()` |
| `JsonNode.isTextual()` | `JsonNode.isString()` | 同上，是别名 |
| `HttpStatus.PAYLOAD_TOO_LARGE` | `HttpStatus.CONTENT_TOO_LARGE` | **仍是 413**，Spring 7 按 RFC 9110 更名 |
| `HttpStatus.UNPROCESSABLE_ENTITY` | `HttpStatus.UNPROCESSABLE_CONTENT` | **仍是 422**，同上 |

注意最后两行有个容易混淆的点：改的是 **Spring 的枚举名**，
而响应体里的 `code` 仍是我们自己的契约错误码（`PAYLOAD_TOO_LARGE` / `VALIDATION_FAILED`），
不随 Spring 改名而动。变的只有 HTTP 原因短语，主项目按 `code` 判断，不受影响。

> 另外 `unchecked` 警告来自测试里对无类型 JSON 的泛型转换
> （`RestClient.body(Map.class)` 返回原始类型 `Map`）。已在
> `TranscriptionFixtureGenerationTest` 类级标注 `@SuppressWarnings("unchecked")` 并写明理由——
> 这类转换在断言处会立刻以 `ClassCastException` 暴露，不是掩盖问题。
> 顺带修掉一个真实缺陷：该文件原先有 7 处 `@SuppressWarnings` 写在**局部变量声明**上，
> 而 Java 不允许这么做，所以那些标注一直是空操作，警告从未被压住。

**已验证（2026-09-15，真实凭据）**

1. ✅ **阿里云协议全链路已真机跑通**：`AliyunAsrClient` 提交 → 轮询 → 下载结果 JSON → 解析出毫秒分段，
   端到端返回正确文本（`ManualAliyunLiveTest`）。用的是阿里云官方 3.9 秒样例音频。
   权限于"跑通"，**不等于"课堂场景可用"**——见下面第 5 条。
2. ✅ **`channel_id` 必须发数组**（真机踩到的 bug，已修 + 已加回归测试）。
3. ✅ **轮询是 GET**、`X-DashScope-Async` 必需（缺它 403）、`language_hints` 与 `diarization_enabled`
   均被 filetrans 接受并生效。逐项对照见 [`docs/阿里云模型选型.md`](docs/阿里云模型选型.md) §5。

**未验证项（不要当已完成）**

1. **TokenHub 契约路径的真实端到端识别仍未跑通**：本机 `WAND_API_KEY` 为空
   （旧沙盒时代曾真实跑通识别，但那条不是 `/api/v1` 契约路由）。
   *阿里云那条路已经真机验证过了，但阿里云目前还卡在音频暂存（见下）。*
2. **契约路由本身的真实媒体端到端仍未跑通**：`/api/v1/jobs` → 真实下载 → 真实 ffmpeg →
   真实引擎 → 落库，这条完整链路**两侧都还没走通过一次**
   （阿里云侧差暂存，TokenHub 侧差有效 Key）。
3. 非 WAV 容器（MP4 等）在契约路径下的**解码范围**未实测（判定规则见交接表 §3）。
4. **2 小时上限是否真的可用**未实测；风险点：2 小时 16 k 单声道 WAV ≈ 230 MB，base64 后 ≈ 307 MB，
   而本模块**没有内联体积守卫**，TokenHub `data` 通道的实际上限未知。
5. **真实课堂音频的识别质量（字准率、远场/噪声、方言）完全未验证**。
   阿里云真机测试用的是一句话的样例音频，**不足以说明任何质量结论**，
   也不能据此认为 `qwen-audio-3.0-asr-flash-filetrans` 比 TokenHub 更准。
6. **与主项目的时间容差**未确定（需 M4 实测后共同确认）。
7. 幂等竞态、重启/崩溃恢复、凭证过期续期、长稳运行均未做服务级验证；响应时间/吞吐基线未测。
8. 生产部署形态（HTTPS 终止、反代、容器、证书、日志采集）未定，仓库不含部署配置。

---

## 8. 本机环境注意（实测结论，复现时先看这里）

1. **Maven 本地仓库在沙盒外**：默认 `D:\develop\apache-maven-3.9.4\mvn_repo` 不可写，
   `.mvn/settings-sandbox.xml` 把本地仓库指向 `.data/m2repo`。
   **必须带 `-s .mvn/settings-sandbox.xml`**（或用 `scripts/Mvn.ps1` / `scripts/Start.ps1`）。
   ⚠️ 不要把 `-Dmaven.repo.local=D:/...` 写进命令行：PowerShell 会在冒号处拆参，Maven 会报
   `No plugin found for prefix '.repo.local=D'`。
2. **本机 `curl.exe` 与 `Invoke-WebRequest` 的 HTTPS 是坏的**（`schannel: SECURE_NO_CREDENTIALS`，连百度都失败），
   **但 JVM 不受影响**（JDK 17 能正常访问外部 HTTPS）。因此：本地 `http://127.0.0.1` 的 curl 调用没问题；
   需要访问 HTTPS 时请用 JVM 侧（本模块自己下载媒体、自己调引擎），不要靠 curl 验证外网。
3. **ffmpeg 便携版在 `.work/tools/ffmpeg/bin/`**（`ffmpeg.exe` / `ffprobe.exe`），与 `application.yml` 默认路径一致；
   换机器时用 `FFMPEG_PATH` / `FFPROBE_PATH` 指向新位置。
4. **ffmpeg 本身读不了 HTTPS**：所以「让 ffmpeg 直接吃一个 https URL」这种用法在本机不成立——
   本模块的实现正好避开它：**先用 JVM 受控下载到本地（并算 SHA-256），再让 ffmpeg 处理本地文件**。
5. **增量编译的坑**：`target/` 陈旧时 `mvn test` 可能报与源码不符的编译错误（例如构造器不匹配），
   先 `mvn clean test`。
6. 用 `spring-boot:run` 启动时已在 pom 里固定 `-Dfile.encoding=UTF-8`，避免 Windows 控制台把中文日志打成乱码；直接用 `java -jar` 运行时需自己加该参数。
7. 本机 MySQL 占用 3306（主工程开发库配的是 13306）；本模块默认用 H2 文件库，不冲突。

---

## 9. 相关文档

| 文档 | 用途 |
| --- | --- |
| [`docs/交接表-transcription-v0.1.md`](docs/交接表-transcription-v0.1.md) | **契约 §7 交接表**：全部必填数值、时间映射规则、并发/超时、凭证注入、能力边界、未确定项 |
| [`docs/TokenHub接口探测记录.md`](docs/TokenHub接口探测记录.md) | 识别引擎的实测结论与限制（异步只给 SRT、错误码、未知上限等） |
| [`docs/阿里云模型选型.md`](docs/阿里云模型选型.md) | 阿里云模型选型理由、协议形状、六个坑、**文档值与实测值对照表**（已全部实测） |
| [`docs/OSS暂存接入方案.md`](docs/OSS暂存接入方案.md) | **阿里云前置条件**：为什么必须暂存、bucket/RAM 开通步骤、安全要求、待实现清单 |
| `docs/首次开通清单.md` | 开通指引（腾讯云标准 ASR + 阿里云百炼） |
| `scripts/` | `Use-Env.ps1`（载入 .env）、`Mvn.ps1`、`Start.ps1` |
| 契约与 fixture（主项目文档包） | `classroom-analysis-contract-v0.1.md`、`transcription-module-v0.1.md`；官方校验器与基线在 `.work/fixture-verify/` |

**本模块不负责**：课程摘要、教学评价、抬头率统计、教师授权、报告发布（契约 §1 与转写专用文档 §1）。
