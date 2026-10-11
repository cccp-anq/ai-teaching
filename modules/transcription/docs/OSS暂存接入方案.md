# OSS 暂存接入方案（阿里云 filetrans 前置条件）

> **一句话**：阿里云的非实时语音识别长音频档（`qwen-audio-3.0-asr-flash-filetrans`）
> **只接受公网可访问的 URL**，不接受 Base64、不接受二进制流、不接受本地文件。
> 所以抽好的音轨必须先传到我们自己控制的 OSS，再把预签名 URL 交给它。
>
> **当前进度**：配置项已全部占位并可绑定（`StagingProperties` + `application.yml` + `.env.example`），
> **上传实现尚未编写**。填了 OSS 环境变量现在也不生效——启动日志会明说这一点。
>
> 状态：`STAGING_TYPE=inline` 时行为与之前完全一致（对任何暂存请求明确失败，不静默降级）。

---

## 1. 为什么绕不开这一步

官方 FAQ 原文（[Paraformer 非实时 Python SDK](https://www.alibabacloud.com/help/zh/model-studio/paraformer-recorded-speech-recognition-python-sdk)）：

> **Q：是否支持Base64编码方式的音频？**
> 不支持Base64编码方式的音频。仅支持可通过公网访问的 URL 所指向的音频的识别，
> **不支持识别二进制流，也不支持直接识别本地文件**。

而且"换个支持 Base64 的模型"这条路是堵死的——阿里云这边
"能内联提交音频"和"能装下 45 分钟课"**互斥**：

| 档位 | 时长上限 | 输入方式 |
| --- | --- | --- |
| 长音频档（`-filetrans` 系列） | 12 小时 / 2GB | **仅公网 URL** |
| 短音频档（`-flash` 系列） | 5 分钟（`qwen3-asr-flash` 仅 10MB） | URL 或 Base64 |

所以对课堂录像来说，暂存不是可选项。

## 2. 为什么不直接把原始 MP4 传上去

看起来更省事（不用抽音轨、不用转码），但**算一下体积就知道不行**：

| 源码率 | 45 分钟 MP4 | 是否撞阿里云 2GB 上限 |
| --- | --- | --- |
| 3 Mbps | 1.0 GB | 勉强 |
| 4 Mbps | 1.35 GB | 勉强 |
| **8 Mbps** | **2.7 GB** | ❌ 超 |
| **15 Mbps**（手机/录播常见） | **5.0 GB** | ❌ 严重超 |

而抽音轨之后（16k 单声道）：

| 格式 | 比特率 | 45 分钟体积 |
| --- | --- | --- |
| WAV（当前默认） | 256 kbps | 86 MB |
| **MP3** | 32 kbps | **10.8 MB** |

**结论：抽音轨是必需的，不是优化。** 上传量差 120 倍以上，而且原始 MP4 会直接撞 2GB 上限。

> 顺带说明了为什么"先用 FFmpeg 转 MP3"这个想法在 OSS 方案下是对的：
> 它把上传量从 86MB 降到 11MB，且 MP3 的帧结构比 WAV（一个头 + 一坨裸 PCM）
> 更抗截断——上传 86MB 比上传 11MB 更容易中途出问题。

## 3. 数据流

```
主项目给的受控 sourceUrl
  │
  ├─ 1. 受控下载 + 流式 sha256 核验        （已有，MediaDownloader）
  ├─ 2. ffprobe 探测 + ffmpeg 抽整条音轨   （已有，FfmpegMediaTool）
  ├─ 3. 上传 OSS（私有 bucket）            ← 待实现
  ├─ 4. 生成预签名 URL（HTTPS，有效期 3h）  ← 待实现
  ├─ 5. 提交给百炼                          （已有，AliyunAsrClient）
  ├─ 6. 轮询到终态                          （已有，JobRunner.pollUntilTerminal）
  └─ 7. 删除 OSS 对象                       ← 待实现（暂存句柄的 close()）
```

第 3–4 步与第 7 步都落在 `AudioStaging` 接口里，`JobRunner` **一行都不用改**——
它已经按 `AsrProviderClient.acceptsInlineAudio()` 分成了两条路，
暂存句柄的生命周期也已经用 try/finally 管好了（轮询结束后才 close）。

## 4. 已有接口与待实现

| 关注点 | 文件 | 状态 |
| --- | --- | --- |
| 暂存抽象（`stage` / `urlStagingAvailable` / `describe`） | `asr/staging/AudioStaging.java` | ✅ 已定义 |
| 明确失败的实现 | `asr/staging/InlineAudioStaging.java` | ✅ 已完成 |
| **OSS 配置项** | `asr/staging/StagingProperties.java` | ✅ **已占位**（归一化 + 端点推导 + 缺项提示 + 已测 10 例） |
| **OSS 上传实现** | `asr/staging/OssAudioStaging.java` | ❌ **待实现** |
| 装配分支 | `asr/config/AsrConfiguration.java#audioStaging()` | ⏳ 待接 `type` 分支 |
| 启动自检 | `asr/service/AsrStartupCheck.java` | ✅ 已能识别"选了 oss 但实现未完成" |
| 提交路径分支 | `job/service/JobRunner.java#submitToProvider` | ✅ 已完成，无需改动 |

### 待实现的 `OssAudioStaging` 要点

1. **上传**：`PutObject` 直传。45 分钟音轨压成 MP3 约 11MB，不需要分片；
   分片阈值（默认 100MB）是为"直传原始录像"这类实验路径保留的。
2. **预签名 URL**：必须用**公网端点**（`resolvedPublicEndpoint()`）签名，
   即使上传走的是内网端点。签错端点会让引擎拉不到音频，
   而错误信息只是一句含糊的下载失败。
3. **有效期**：默认 3 小时 = 轮询上限 2 小时 × 1.5。必须覆盖"排队 + 识别 + 轮询"全过程——
   地址在任务中途失效，任务会以一个含糊的下载失败告终。
4. **删除**：`close()` 必须可重复调用且不抛异常（它在 finally 里执行，
   抛异常会掩盖真正的失败原因）。
5. **对象键**：`prefix + 随机名 + 扩展名`。不要用 `jobId` 之外的业务标识，
   也不要用原始文件名（可能含学生姓名等隐私信息）。
6. **日志纪律**：只记 bucket 名与对象键，**不记完整签名 URL**（带签名查询串），
   **永不记 AccessKey**。

## 5. 安全要求（重要）

**① bucket 保持私有。** 不要设成公共读。预签名 URL 自带鉴权，
百炼服务器能下载、其他人不能。公共读意味着任何拿到 URL 的人都能下载课堂录音——
**未脱敏的学生声音，这是真实的隐私问题**。

**② 用 RAM 子账号，不要用主账号 AccessKey。** AccessKey 的权限远大于一枚 API Key
（主账号密钥可读写整个账号下的所有 bucket）。建议的最小权限策略：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["oss:PutObject", "oss:GetObject", "oss:DeleteObject"],
      "Resource": ["acs:oss:*:*:你的bucket/videoreact/staging/*"]
    }
  ]
}
```

注意 `Resource` 末尾的 `/*`——限定到前缀，这样即使密钥泄露也只能动暂存目录。

**③ 配生命周期规则兜底。** 即使模块崩溃没删掉对象，也应在一段时间后自动清理：

| 配置项 | 建议值 |
| --- | --- |
| 适用范围 | 前缀 `videoreact/staging/` |
| 过期删除 | 1 天 |

这是"失败时不会积累垃圾"的保险，不能替代 `close()` 里的主动清理。

**④ 地域要一致。** OSS bucket 的地域应与百炼调用地域一致（华北2 = `cn-beijing`）。
跨地域拉取更慢，且可能产生流量费。

## 6. 开通步骤

### 6.1 在控制台做

1. **确认地域**：本方案按**华北2（北京）**写。与百炼调用地域保持一致。
2. **建 bucket**：<https://oss.console.aliyun.com/>
   - 读写权限选 **私有**
   - 记下 **bucket 名**（只记桶名本身，不要带 `.oss-cn-beijing.aliyuncs.com`）
3. **建 RAM 子账号**：<https://ram.console.aliyun.com/users>
   - 勾选「编程访问」，会生成 AccessKeyId / AccessKeySecret
   - **立刻保存**（Secret 只在创建时显示一次）
4. **授权**：给这个子账号一条自定义策略，内容用上面 §5 ② 的 JSON
   （把 `你的bucket` 换成实际桶名）
5. **配生命周期规则**：bucket → 数据管理 → 生命周期 → 按 §5 ③ 配置

### 6.2 填进 `.env`

```ini
STAGING_TYPE=oss

OSS_ACCESS_KEY_ID=<RAM 子账号的 AccessKeyId>
OSS_ACCESS_KEY_SECRET=<RAM 子账号的 AccessKeySecret>
OSS_BUCKET=<桶名，只填桶名>
OSS_REGION=cn-beijing
OSS_PREFIX=videoreact/staging/

# 可选：模块部署在 ECS 上时填内网端点省流量
# OSS_INTERNAL_ENDPOINT=https://oss-cn-beijing-internal.aliyuncs.com
```

其余项（`OSS_URL_EXPIRY_SECONDS` 等）有合理默认值，一般不用动。

> **填了也不会立刻生效**——上传实现还没写。启动日志会明确告诉你这一点：
> ```
> WARN 暂存配置待办: OSS 暂存的配置项已就位（oss（bucket=..., region=cn-beijing, ...）），
>      但上传实现尚未完成，当前仍会以「暂存不可用」失败。见 docs/OSS暂存接入方案.md
> ```

### 6.3 验证是否配对了（实现完成后）

```powershell
# 本地先跑一遍全量测试
mvn -B -s .mvn/settings-sandbox.xml clean verify

# 然后启动，看自检那一行
. .\scripts\Use-Env.ps1
.\scripts\Start.ps1
```

启动日志里这一行会显示实际的 bucket / region / 端点 / URL 有效期：

```
ASR 提供方: ALIYUN_FILE_TRANS / 模型 qwen-audio-3.0-asr-flash-filetrans
  （内联音频: 不支持，URL 暂存: oss（bucket=xxx, region=cn-beijing, ...），轮询上限 PT2H）
```

## 7. 已识别的风险

| 风险 | 说明 | 应对 |
| --- | --- | --- |
| **OSS SDK 的 Jackson 2 冲突** | `aliyun-sdk-oss` 依赖 Jackson 2，而本项目是 Spring Boot 4 + **Jackson 3**（`tools.jackson`）。共存未验证 | 实现第一步就验，不等到最后 |
| **百炼能否读取预签名 URL** | 预签名 URL 带查询串。若百炼的下载器剥离 query 变成匿名请求，会 403 | 首次联调用真实文件验证；不通过就退化为"公共读 + 随机不可猜对象名"（仍不理想，需重新评估） |
| **跨账号 bucket** | 若 bucket 属于另一个阿里云账号，预签名 URL 依然可用（签名基于 AK，不依赖账号关系） | 低风险 |
| **上传失败的重试语义** | 上传超时/5xx 可重试；桶不存在/凭证无效不可重试 | 已由 `AudioStagingException.retryable` 承载 |

## 8. 一个尚未验证的优化（先不做）

如果主项目的课堂录像**本来就存在同一个阿里云 OSS 上**，理论上可以省掉"下载 → 上传"的往返：

```
主项目给 OSS 路径/签名 URL
  → 我们仍流式下载核验 sha256（不落盘）
  → 直接给百炼一个我们自己签的预签名 URL
```

但这依赖 §7 第二条那个未验证的假设（百炼能否正确使用带查询串的 URL）。
**不在设计里假设它成立**，等主项目确认媒体存储现状后再评估。
