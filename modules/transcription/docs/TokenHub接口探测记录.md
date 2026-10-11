# TokenHub / wand-asr-v1 接口探测记录

> 本文只记录**实测**到的行为，不是抄文档。
> 探测时间：2026-09-13。**全链路已跑通**（真实提交 → 轮询 → 取回带时间戳文字）。
>
> **结论：核心目标"带时间戳的课程文字"已达成。**
> 但走的是 **SRT** 而不是 JSON 字段——见 §4.1，这一条曾经差点被我写错。

## 0. 服务基本情况

| 项目 | 值 |
|---|---|
| 基地址 | `https://tokenhub.tencentmaas.com` |
| 模型 | `wand-asr-v1`（官方文档：https://cloud.tencent.com/document/product/1823/130079） |
| 鉴权 | `Authorization: Bearer sk-...`（**单个 API Key**，不是 SecretId/SecretKey 签名） |
| 风格 | **OpenAI 兼容风格**（`object: "asr.task"`、`error` 对象、`id`/`request_id`） |
| 控制台 | https://console.cloud.tencent.com/tokenhub/inference |

### 三个接口

| 用途 | 方法与路径 |
|---|---|
| 同步转写 | `POST /v1/wand/asrproxy/sync_transcribe` |
| 异步提交 | `POST /v1/wand/asrproxy/async_create` |
| 异步查询 | `GET  /v1/wand/asrproxy/tasks/{task_id}` |

请求体：`{"model":"wand-asr-v1","input_url":"https://.../xxx.mp4"}`

## 1. 实测：鉴权是有效的

| 请求 | 结果 | 结论 |
|---|---|---|
| 不带 `Authorization` | **401** `401002` "API Key 不存在或签名校验失败" | 鉴权确实是 Bearer |
| 带正确 Key | **402** `401007`（额度问题，不是鉴权问题） | **Key 有效**，缺的是额度 |

## 2. 🔴 当前阻塞：没有额度、也没开过后付费

```
HTTP 402
{
  "error": {
    "message": "No free trial quota is available for the service and postpaid billing is not enabled,
                so the service cannot be accessed. Please go to Console > Online Inference Service
                to enable postpaid billing.",
    "message_zh": "服务无可用免费体验额度，且未开启后付费，无法正常访问。
                   请前往控制台 > 在线推理服务开启后付费",
    "code": "401007",
    "type": "gateway_error",
    "request_id": "4661c33e-..."
  }
}
```

**要做的事**：去 https://console.cloud.tencent.com/tokenhub/inference 开启**后付费**（或领取免费额度）。
在开通之前，`sync_transcribe` 与 `async_create` 都会返回 402，与代码无关。

## 3. 错误报文格式（两种形状，都要兼容）

### 3.1 网关级错误（鉴权 / 额度）→ `application/json`

```json
{
  "error": {
    "message": "英文说明",
    "message_zh": "中文说明",
    "code": "401002",
    "type": "gateway_error",
    "request_id": "5cff29f4-..."
  }
}
```

### 3.2 任务级错误（task not found）→ ⚠️ `text/plain; charset=utf-8` 但内容是 JSON

```json
{
  "request_id": "9454f717-...",
  "error": {
    "code": "400004",
    "message": "task not found",
    "source": "client",
    "request_id": "9454f717-..."
  }
}
```

**两个坑**：
1. `request_id` 出现在**两个层级**（顶层和 error 内），排查时两个都要打日志；
2. 错误响应的 `Content-Type` 不统一（`application/json` 与 `text/plain` 混用），
   **解析时必须按内容而不是按 Content-Type 判断**，否则会漏掉错误信息。

## 4. 已收集的错误码

| code | HTTP | 含义 | 处理 |
|---|---|---|---|
| `401002` | 401 | API Key 不存在或签名校验失败 | Key 填错 / 被吊销 → **不可重试** |
| `401006` | 400 | service ID 不存在，或 model 与 service 不匹配 | 模型名写错 → **不可重试** |
| `401007` | 402 | 无免费额度且未开后付费 | 去控制台开通 → **不可重试**（开通后需人工重发） |
| `400001` | 400 | 缺必填字段（`one of input_url or data is required`） | 参数问题 → **不可重试** |
| `400002` | 400 | 参数取值非法（如 `input_url` 不是合法 URL） | 参数问题 → **不可重试** |
| `400004` | 400/404 | task not found | 任务 ID 不存在或结果已过期 → **不可重试** |
| `502001` | 502 | `CodeUpstreamError`：上游 ASR 引擎返回业务错误 | 随附 `upstream_code` / `upstream_status`，**可重试** |

`502001` 实例（下载不到音频时）：

```json
{"created_at":1789299622,
 "error":{"code":"502001","type":"CodeUpstreamError","message":"Download file failed",
          "message_zh":"上游 ASR 引擎返回业务错误","source":"upstream",
          "upstream_code":"-30","upstream_status":"200"},
 "id":"...","object":"asr.task","status":"failed","request_id":"..."}
```

> ⚠️ 注意 `upstream_status: "200"` 是**字符串**，而且它 200 却失败了——
> 它指的是"上游 HTTP 调用成功但业务失败"。不要用 `upstream_status` 判断成败。

## 4.1 ✅ 成功报文（最关键的发现）

实测方式：合成 1 秒 440Hz 正弦 WAV，走 `data`（base64）通道提交。
**HTTP 200**：

```json
{
  "created_at": 1789299643,
  "id": "th_1486968752_00_asrproxy_bd1312a94bca812d62576863c1afb380",
  "object": "asr.task",
  "request_id": "690985da-e226-477a-8ff4-85596c81c2dc",
  "status": "completed",
  "output": {
    "duration_ms": 1000,
    "text": "",
    "source": "zh",
    "sentences": [],
    "subtitle_url": "https://subtitle-task-proxy-tmp-1258344699.cos.ap-guangzhou.myqcloud.com/audio_transcript_tmp/temp/690985da_....srt"
  },
  "usage": { "total_tokens": 50 }
}
```

### 这解决了什么

1. **`sentences` 是结构化数组**，不是只有纯文本。本次是 `[]` 因为纯正弦波里没有语音内容
   （引擎判定"无语音"，所以 `text` 也是空串）。有真实语音时会填充。
   字段名与 `duration_ms` 的单位（毫秒）风格一致，预计为 `begin_time`/`end_time`/`text`
   一类命名——**待用真实中文语音确认一次即可钉死**。
2. **`subtitle_url` 是 SRT 字幕文件**（预签名 COS 链接）。SRT 天生带时间戳，
   可作为**时间戳兜底通道**：万一 `sentences` 结构不理想，还能解析 SRT。
3. `status` 成功值是 **`completed`**（不是 `SUCCEEDED`），失败是 `failed`。
4. `usage.total_tokens` 用作计量 —— 计费口径是 **token 而非音频时长**，与标准 ASR 不同。

## 4.2 ✅ 全链路已跑通（异步通道的真实结果）

用公开中文语音样本（`hello_world_female2.wav`）走**异步通道**跑通，日志留痕：

```
音源探测: ...hello_world_female2.wav 大小=125.5 KB 类型=audio/wav
提交 TokenHub ASR 任务: model=wand-asr-v1, url=...
TokenHub 任务已创建: taskId=th_1486968752_00_asrproxy_240725506d81b48892f3f1dc84c7815f
任务 th_1486... 从 SRT 解析出 2 条带时间戳的分段（SRT 93 字节）
```

最终由业务接口返回（`GET /api/videos/9002/transcription/transcript`）：

```json
{
  "status": "SUCCESS",
  "duration": 3.362,
  "segmentCount": 2,
  "fullText": "Hello world,这里是阿里巴巴语音实验室。",
  "segments": [
    { "index": 0, "startMs": 462,  "endMs": 1187, "timeLabel": "00:00:00", "text": "Hello world," },
    { "index": 1, "startMs": 1187, "endMs": 3362, "timeLabel": "00:00:01", "text": "这里是阿里巴巴语音实验室。" }
  ]
}
```

时间戳与 SRT 原文逐字节一致（`00:00:00,462` → `startMs: 462`），
说明 **SRT 解析路径正确**。

## 4.3 ⚠️ 差点写错的地方：异步与同步的 output 不一样

| 通道 | 成功的 `output` 内容 | 时间戳来源 |
|---|---|---|
| **同步** `sync_transcribe` | `duration_ms` + `text` + `source` + **`sentences[]`** + `subtitle_url` | `sentences` 字段 |
| **异步** `async_create` + `tasks/{id}` | **只有 `subtitle_url`**，没有 `sentences`/`text`/`duration_ms` | **必须解析 SRT** |

异步完成的真实返回（注意 `output` 里只有一个字段）：

```json
{"request_id":"83fba96e-...","id":"th_1486968752_00_asrproxy_a1a18ff13fcc49c636435c0afdcc5d69",
 "object":"asr.task","created_at":1789300437,"status":"completed",
 "output":{"subtitle_url":"https://subtitle-task-proxy-tmp-1258344699.cos.ap-guangzhou.myqcloud.com/.../hello_world_female2_3087492328.srt"},
 "usage":{"total_tokens":150}}
```

**如果照同步接口的假设去写解析，异步通道的转写结果会是空的。**
本模块走异步（避免阻塞 HTTP 线程），所以实现了 SRT 解析
（`SrtParser`），并优先兼容内联 `sentences`。

下载回来的 SRT 原文（93 字节）：

```
1
00:00:00,462 --> 00:00:01,187
Hello world,

2
00:00:01,187 --> 00:00:03,362
这里是阿里巴巴语音实验室。
```

## 4.4 ✅ 真实 MP4 实测通过（回答"能不能直传 MP4"）

用真实带语音的 MP4（`https://media.w3.org/2010/05/sintel/trailer.mp4`，4MB）实测：

```
POST async_create  -> HTTP 200
{"request_id":"5b7270aa-...","id":"th_1486968752_00_asrproxy_f0c64cc20271254aa94c61058f2c8266",
 "created_at":1789303757,"status":"queued"}

轮询: queued → in_progress ×3 → completed（约 20 秒）
```

**结论：TokenHub 支持 MP4，会自己抽音轨，不需要先转 MP3。**

下载 SRT 得到的识别结果（Sintel 预告片真实对白，3 段、跨度 43 秒）：

```
1  00:00:11,965 --> 00:00:14,515   What brings you to the land of the gatekeepers?
2  00:00:18,315 --> 00:00:19,815   I'm searching for someone.
3  00:00:36,653 --> 00:00:43,804   A dangerous quest for a lo hunter I've been alone for as long as I can remember.
```

经完整应用链路的返回（`GET /api/videos/9200/transcription/transcript`）：

```json
{ "status":"SUCCESS", "duration":43.804, "segmentCount":3,
  "segments":[
    {"index":0,"startMs":11965,"endMs":14515,"timeLabel":"00:00:11","text":"What brings you to the land of the gatekeepers?"},
    {"index":1,"startMs":18315,"endMs":19815,"timeLabel":"00:00:18","text":"I'm searching for someone."},
    {"index":2,"startMs":36653,"endMs":43804,"timeLabel":"00:00:36","text":"A dangerous quest for a lo hunter I've been alone for as long as I can remember."}
  ]}
```

### ⚠️ 本次实测暴露的代码缺陷（已修）

`AudioSourceProbe` 原先用**全局 1GB 配置**拒绝超限音源，但那是
**腾讯云标准 ASR** 的限制。用 TokenHub 提交 1.2GB 课堂录像时会被本地**错误拒绝**，
理由与提供方能力不符。

已改为**由提供方声明上限**（`AsrProviderClient.maxInputUrlBytes()`）：

| 提供方 | 声明上限 | 依据 |
|---|---|---|
| 腾讯云标准 ASR | **1GB** | 官方原文「音频 URL 时长不能大于 5 小时，文件大小不超过 1GB」 |
| TokenHub | **无上限**（`Long.MAX_VALUE`） | 文档未给；4MB MP4 实测通过 |

新增 7 个测试（`AsrSourceSizeGuardTest`）钉住这条行为：用一个本地 HTTP 服务器
返回 `Content-Length = 2GB`，URL 用 `localtest.me`（公网 DNS、解析到 127.0.0.1）
以通过公网校验，从而**直接调真实的 `AsrService.submit()`** 验证拦截与否。

### 仍未确定

- TokenHub 的**实际大小上限**：只验证了 4MB。要确认得上传一个接近上限的大文件，
  成本较高，暂列为未知。按 2GB 规划比较稳妥（与阿里云同类产品一致），
  但**这是推测，不是实测**。
- 时长上限同样未知。

## 5. 与腾讯云标准 ASR 的对比（选型参考）

| 维度 | 腾讯云 ASR（CreateRecTask） | **TokenHub wand-asr-v1** |
|---|---|---|
| 鉴权 | SecretId+SecretKey，**需实现 TC3 签名** | **一个 Bearer Key**，简单得多 |
| 同步接口 | ❌ 只有异步 | ✅ **`sync_transcribe` 直接返回结果** |
| 请求体 | 十几个参数（EngineModelType/ChannelNum/ResTextFormat…） | **只有 `model` + `input_url`（或 `data`）** |
| 异步成功后的文本 | `ResultDetail` 内联 | **只有 SRT 链接，需下载解析** |
| 任务 ID | 数字（`9266418`） | **字符串（`th_1486...`）** |
| **MP4 直传** | ✅ 支持，但 **URL 方式限 1GB / 5 小时**，45 分钟 1080p 课堂录像常超限 | ✅ **支持，实测 4MB 通过，无已知上限** |
| 大小上限 | **1GB**（官方明确） | **未知**（文档未给） |
| 附加输入方式 | 仅 URL | URL **或 base64 `data`** |
| 计费口径 | 音频时长 | `usage.total_tokens` |
| 热词 / 说话人分离 | 支持 | 请求体里看不到入口，疑不支持 |
| 签名/时间依赖 | 有（签名 v3 对时间敏感） | 无 |
| 公共文档 | 完整 | 有（product/1823/130079），但字段细节需实测 |

**结论**：如果诉求是"**直接喂 MP4、不想装 FFmpeg**"，**TokenHub 明显更合适**——
腾讯云标准 ASR 的 1GB 上限会让 45 分钟 1080p 课堂录像被拒。
反过来，如果需要**热词、说话人分离**或字段级文档保障，标准 ASR 更稳。

## 6. 状态取值（实测确认）

| 状态 | 含义 |
|---|---|
| `queued` | 已入队（提交后的初始状态） |
| `in_progress` | 识别中 |
| `completed` | 成功（结果在 `output.subtitle_url`） |
| `failed` | 失败（原因在 `error`） |

代码的 `mapStatus()` 对未知取值一律当作"仍在处理"，由 15 秒轮询与
`ASR_POLL_TIMEOUT`（默认 2 小时）兜底——不猜枚举值，避免提供方新增状态时把有效任务判死。

## 7. 仍未确认的事项

1. **TokenHub 的实际大小/时长上限**（只验证了 4MB；按 2GB 规划是推测）。
2. 是否支持**热词**、**说话人分离**、**词级时间戳**
   （请求体只接受 `model` + `input_url`/`data`，看起来没有入口）。
3. `data`（base64）通道的大小上限。

## 8. 安全提醒

⚠️ 这把 API Key 曾在聊天中明文出现。**验证完成后请立即到控制台轮换**：
https://console.cloud.tencent.com/tokenhub/apikey
