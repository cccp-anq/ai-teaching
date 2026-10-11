# 主项目接入指南 · 抬头率模块 v0.1（适配器规格 + 联调验收单）

日期：2026-09-14。读者：**ai_teaching 主项目**（Java 17 / Spring Boot / MySQL / Vue 3）与**videoreact**
（转写模块）的维护者。契约数值见 [`交接表-head-up-v0.1.md`](交接表-head-up-v0.1.md)，
口径见 [`定义说明-head-up.md`](定义说明-head-up.md)。

> 本文只解决一件事：**主项目怎么把我们接进去、怎么验、什么时候算接完**。
> 契约本身（字段表、错误码、幂等语义）以主项目文档包的
> `integration/classroom-analysis-contract-v0.1.md` 与 `integration/head-up-module-v0.1.md` 为准。

---

## 1. 两条接入路径（可分别验收）

| 路径 | 是否需要模块在线 | 主项目侧动作 | 里程碑 |
| --- | --- | --- | --- |
| **A. JSON 导入**（推荐先做） | 不需要 | 导入我们发布的 `head-up-success.json` / `head-up-ratio-success.json` / `head-up-failed.json` 形状，走 M2 的标准接收、幂等/版本、统计与曲线 | M2 |
| **B. HTTP 契约** | 需要 | 适配器提交 `POST /api/v1/jobs` + 轮询 `GET /api/v1/jobs/{jobId}`，把 `JobEnvelope` 落成 `module_job` + `module_result` | M4 |

两条路径的 payload 形状完全相同，**路径 A 通过后再做 B**，可以避免"接口和统计一起改"的返工。

我们的失败样例目前只有模块内的失败路径（`PAYLOAD_TOO_LARGE`、`FFMPEG_MISSING` 等由任务产生）；
契约文档包里的 `head-up-failed.json`（`PROCESSING_FAILED`，retryable=true）形状与我们的
`error` 结构一致，可直接用于主项目失败态展示的联调。

---

## 2. 主项目需要登记的值（照着 `/api/v1/health` 抄，不要抄文档）

```http
GET {baseUrl}/api/v1/health        # 本模块该路由不需要 Bearer
```

```json
{
  "status": "UP",
  "providerId": "head-up-local",
  "moduleVersion": "head-up-0.1.0",
  "apiVersion": "1.0",
  "moduleType": "HEAD_UP",
  "definitionId": "head-up-qwen-vl-v1",
  "sampleMode": "REPRESENTATIVE_FRAME",
  "outputModes": ["COUNTS", "RATIO_ONLY"],
  "limits": { "maxBodyBytes": 2097152, "maxMediaBytes": 2147483648,
              "maxDurationMs": 7200000, "maxWindows": 20000, "workers": 1 },
  "auth": { "bearerRequired": true, "tokensConfigured": true, "healthRequiresBearer": false },
  "vision": { "provider": "qwen-vl", "model": "qwen3-vl-plus", "credentialConfigured": true },
  "media": { "ffmpegConfigured": true, "hostAllowListConfigured": true, "allowLoopbackHttp": false },
  "fixtureProvider": { "enabled": false, "providerId": "head-up-demo" }
}
```

登记到主项目侧的字段（对应 `module_job.provider_id` / `module_result.module_version`）：

| 主项目字段 | 取值来源 | 注意 |
| --- | --- | --- |
| `providerId` | `health.providerId` | 合成样例用的是 `head-up-demo`，与真实部署身份**不同**，不要混用 |
| `moduleVersion` | `health.moduleVersion` | 换模型/换抽帧宽度/换口径都会变，主项目应把旧结果按版本保留 |
| `definitionId` | `health.definitionId` 或 `payload.definitionId` | 必须与 `payload.denominatorDefinition` 一起展示，不能只显示比例 |
| `bearer` | 部署方注入 | 不放 Git、不进前端、不进日志 |

**特别注意 `fixtureProvider.enabled`**：为 `true` 时该部署会对演示身份输出**人工合成观测**。
主项目页面必须能区分（`providerId=head-up-demo` 即合成），不要把这类结果显示成真实课堂数据。

---

## 3. 提交与轮询（适配器伪代码）

```java
// 1) 建 job：clientRequestId 由主项目生成，唯一确定本次识别
String clientRequestId = "req-head-up-" + UUID.randomUUID();
JobRequest body = JobRequest.of(media, analysisRange, windowMs, outputMode, definitionId);

// 2) 提交（202）或复用（200）；网络超时后用**同一个 clientRequestId** 重提交，绝不新建键
HttpResponse<String> res = post(baseUrl + "/api/v1/jobs", bearer, clientRequestId, body);

// 3) 轮询：2s 起、最多 10s 并加抖动（契约 §2 工程初值，不是 SLA）
while (!terminal(env.status())) { sleepWithJitter(); env = get(baseUrl + "/api/v1/jobs/" + env.jobId()); }
```

适配器必须处理的 5 个分支：

| 分支 | 判据 | 主项目动作 |
| --- | --- | --- |
| 首次受理 | HTTP 202 | 存 `jobId`，进入轮询 |
| 幂等复用 | HTTP 200 | 复用已有 `jobId`，**不要**再建一条 `module_job` |
| 同键异内容 | HTTP 409 `IDEMPOTENCY_CONFLICT` | 说明主项目复用了不该复用的键；换新 `clientRequestId` 重新提交 |
| 请求非法 | HTTP 400/403/404/413/422 | 落主项目侧失败并展示 `code`/`message`；**不要重试** |
| 处理失败 | HTTP 200 + `status=FAILED` | 读 `error.code` / `error.retryable`，按 §5 处理 |

超时、连接错误、轮询超时都记在**主项目自己的** integration job 上，**不要伪造模块结果**
（契约 §5：GET 失败 ≠ 远端任务 FAILED）。

---

## 4. 结果映射（`payload` → 主项目表）

### 4.1 明细

| 主项目表（M2 设计） | 来源 | 约束 |
| --- | --- | --- |
| `module_result` | 整个 `JobEnvelope` | `raw_json` 原样存；`(providerId, jobId, revision)` 唯一；终态不可变 |
| `observation_window` | `payload.windows[]` | `id` 用 `win-001…`；`(result_id, local_id)` 唯一 |
| `observation_window.start_ms/end_ms` | `window.startMs/endMs` | 原媒体毫秒轴，左闭右开 |
| `observation_window.sample_at_ms` | `window.sampleAtMs` | 可为 `null`（`FRAME_EXTRACTION_FAILED`） |
| `observation_window.quality` + `quality_reason` | 同名 | 三态枚举 `VALID` / `NO_SUBJECTS` / `LOW_QUALITY` |
| 计数或比例 | `headUpCount` + `validObservedCount`（COUNTS）或 `headUpRatio`（RATIO_ONLY） | **不得跨模式混用**；同一次分析只能一个模式 |
| `processed_ranges` | `payload.processedRanges` | 与 `windows` 的并集必须相等；缺失区间在完整性卡片里显示 |

### 4.2 主项目必须自己算的统计（口径已升版，务必注意）

**v0.2 起模块内部使用 `stats-v2`**：

```
meanRate = SUM(head_up_count) / SUM(evaluable_count)    只含 quality == VALID 的窗口
```

| 版本 | 聚合方式 | 说明 |
| --- | --- | --- |
| `stats-v1`（契约 v0.1 冻结） | 时长加权 `Σ(r_i·d_i)/Σd_i` | 模块仍以 `v1DurationWeightedMeanRate` 字段暴露，供对照 |
| `stats-v2`（v0.2 正式） | 人数比 `SUM(up)/SUM(evaluable)` | `payload` 与 `statistics` 里的 `meanRate` |

两者在**窗口等长时数值相同**（演示 fixture 全是 10s，故 0.4625 不变），窗口不等长时分叉：
真实 50 秒 5 窗实测 v2 = **0.7714**、v1 = 0.6687。**主项目需要决定页面展示哪一个**，
并在 DTO 里明确标注所用口径（`statisticsVersion` 字段可用于分支）。

三条**不要做**的事：

1. 不要把 REVIEW 窗口当 VALID 计入均值（REVIEW = "算得出来但可观测性不足"）；
2. 不要把 `windows` 的人数跨窗求和当"学生总人数"；
3. 不要用 `validObservedCount`/`evaluable` 推应到/实到或出席率。

`windows` 与转写 `segments` 的关联按**区间相交**，不按数组下标；无转写时显示"此时无转写片段"。

### 4.3 页面文案建议（避免口径被误读）

| 位置 | 建议文案 |
| --- | --- |
| 抬头率曲线标题 | 有效窗口平均抬头率（代表帧估计，本次运行） |
| 曲线缺口 | 该时段无可计算观测（遮挡/不可判读/未处理），非 0% |
| 分母提示 | 分母为"可判断抬头状态的可观测人数"，不等于班级人数，也不用于出席率 |
| 完整性卡片 | 处理覆盖 / 有效窗口覆盖 分别显示；注明"代表帧估计，不是连续行为测量" |
| 模型信息 | 建议显示 `payload.definitionId` + 实际模型名（换模型数值会变：plus 0.715 vs flash 0.588） |
| 稳定性说明 | 同一媒体重跑可能有 ±3 个百分点差异；页面数值属于该次运行的不可变结果 |

### 4.4 真实模型实测（2026-09-14，供主项目评估展示方式）

| 项 | 实测 |
| --- | --- |
| 端到端 | 50 秒 5 窗真实画面媒体：202 → `SUCCEEDED`，9.9–14.2 秒 |
| 结果 | 5 窗全部 `VALID`，平均抬头率 **0.715**（重跑 0.687） |
| 成本 | 1140 prompt tokens/帧 ⇒ 45 分钟课（270 帧）≈ **¥0.45**（plus）/ **¥0.11**（flash） |
| 已发现的问题 | ① 模型对"只见后脑"的人会猜成抬头；② 同帧 ±1 人抖动；③ 换模型数值差异明显 |

完整记录：[`fixtures/head-up-module-v0.1/测试记录-真实模型.md`](fixtures/head-up-module-v0.1/测试记录-真实模型.md)。
**在人工标注集建立之前，页面不得出现"准确率"字样**。

---

## 5. 错误处理矩阵

| `error.code` | retryable | 主项目该做什么 |
| --- | --- | --- |
| `MEDIA_URL_EXPIRED` | true | 续期受控下载地址，**用同一个 `clientRequestId`** 重提（指纹不含 URL） |
| `MEDIA_HASH_MISMATCH` | false | 先修媒体登记数据；重试无效 |
| `UNSUPPORTED_MEDIA` | false | 看 `message`：格式不支持 / 无视频流 / 明文 http / 主机不在白名单 / 解析到内网 |
| `DECODE_FAILED` | false | 容器损坏或抽帧失败；换媒体或先转码 |
| `PROCESSING_FAILED` | true | 临时故障；可用新 `clientRequestId` 重试 |
| `PAYLOAD_TOO_LARGE` | false | **补充码**：媒体超上限，需协商上限或缩小媒体 |
| `VISION_PROVIDER_ERROR` | true/false | **补充码**：模型超时/限流（true）或返回不可解析（false 时说明提示词/模型不兼容） |
| `VISION_CREDENTIAL_MISSING` | false | **补充码**：部署缺 `DASHSCOPE_API_KEY`，属运维问题 |
| `FFMPEG_MISSING` | false | **补充码**：部署缺 ffmpeg/ffprobe，属运维问题 |

未识别 `code` 时：显示 `message`（不含堆栈/凭证/媒体地址）并把结果标为失败，**不要**当成成功。

---

## 6. 联调验收单（两边逐条打勾）

### 6.1 路径 A：JSON 导入（主项目 M2）

- [ ] `verify_head_up_fixtures.py` 在样例目录跑通（我方已通过：4 个信封、0 失败）
- [ ] 导入 `head-up-success.json`：曲线 6 点、rate `0.75/0.60/0/null/null/0.50`、均值 `0.4625`
- [ ] 第 3 窗显示"真实 0%"，第 4 窗显示"无可计算数据"（`NO_SUBJECTS`），第 5 窗显示"遮挡/低质量"
- [ ] 曲线在第 4、5 窗**断开**，不连线、不插值
- [ ] 导入 `head-up-ratio-success.json`：均值同为 `0.4625`，所有人数为 `null`
- [ ] 有效窗口覆盖 `2/3`、处理覆盖 `1` 分别显示
- [ ] 相邻变化只出现 `-15` 与 `-60`（不跨无效窗）
- [ ] 导入同一份样例两次 → 200 复用；导入改过数值的同键样例 → 409 且旧数据不变
- [ ] 无视频流/无语音/全遮挡三类空态能分别展示

### 6.2 路径 B：HTTP 契约（主项目 M4）

- [ ] `/api/v1/health` 免 Bearer 可读；带错 Bearer 的 `/api/v1/jobs` 返回 401
- [ ] 首次提交 202 且 `status=QUEUED`、`revision=0`、`payload=null`
- [ ] 同键同内容重提 200 且 `jobId` 不变（不重复计费）
- [ ] 同键异内容 409，原任务不受影响
- [ ] `Idempotency-Key` 与 `clientRequestId` 不一致 → 422
- [ ] 轮询至终态：`revision=1`，`payload` 通过主项目校验
- [ ] 真实媒体起点/中点/末尾三处时间与主项目播放器跳转一致（**容差待双方商定**）
- [ ] 越权访问他人 `jobId` → 404 且无数据泄漏
- [ ] 断网/重启主项目后，用同一 `clientRequestId` 重提能恢复

### 6.3 与 videoreact 并行（同一媒体、同一次课堂）

- [ ] 两份结果都 `SUCCEEDED`、`mediaSha256` 与 `durationMs` 相同、`timeBase` 均为 `MEDIA_START_MS`
- [ ] 用**区间相交**做窗口↔片段关联，`expected-statistics.json` 的 `windowSegments` 对得上
- [ ] 转写缺口（20–50s 无片段）不导致抬头曲线也被错误截断
- [ ] 两模块的 `providerId` / `moduleVersion` 分别登记，不互相覆盖

---

## 7. 我方仍需要主项目确认的清单（阻塞项已标注）

| # | 事项 | 是否阻塞 | 我方默认 |
| --- | --- | --- | --- |
| 1 | M4 时间容差数值 | **阻塞路径 B 验收** | 未定；建议与转写模块共用同一容差 |
| 2 | §5 的 4 个补充错误码由谁吸收 | 阻塞错误态展示 | 待在交接表 §10 评审 |
| 3 | `/api/v1/health` 免 Bearer 是否接受 | 不阻塞 | 免 Bearer（可快速改成强制） |
| 4 | `windowMs` 生产取值（建议 10–30s） | 不阻塞 | 10000 |
| 5 | `MIN_LEGIBLE_RATIO=0.60` 是否接受 | 不阻塞（影响有效覆盖） | 0.60 |
| 6 | 代表帧位置 `middle` 是否可接受 | 不阻塞 | middle |
| 7 | 代表帧是否需保留供人工复核 | 不阻塞 | 默认全删 |
| 8 | 真实课堂精度验收标准（几段视频、多少标注窗口） | **阻塞"精度验收"这一句结论** | 无标准，我方不宣称精度 |
| 9 | 凭证粒度（部署级 vs 按任务范围） | 不阻塞 | 部署级 |
| 10 | 生产部署形态（HTTPS 终止/反代/容器/日志采集） | 阻塞上线 | 未定 |
| 11 | 是否收紧提示词（禁止"猜"背对镜头的人） | 不阻塞（影响数值） | 暂用 `headup-prompt-1`，严格版已备好 |
| 12 | 抖动如何在页面呈现（声明估计值 / 临界窗口二次判定） | 不阻塞 | 声明"本次运行估计值" |
| 13 | 页面是否显示实际使用的视觉模型 | 不阻塞 | 建议显示 |

---

## 8. 对接时最容易踩的五个坑（实测总结）

1. **`Idempotency-Key` 必须等于 body 的 `clientRequestId`**，否则 422（不是 409）。
2. **续期下载链接后要沿用原键**：指纹不含 `sourceUrl`/`urlExpiresAt`，换键会重复计费。
3. **`NO_SUBJECTS`(0/0) 与"0% 抬头"(0/N) 不是一回事**，页面必须分开显示，否则会误导成"没人抬头"。
4. **`LOW_QUALITY` 的两个计数是 `null` 而不是 0**：任何把 null 当 0 参与加权平均的实现都会算错。
5. **别用等长演示样例去验证时长加权**：等长窗口下 `Σ(r_i·d)/Σd` 与 `Σc_i/Σn_i` 恒等，
   错误实现在样例上也会"通过"。
