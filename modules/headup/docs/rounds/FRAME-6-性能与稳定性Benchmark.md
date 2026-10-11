# FRAME-6：性能与稳定性 Benchmark（实测）

> 目标（plan §23 FRAME-6 / §28）：把"抽帧方式、模型调用量、整节课耗时与成本"从**外推**变成**实测**，
> 并**据此**决定是否引入 Decord（没有实测数据前不引入）。
>
> 原则：宁可少给一个数字，也不要给一个猜出来的数字。没测到的量在本文里一律标注"未实测"或"推算"。

---

## 1. 修改文件

| 文件 | 改动 |
| --- | --- |
| `src/headup/perf.py` | **新增**：`RunMetrics`（分阶段计时/计数/token/字节）、`CountingAnalyzer`（逻辑调用计数）、`CostModel`（单价可配的成本估算）、`measure_files` |
| `src/headup/benchmark.py` | **新增**：抽帧基准（批量 vs 逐点 + 逐点等价性 + 时间点证据）、端到端基准、报告与结论生成 |
| `src/headup/video/frame_extractor.py` | **改**：`extract_at` 改为**输入侧 seek + `-accurate_seek`**（实测像素级等价、快 10–45 倍）；**新增** `extract_grid()`（等间隔前缀批量 + 尾部逐点）、`uniform_prefix()`、`decoded_timestamps()`（时间点证据）；`last_mode`/`last_equivalence`/`last_grid_split` 真实路径上报 |
| `src/headup/vision/qwen_client.py` | 传输层计数：`request_count`（含重试的真实 HTTP 请求）、`request_notes`、`token_totals` |
| `src/headup/pipeline.py` | 分阶段计时（下载/探测/抽帧/判定/复核/聚合/清理）、计数器、落盘字节；`JobRunResult.metrics` |
| `src/headup/store.py` | `head_up_job.metrics_json` 列 + 幂等迁移 + `set_metrics()` / `metrics_of()` |
| `src/headup/service.py` | 指标落库（**含失败运行**） |
| `src/headup/server.py` | `GET /api/v1/jobs/{jobId}` 增量字段 `metrics` |
| `src/headup/config.py` | `HEADUP_PERF_METRICS`、`HEADUP_COST_CURRENCY`、`VISION_PRICE_PROMPT_PER_1K`、`VISION_PRICE_COMPLETION_PER_1K`；`/health.performance` |
| `src/headup/cli.py` | **新增** `headup benchmark`；`headup run --metrics-out` 并打印实测汇总；控制台编码安全 |
| `src/headup/frames.py` | `plan_frames()` 改为 `SamplingPolicy` 的薄包装（FRAME-5 遗留 #4：网格实现只剩一份） |
| `tests/test_frame6_perf.py` | **新增** 39 个用例 |
| `.work/frame6_real_class.py` | 真实模型整节课测量脚本（HTTP 契约路径 + 真实凭据） |
| `.work/dbg_*.py`、`.work/frame6_summary.py` | 定位/取证脚本（保存证据用，非交付物） |

---

## 2. 关键设计

1. **指标不进契约 payload。** payload 只描述抬头率结果，保持确定性；指标落 `head_up_job.metrics_json`，
   通过 `GET` 的增量字段 `metrics` 暴露（主项目可完全忽略，契约字段逐字段不变）。
2. **两个层级的调用计数不能混为一谈。**
   - 逻辑调用（`vision.calls`）：一次 `analyze()` / `analyze_window()` = 一个窗口观测；
   - 真实 HTTP 请求（`notes.visionRequests`）：含**计数不自洽重试**与**备用模型重试**，这才是计费口径。
3. **成本只有配了单价才给。** `VISION_PRICE_*_PER_1K` 未配置 ⇒ `cost = null`，只报实测 token；
   配置后给金额 + 单价 + "估算非账单"说明。模块不内置任何价格。
4. **网格唯一来源不变。** 批量只接受 `SamplingPolicy` 给出的时间点；`extract_grid()` 只做
   "最长等间隔前缀批量 + 尾部少量点逐点"的拆分，**时间戳、窗口 id、文件名一律不变**。
5. **回退必须可见，指标不许把回退写成批量。** `last_mode ∈ {BATCH, BATCH+SEEK_TAIL, SEEK_FALLBACK, SEEK}`
   一路进 `metrics.notes.extractionMode`。
6. **"帧精确"要有证据。** `decoded_timestamps()` 用 `showinfo` 读**实际解码帧**的 pts，
   在报告里给出"请求时间点 vs 实际帧"的偏移（基准用，生产路径不付这个开销）。
7. **量化决策线可配**：`benchmark --decord-threshold 0.5`（秒/帧）。

---

## 3. 实测数据

测试机：本机 Windows（Python 3.13 + FFmpeg 便携版）。素材：`1280x720 / h264 / 5fps / 有音轨`。
45 分钟版本由 `headup-realclassroom-scenes-50s.mp4`（真实课堂照片拼成的 50 秒场景视频）循环 54 次
`-c copy` 得到：**duration = 2700.467s ⇒ 270 个完整 10 秒窗 + 1 个 0.467s 末窗 = 271 个采样点**，82.7MB。

> 素材说明：画面内容是同一段 50 秒真实课堂画面重复 54 次。**耗时/调用/token/成本可迁移，
> 抬头率数值不可迁移**；结尾"遗留问题"列出缺真实课堂视频时必须重测的项目。

### 3.1 抽帧：批量 vs 逐点 seek（整节课 271 点，各 2 次）

| 方式 | 实测耗时 | 单帧 | 帧数 | 落盘 | 实际路径 |
| --- | --- | --- | --- | --- | --- |
| **批量** | **6.17 / 6.29 s** | **0.023 s** | 271 | 49.08 MB | `BATCH+SEEK_TAIL`（270 点批量 + 末点逐点） |
| **逐点 seek** | **31.37 / 31.80 s** | **0.117 s** | 271 | 49.08 MB | `SEEK` |
| 加速比 | **5.07×** | — | — | — | — |

- **逐点等价性：271/271 点等价**，最大平均像素差 **0.0137**（容差 2.0）；
- **时间点证据**（`showinfo` 读实际解码帧 pts）：批量帧偏移 **0.0 ms**（完全落在网格上）；
  逐点抽样最大偏移 **155.1 ms**，落在一帧之内（5fps ⇒ 帧间隔 200 ms）；
- 批量路径内部结构：270 点等间隔前缀一次抽完 + 末窗短导致的 1 点逐点（见 §5.1）。

小素材对照（50 秒 5 点，各 3 次）：批量 0.29 / 0.32 / 0.38 s，逐点 0.46 / 0.48 / 0.49 s，
**1.50×**，5/5 点等价（最大像素差 0.0017），两类帧的时间点偏移都是 0.0 ms。

**稀疏网格对照（同一 45 分钟素材，10 点、间隔 270 秒，各 2 次）**：逐点 **1.03 / 1.09 s**；
批量 **6.86 / 6.88 s** 且 `SEEK_FALLBACK`（整段回退，见 §5.2）。
⇒ 网格稀疏时"一次批量"并不划算：批量固定要付一次全片解码，而输入侧 seek 只付目标点附近的解码。

### 3.2 CPU 与解码（`ffmpeg -benchmark` 单独测量）

| 命令 | wall | utime | stime | maxrss |
| --- | --- | --- | --- | --- |
| 45 分钟整段解码 + 稀疏抽帧（11 帧上限） | 5.32 s | 15.70 s | 4.09 s | 282 MB |

- 解码是**多线程**的（CPU/wall ≈ 3.7），所以 `utime` 远大于 wall；
- **整段解码的 wall time 与网格疏密无关**（全网格 5.43s、稀疏网格 5.29s）——`fps` 滤镜本来就要把
  整段视频解一遍。这正是"稀疏网格用批量反而更慢"的原因；
- **本模块不报 CPU 时间**：ffmpeg 是子进程，Python 的 `process_time()` 测不到，Windows 上也没有可用的
  `RUSAGE_CHILDREN`。需要 CPU 数据时用 `ffmpeg -benchmark` 单独跑（上表）。

### 3.3 磁盘用量（整节课实测）

| 项 | 实测 |
| --- | --- |
| 单帧（代表帧，1600 宽 JPEG `-q:v 2`） | 181,116 字节 ≈ 177 KB |
| 整节课代表帧 271 张 | 49.08 MB |
| 复核帧 720 张（q:v 3，见 §6.2） | 99.77 MB（≈ 138.6 KB/张） |
| 下载的媒体（临时） | 82.67 MB（任务结束即删） |
| **峰值** | ≈ **231.5 MB**（代表帧 + 复核帧 + 临时媒体） |
| 生命周期 | 成功即删（本次实测清理阶段 987.7 ms）；失败保留 12h（可配）；帧**不进**数据库 |

### 3.4 真实模型整节课（45 分钟 / 271 窗 / qwen3-vl-flash，真实凭据）

报告文件：`.work/real-test/frame6-class45-flash.json`（脚本 `.work/frame6_real_class.py`）。

| 指标 | 实测 |
| --- | --- |
| 终态 / 版本 | `SUCCEEDED`，`revision=1`，`currentStage=SUCCESS` |
| **端到端 wall time** | **839.9 s ≈ 14.0 分钟** |
| 阶段时间线 | 抽帧 3.0s → 判定开始 15.1s → SUCCESS 839.9s |
| 抽帧 | 11.84 s（`BATCH+SEEK_TAIL`，270 批量 + 1 尾部逐点） |
| 代表帧判定（`VISION_PRIMARY`） | 271 次，合计 362.9 s（均 1.34 s，最大 20.6 s） |
| 复核（`REVIEWING` 含取帧） | 241 窗，合计 461.4 s（均 1.9 s，最大 20.6 s） |
| 其中复核模型调用（`VISION_REVIEW`） | 243 次，合计 376.2 s（均 1.55 s）⇒ 复核取帧约 85 s |
| 聚合 / 清理 | 8.2 ms / 987.7 ms |
| **逻辑调用** | **513**（271 代表帧 + 242 复核） |
| **真实 HTTP 请求** | **549**（+36 = 计数不自洽重试 35 次 + 连接中断重试 1 次） |
| 多图请求 | 239（另 1 次退化为逐帧单图） |
| **token** | prompt **1,115,808** / completion **29,984**（每次逻辑调用均 2,175 prompt tokens） |
| **成本（flash 官方牌价估算）** | **$0.0509 ≈ ¥0.367**（单价 0.000036 / 0.000359 USD per 1K，估算非账单） |
| review 比例 | **241/271 = 0.8893**（素材每 5 秒硬切，突变阈值几乎每窗触发 —— **素材特性**） |
| 质量分布 | VALID 130 / LOW_QUALITY 141 / NO_SUBJECTS 0；coverage 均值 0.620 |
| 最终来源 | PRIMARY 30 / REVIEW_WINDOW 241 |
| 课程均值（stats-v2，仅 VALID，SUM 比） | 0.7253（874/1205）——**该素材的数值，不代表真实课堂** |

两点必须说清楚：

1. **成本比早期估算高约 3 倍**（早期 ¥0.11–0.14/课）。原因不是单价涨了，而是**复核比例**：
   本次 1.89 次调用/窗、且复核是**三图一次请求**（每次逻辑调用均 2,175 prompt tokens，
   而单图严格口径约 1,377）⇒ **复核比例直接决定成本**。真实课堂的复核比例必须重测。
2. **141/271 窗为 `LOW_QUALITY`**：素材里有讲台/空教室/只见后脑的画面，严格口径下模型拒绝猜测、
   给 UNKNOWN ⇒ 该窗两数 null、不进均值。这正是"宁可少给一个数字"的预期行为。

> 时间注记：本次整节课测量在 §5.3 的 seek 修正**之前**完成。修正只影响逐点 seek
> （本课的批量前缀不受影响、复核帧走的是另一条 `media.extract_frame` 路径），
> 影响量级 ≈ 尾部 1 点的 5.4s → 0.1s，即 839.9s 的 0.6%。

### 3.5 2 分钟试点（12 窗，先跑通链路）

| 指标 | 实测 |
| --- | --- |
| 端到端 | 36.3 s |
| 逻辑调用 / 多图调用 | 23 / 11 |
| 真实 HTTP 请求 | 24（1 次计数不自洽重试：模型给出 `19 ≠ visible_count 17`） |
| token | prompt 50,514 / completion 1,343 |
| 成本（flash 官方牌价） | $0.0023 ≈ ¥0.017 |
| review 比例 | 11/12 = 0.917（同上：素材每 5 秒硬切所致） |

### 3.6 稳定性

| 观测量 | 结果 |
| --- | --- |
| 整节课批量抽帧 | 6.17 / 6.29 s（同机同素材，极差 2%） |
| 整节课逐点 seek | 31.37 / 31.80 s（极差 1.4%） |
| 小素材（50s）批量 | 0.29 / 0.32 / 0.38 s（极差 31%，绝对值小、受进程启动影响大） |
| 单帧代价量级 | 批量 0.023 s、逐点 0.117 s（整节课）；小素材 0.064 / 0.096 s |
| 模型延迟 | 本课 513 次调用，单次最大 20.6 s、均值 1.3–1.6 s；早期实测同一 5 窗任务 16.2s ↔ 36.3s |

**结论**：抽帧的 wall time 在整节课尺度上很稳（极差 ≤ 2%）；小素材因进程启动开销占比高，抖动百分比大但绝对值 < 0.1s。
模型侧波动由服务端决定，**整节课耗时只能给量级与区间**（本次 14 分钟，其中 88% 是模型等待）。

---

## 4. Decord 决策（plan §28）

| 判据 | 实测/结论 |
| --- | --- |
| 决策线（可配 `--decord-threshold`） | 批量单帧 < 0.5 s/帧 ⇒ 不必引入 |
| 整节课网格批量 | **0.023 s/帧**（271 帧 6.2 s）——约为决策线的 **1/22** |
| 逐点 seek（输入侧 accurate_seek） | 0.117 s/帧，与采样位置无关（修正前是 0.1→5.1 s/帧，随位置线性增长） |
| 最坏情况 | 稀疏网格上批量的 `fps` 相位会错位 ⇒ 整段回退；那是**相位问题**不是吞吐问题，换 Decord 一样要处理时间对齐 |
| 引入成本 | 新增编译依赖；抽帧语义分叉（现有等价性证明、时间戳证据、文件名规则都基于 FFmpeg） |

**结论：不引入 Decord。** 复审触发条件（任一条满足时用同一条 `benchmark` 命令重测）：
批量单帧 > 0.5 s、需要 GPU 解码、或出现 FFmpeg 无法按时间点取帧的编码格式。

---

## 5. 本轮发现并修掉的真问题

### 5.1 末窗短 ⇒ 网格不等间隔 ⇒ 整段批量被正确拒绝

45 分钟素材实测：最后一个采样点在 `2695233ms`（末窗只有 467ms），与前一点间隔 10233ms，
整段交给 `extract_batch()` 会被**正确拒绝**（批量要求等间隔）。
旧行为是整节课退回逐点 seek（修正前外推 ≈12.9 分钟；修正后实测 31.4s）。
→ 新增 `extract_grid()`：**等间隔前缀批量 + 尾部少量点逐点**，时间戳/窗口 id/文件名不变，
整节课抽帧 **6.2 s**。指标里能看到拆分：`notes.extractionGridSplit = {batchPoints: 270, seekPoints: 1}`。

### 5.2 `fps` 相位陷阱（保留为兜底，不再静默）

稀疏网格（间隔 270s ⇒ `fps=0.0037`）上，批量首帧与逐点帧的平均像素差 **52.0/255**（取到了相邻场景），
被首帧等价性校验拦下并整段回退。顺手试过的"把 `-ss` 挪到 `-i` 之后"更糟：
50 秒素材首帧差异 **62.3/255**。→ 保留输入侧 `-ss` + 首帧等价性校验作为兜底，
并把失败时的平均像素差写进 `metrics.notes.extractionEquivalence`；基准报告在回退时显式标注
"本比较实际是逐点 vs 逐点，不能作为批量等价性证据"。

### 5.3 逐点 seek 一直在"从片头解码"（本轮最大的性能问题）

FRAME-1 的实现把 `-ss` 放在 `-i` **之后**（纯输出侧）：每次都从 0 解码到目标点。
实测 45 分钟素材：`5s → 0.11s`、`300s → 0.68s`、`2435s → 5.08s`（随位置线性增长），
于是"整节课逐点 seek"外推 ≈12.9 分钟，也把批量回退的代价放大到分钟级。

实测证明**输入侧 `-ss` + 默认 `accurate_seek` 同样帧精确**：

| 请求点 | 输入侧耗时 | 输出侧耗时 | 两者画面平均像素差 | `media.extract_frame` 与输出侧差异 |
| --- | --- | --- | --- | --- |
| 5 s | 0.126 s | 0.113 s | **0.000** | 0.992（仅 JPEG 质量差） |
| 300 s | 0.116 s | 0.684 s | **0.000** | 0.993 |
| 1350 s | 0.106 s | — | **0.000** | 0.893 |
| 2435 s | 0.108 s | — | **0.000** | 1.902 |

→ 改为输入侧 `-ss` + 显式 `-accurate_seek`，并用 `showinfo` 把"实际解码帧的 pts 偏移"
（0–155 ms，落在一帧内）写进基准报告，作为"帧精确"的可核对证据。
**整节课逐点 seek：31.4s（修正前外推 12.9 分钟）；批量回退的代价同步降了两个数量级。**

### 5.4 指标曾会骗人（我自己引入的 bug）

`extract_grid()` 最初无条件把 `last_mode` 写成 `BATCH`，掩盖了 `extract_batch()` 内部的整段回退
——报告会显示"批量成功"而实际是逐点。已修：回退原样上报，并在基准里标注比较证据是否成立。

### 5.5 GBK 控制台让成功的基准变成"命令失败"

结论文案里的 `⇒` 在 GBK 控制台抛 `UnicodeEncodeError`，命令以 exit 1 结束。
→ 结论文案改用 ASCII 箭头；`cli.main` 统一把输出流错误策略设为 `replace`（不换编码，中文照常显示）。

---

## 6. 遗留问题

| # | 问题 | 计划 |
| --- | --- | --- |
| 1 | **缺真实课堂视频**：review 比例（本次 0.889 是"每 5 秒硬切"素材的特性）、真实课堂的整节课耗时/成本与课程均值都需重测 | 拿到视频后重跑 §3.4 / §3.5 |
| 2 | **抽帧有两套实现**：代表帧走 `FrameExtractor`（帧精确、`-q:v 2`、有等价性证明），复核帧走 `media.extract_frame`（输入侧 seek、`-q:v 3`、无等价性证明）。实测两者画面差 0.89–1.9（纯 JPEG 质量差，非时间点差） | 下一轮统一到 `FrameExtractor`；需先把复核取帧改批量，否则逐窗 3 帧会变慢 |
| 3 | 逐窗**串行**：整节课耗时 ≈ Σ 单窗延迟（本次 88% 时间在等模型） | 并发前必须先解决：①复核的突变检测依赖**前序窗口**；②分析器的 `used_models`/`last_trace` 是实例状态、非线程安全。本轮**不引入**（没有实测收益证据，且有改变判定语义的风险） |
| 4 | 稀疏网格上批量不可用（相位） | 用 `--every` 抽稀时认清语义；报告已自动标注证据缺失。批量只服务"完整网格"这一主场景 |
| 5 | CPU 时间只能在模块外测（`ffmpeg -benchmark`） | 容量规划时加外部采样脚本 |
| 6 | 逐窗口样本仍无独立路由（只在 `GET` 附带，271 条时响应体较大） | 主项目需要时分页路由 |
| 7 | 失败运行的指标已落库，但无独立保留/清理策略 | 体积小（几 KB/任务），暂不需要 |
| 8 | 成本估算依赖部署方配置单价 | 单价随官方调价变化，写入部署手册而不是代码常量 |

---

## 7. 验收标准核对（plan §23 FRAME-6 + §28）

| 验收标准 | 结果 |
| --- | --- |
| 批量 vs 逐点的耗时/CPU/磁盘有实测 | ✅ §3.1（整节课 271 点各 2 次 + 稀疏 2 次 + 小素材 3 次）、§3.2（`ffmpeg -benchmark` CPU）、§3.3（逐帧/峰值字节） |
| Qwen 总调用数可查 | ✅ 逻辑调用 513 / 真实 HTTP 549（含 36 次重试），落库 + `GET.metrics` |
| Review 比例可查 | ✅ `derived.reviewRatio = 0.8893`，并区分 `windows.reviewed / reviewApplied` |
| 每节课耗时与成本 | ✅ 端到端 839.9s（真实凭据实测）；成本 $0.0509 ≈ ¥0.367（单价可配、标注为估算） |
| Decord 决策有依据 | ✅ §4：不引入，附量化复审条件 |
| 指标不破坏契约 | ✅ 不进 payload；`GET` 增量字段；契约字段逐字段不变（`test_http` 冻结比较通过） |
| 不破坏既有口径与测试 | ✅ **316 个用例全绿**（原 277 + 新增 39）；`selftest` 与 5 个冻结信封校验通过 |
| 阈值可配 | ✅ Decord 决策线、成本单价、指标开关、抽样步长均可配 |
| 稳定性有数据 | ✅ §3.6：整节课抽帧极差 ≤ 2%；模型侧延迟波动如实记录，不写单次数字当承诺 |

---

## 8. 复现命令

```powershell
# 抽帧基准（批量 vs 逐点 + 等价性 + 时间点证据 + Decord 决策）
python -m headup benchmark --file .work/frame6/class45-loop.mp4 --extraction both `
    --window-ms 10000 --repeats 2 --out .work/real-test/report.json

# 真实模型整节课（HTTP 契约路径，产出 metrics/token/成本）
$env:DASHSCOPE_API_KEY = "<key>"
python .work/frame6_real_class.py .work/frame6/class45-loop.mp4 --model qwen3-vl-flash `
    --out .work/real-test/frame6-class45-flash.json

# 单机 CLI 路径的实测指标（不落库，直接写文件）
python -m headup run --file <video> --metrics-out .work/real-test/metrics.json

# 从落库样本汇总整节课口径（只读）
python .work/frame6_summary.py

# 合成 45 分钟素材（真实 50 秒课堂场景循环，仅用于性能/成本测量）
ffmpeg -stream_loop 53 -i docs/fixtures/head-up-module-v0.1/media/headup-realclassroom-scenes-50s.mp4 `
    -c copy -t 2700 .work/frame6/class45-loop.mp4
```

成本单价示例（**牌价以阿里云百炼当前公示为准**，仓库不内置价格）：

```powershell
$env:VISION_PRICE_PROMPT_PER_1K="0.000036"      # USD / 千 token（flash 输入档，示例）
$env:VISION_PRICE_COMPLETION_PER_1K="0.000359"  # USD / 千 token（flash 输出档，示例）
$env:HEADUP_COST_CURRENCY="USD"
```

---

## 9. 补记（用户报告的两个问题，已修复）

### 9.1 缺陷：抽帧失败窗口使**整个任务**的审计样本丢失

**用户报告**：LOW_QUALITY 窗口 `sampleAtMs=null` 时，样本持久化可能触发
`head_up_sample.timestamp_ms` 非空约束异常；异常被捕获，任务与测试仍显示成功，
但该窗口的审计样本可能没有落库。

**复现（先用数据确认，再改）**：

```text
timestamp_ms NOT NULL = 1
save_samples 抛异常: IntegrityError: NOT NULL constraint failed: head_up_sample.timestamp_ms
实际落库行数 = 0（期望 3）
结论: 整个批次被回滚，样本全丢
```

比报告的更严重：`save_samples()` 把所有行放进**一个事务**，一行违反约束 ⇒ 整批 `ROLLBACK`，
**该任务的全部逐窗口样本**都没有落库；`_persist_samples()` 把异常捕获成一行 ERROR 日志，
任务仍然 `SUCCEEDED` ⇒ 表面成功、审计线索静默丢失。

**触发路径（两条，都真实可达）**：

1. 流水线：某窗口抽帧失败 ⇒ `qualityReason=FRAME_EXTRACTION_FAILED`、`sampleAtMs=null`
   （该窗口确实**没有采样点**），而任务只要有别的窗口可判读就仍然成功；
2. 合成 provider：`FixtureProvider` 对 `quality == LOW_QUALITY` 的窗口同样写 `sampleAtMs: null`
   —— **这一条在现有测试套件里已经真实触发过**（定位过程中在整轮套件输出里抓到了同一条
   `IntegrityError` 日志），只是没有断言检查样本条数，所以一直没被发现。

**修复**：

| 层 | 改动 |
| --- | --- |
| schema | `head_up_sample.timestamp_ms` 改为可空；老库自动**重建迁移**（改名 → 去旧索引 → 建新表 → 按字段搬运 → 删旧表 → 建索引），数据一行不丢 |
| 写入 | 新增 `save_samples_detailed()`：**逐行 SAVEPOINT 隔离**，坏行只回滚自己、好行照写；返回 `SamplePersistOutcome{written, failed[]}`。`save_samples()` 保持"返回写入行数"的旧签名 |
| 语义 | `timestamp_ms = payload.sampleAtMs`，为 null 就写 null（**不用窗口起点顶替** —— 那会伪装成一个真实采样时间点）；窗口靠 `window_start_ms/window_end_ms` 定位 |
| 可见性 | 写入条数与失败窗口进 `metrics.notes.samplesWritten` / `samplesPersistFailed`，并打 ERROR 日志（不再只留一行日志） |
| 排序 | `samples_of()` 按 `COALESCE(timestamp_ms, window_start_ms), window_id` 排序，没有采样点的窗口仍落在正确的课堂位置上 |

**验证**：

| 验证项 | 结果 |
| --- | --- |
| 复现脚本重跑 | 3/3 行落库（含 null 时间戳那行） |
| 真实库迁移（`.work/frame6/class-run/db.sqlite3`，283 行） | 迁移前后行数一致、字段顺序与当前 schema 一致、索引与唯一约束保留、重复打开幂等 |
| 迁移后课程均值 | `0.7253（874/1205）`、`0.7（28/40）` —— 与迁移前完全一致（数据无损） |
| 新增回归用例 | `tests/test_sample_persistence.py` **11 个用例**：null 落库、单行隔离、幂等 upsert、老库迁移（保留数据/幂等/字段一致）、合成 provider 路径、端到端（真实 `_process_window()` 造出抽帧失败窗口 + 真实落库 + `metrics` 记录） |

### 9.2 测试里的 `ResourceWarning`（未关闭 socket）

**定位**：用 `-W error::ResourceWarning` 逐模块跑，命中 `tests/test_frame6_perf.py`
的 `HttpMetricsFieldTest.tearDown` —— 只调了 `server.shutdown()`，没有 `server_close()`：
`shutdown()` 只停 `serve_forever()`，**监听 socket 要 `server_close()` 才释放**。

**顺带修掉产品里的同类问题**（同一模式）：

| 位置 | 问题 |
| --- | --- |
| `cli.cmd_serve` 的 finally | 退出时只 `shutdown()`，监听 socket 未释放 |
| `cli._serve_and_submit` | 模块服务与本地媒体服务都只 `shutdown()`，两个 socket 都未释放 |

**验证**：`python -W error::ResourceWarning -m unittest test_frame6_perf test_http
test_sample_persistence test_qwen_client` ⇒ **ResourceWarning 计数 0**，82 个用例 OK。
