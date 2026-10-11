# 交付样例与自校验（抬头率模块 v0.1）

契约版本：wire `schemaVersion=1.0` / 候选契约 v0.1。本目录同时服务两件事：

1. **M2 结构联调**：主项目用合成样例验证"标准接收、幂等/版本、统计与曲线"（不需要本模块在线）；
2. **M3/M4 真实链路**：主项目用真实媒体样例核对"同一媒体 → 本模块 → 结果"的形状与时间轴。

> **本轮全部样例均非真实课堂数据**：合成样例是人工编造的观测值；"真实媒体"样例来自
> 一段用 ffmpeg 合成的示意教室图（4×5 人形轮廓）+ 正弦音轨。它们**不能**作为识别精度证据。

---

## 1. 文件清单

| 文件 | 用途 |
| --- | --- |
| `head-up-request.json` | 计数模式提交请求（60s / 10s 窗 / `head-up-demo-v1`） |
| `head-up-success.json` | 计数模式成功终态（固定数值 30/40、24/40、0/40、0/0、遮挡、20/40） |
| `head-up-ratio-request.json` / `head-up-ratio-success.json` | 同一统计意义的纯比例兼容模式 |
| `head-up-realclassroom-request.json` | 真实模型测试视频的提交请求形状；`sourceUrl` 为占位符 |
| `head-up-realclassroom-success.json` | **真实 Qwen-VL 输出**的终态信封（5 窗全部 VALID） |
| `head-up-realclassroom-stats.json` | 上述结果的 stats-v1 统计快照（均值 0.715） |
| `realclassroom-run-record.json` | 运行记录：模型、提示词版本、耗时、逐窗判读、质量分布、素材来源与授权 |
| `head-up-real-request.json` | 早期真实（合成）媒体样例的提交请求；`sourceUrl` 已替换为占位符 |
| `head-up-real-success.json` | 对合成示意图媒体的**真实处理结果**（无凭证时全部降级） |
| `head-up-real-stats.json` | 上述结果的统计快照 |
| `head-up-http-success.json` | 通过 HTTP 契约路径（POST → 轮询）拿到的终态信封 |
| `head-up-http-stats.json` | 上述 HTTP 结果的统计快照 |
| `http-run-record.json` | HTTP 联调记录：202/200、jobId 复用、质量分布 |
| `media-manifest.json` | 生成信息：分析器、口径、媒体 hash、质量汇总 |
| `media/headup-synthetic-classroom-20s.mp4` | 合成示意图媒体（20 秒，人形轮廓；模型正确判为"非真人"） |
| `media/headup-realclassroom-scenes-50s.mp4` | **真实课堂照片拼接的 50 秒测试视频**（5 机位，见 §3.1） |
| `verify_head_up_fixtures.py` | 仅标准库的自校验脚本 |

### 3.1 `headup-realclassroom-scenes-50s.mp4` 的素材来源

由 **CC 授权的真实课堂照片**硬切拼接，每 10 秒一个窗口（Windows 1–5）：

| 窗口 | 素材照片 | 授权 | 场景 |
| --- | --- | --- | --- |
| win-001 | Professor and students in a university classroom in Tennessee | CC BY 4.0 | 正面视角：教师与学生同框 |
| win-002 | Reading Wikipedia in the Classroom … 81 | CC BY-SA 4.0 | 俯拍：学生低头操作设备，多人仅见后脑 |
| win-003 | Reading Wikipedia in the Classroom … 93 | CC BY-SA 4.0 | 教室全景 |
| win-004 | Reading Wikipedia in the Classroom … 24 | CC BY-SA 4.0 | 学生活动场景 |
| win-005 | Reading Wikipedia in the Classroom … 30 | CC BY-SA 4.0 | 竖幅教室全景 |

来源：Wikimedia Commons（摄影者见各图片页）。
**这不是真实课堂录像，也没有连续运动**：它用于跑通"视频 → 抽帧 → 真实模型 → 窗口 → 统计"，
并暴露不同机位下的可判断比例问题。完整实测结论见
[`docs/fixtures/head-up-module-v0.1/测试记录-真实模型.md`](测试记录-真实模型.md)。

> ⚠️ 使用这些照片时请遵守原授权（CC BY 4.0 / CC BY-SA 4.0）的署名要求；
> 本仓库仅用于内部联调测试。

## 2. 固定数值（与主项目 `expected-statistics.json` 一致）

媒体 60000ms，六个 10000ms 窗口：`30/40`、`24/40`、`0/40`、`0/0`、遮挡、`20/40`。

- rate 依次 `0.75`、`0.60`、`0`、`null`、`null`、`0.50`；
- 有效窗口 40000ms，**时长加权平均 0.4625**，有效覆盖 **2/3**，处理覆盖 **1**；
- 相邻有效窗口变化 `-15`、`-60` 个百分点；跨无效窗不计算相邻变化；
- 纯比例版本必须给出同样的平均值，且所有人数为 `null`。

> 注意：这组演示数据里六个窗口**等长**，因此"时长加权"与"人次汇总"恰好同值（都 0.4625）。
> 两者只有在窗口长度不等时才分叉——主项目实现必须按时长加权（`stats-v1`）。

## 3. 真实（合成）媒体样例说明了什么

`head-up-real-success.json` 的价值不在于数值，而在于它证明：

- 受控下载 → **sha256 核验** → ffprobe 读时长 → 按 `windowMs` 抽帧 → 归一化到原媒体毫秒轴这条链路可跑；
- 没有判定能力时（本机未配 `DASHSCOPE_API_KEY`，使用 `VISION_PROVIDER=heuristic`）
  **不会编造人数**：窗口会落成 `NO_SUBJECTS`/`LOW_QUALITY`，而不是假的 `VALID`；
- 窗口恰好覆盖 `analysisRange`，`sampleAtMs` 落在窗口内，`revision=1`、终态不可变。

`media-manifest.json` 里的 `realFixture.credentialsConfigured` 明确记录当时**是否有真实模型凭证**：
`false` 表示这份样例只验证了链路与降级，不是模型输出。

## 4. 运行自校验

```powershell
python docs/fixtures/head-up-module-v0.1/verify_head_up_fixtures.py
```

脚本会检查：信封字段、`timeBase`、`revision/status` 对应、`processedRanges` 与 `windows`
的有序/不重叠/恰好覆盖、三态质量语义、两种 `valueMode` 的互斥约束、请求与结果的一致性、
合成样例与冻结统计的数值、HTTP 记录的 202/200 与 jobId 复用。

它**不是**完整 JSON Schema 校验器，也不是服务端接收器或生产契约测试的替代品。

## 5. 重新生成（改动过提示词/口径/配置后）

```powershell
# 需要 ffmpeg/ffprobe（PATH 或 FFMPEG_PATH/FFPROBE_PATH）
.\scripts\Run.ps1 -Target gen
# 想连 HTTP 契约路径一起跑（会另起本地媒体服务 + 模块服务）：
$env:MODULE_BEARER_TOKENS='dev-only-token'; $env:ENABLE_FIXTURE_PROVIDER='true'; $env:VISION_PROVIDER='heuristic'
python -m headup generate-fixtures --output docs/fixtures/head-up-module-v0.1 `
  --media docs/fixtures/head-up-module-v0.1/media/headup-synthetic-classroom-20s.mp4 --duration-ms 20000 --serve
```

> 生成"真实媒体"样例会**重写** `docs/fixtures/.../media/*.mp4`，其 `sha256` 会变，
> 因此 `head-up-real-*.json` 必须一并重新生成，不要手工拼接不同批次的样例。
> 重跑前请清掉 `.data/` 里的任务库，否则同 `clientRequestId` 会命中 409（内容已变）。

## 6. 按里程碑联调矩阵（抬头侧）

| 场景 | 预期 | 本轮状态 |
| --- | --- | --- |
| 合成样例导入（M2） | 结构、统计、曲线、缺失态正确 | 样例已交付（与主项目冻结样例逐字段一致），**主项目侧尚未导入** |
| 纯比例模式 | 与计数模式数值等价、人数全 `null` | ✅ 脚本校验 |
| 真实 0 / `NO_SUBJECTS` / `LOW_QUALITY` | 三者不可混同 | ✅ 样例与测试覆盖 |
| 人数超分母、零分母、比例越界、重叠窗、重复 ID | 拒绝，不入库 | ✅ 模块侧 422/内部校验；主项目侧待验 |
| 同键同内容 / 异内容 | 200 复用 / 409 | ✅ HTTP 实测（见 `http-run-record.json`） |
| 视觉模型客户端协议 | 请求形状/鉴权/图像编码/重试/主备切换/异常解析 | ✅ 本地假 DashScope 服务逐条测过（`tests/test_qwen_client.py`） |
| 真实媒体起点/中点/末尾时间核对（M4） | 偏差在商定容差内 | ⚠️ **未执行**：需要真实课堂媒体 + 真实凭证 + 双方容差 |
| 真实课堂识别精度 | 与人工标注对照 | ⚠️ **未执行**：当前无真实标注集 |
| 模型超时/限流 | 显式失败、不产生假结果 | ⚠️ 假服务已覆盖 429/5xx/非 JSON；**未用真实配额触发过** |
| 重启/并发/凭证过期 | 可恢复、无重复副作用 | ⚠️ 租约回收有单测，**未做服务级长稳验证** |
