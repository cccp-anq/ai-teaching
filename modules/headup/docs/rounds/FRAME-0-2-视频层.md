# FRAME-0 ~ FRAME-2 实施报告 · 视频层（本地抽帧）

日期：2026-09-15。范围：**FRAME-0（探测）、FRAME-1（单点精确抽帧）、FRAME-2（批量采样）**。
按 plan §29 逐轮推进，本轮未进入 FRAME-3（视觉层）。

> 前置澄清：本模块**从来没把长视频 URL 交给 Qwen**——media 的 `sourceUrl` 只用于受控下载，
> 抽帧一直是本地 ffmpeg 完成的。因此 plan §0/§12 描述的"要替换的主路径"在本仓库不存在；
> 本轮实际交付的是**抽帧工程化**：独立视频层、采样策略成为唯一网格来源、批量抽帧的等价性证明、
> 以及可追溯的帧文件命名。

---

## 1. 修改文件

| 文件 | 内容 |
| --- | --- |
| `src/headup/video/__init__.py` | **新增**：视频层入口（探测/抽帧/采样/工作区），明确"本层不做视觉判断" |
| `src/headup/video/probe.py` | **新增**：`VideoMetadata`、`VideoProbeService`、`ProbeError`（带 `error_stage`） |
| `src/headup/video/frame_extractor.py` | **新增**：`FrameSample`、`FrameExtractor.extract_at()`（帧精确）、`extract_batch()`（批量 + 首帧等价性校验 + 失败整段回退）、`verify_batch_equivalence()` |
| `src/headup/video/sampling.py` | **新增**：`SamplingPolicy`（**唯一网格来源**）、`SamplingPoint`、`SamplingPlan`、offset 过滤 |
| `tests/test_frame0_probe.py` | **新增** 11 个用例 |
| `tests/test_frame1_2_extract.py` | **新增** 23 个用例（含网格、offset、批量等价性） |
| `.work/batch_equivalence.py` | **新增**：批量 vs 逐点等价性实测脚本（输出证据 JSON） |

**未删除任何测试与冻结样例**；Round 1–4 与契约测试全部继续通过。

---

## 2. 关键设计（严格按三条决策）

### 2.1 采样网格只由 SamplingPolicy 生成（决策①）

```python
SamplingPolicy(window_ms=10_000, position="middle").grid(0, 60_000)
→ [5s, 15s, 25s, 35s, 45s, 55s]      # 窗口中点，与冻结样例的 sampleAtMs 完全一致
```

- 网格是**纯计算**，不接触 ffmpeg；`test_grid_does_not_depend_on_ffmpeg` 钉住这一点；
- `position` 可配 `start/middle/end`，末窗可短且采样点始终落在 `[start, end)` 内；
- 抽帧层只接受"给我这些时间点"，**不得自行定义网格**。

### 2.2 批量抽帧必须有等价性证明（决策①）

`extract_batch()` 的启用条件与自我保护：

1. **网格合法性先校验**：时间点必须等间隔（否则抛 `PROCESSING_FAILED`，让调用方改逐点）；
2. **首帧等价性校验**：批量用输入侧 `-ss`（快，但只保证关键帧对齐），因此额外用
   输出侧 `-ss`（帧精确）抽一张参考帧，逐像素比对（平均差 ≤ 2.0 视为等价），
   等价则用参考帧**内容**覆盖批量首帧（保留批量文件名与时间点）；
3. **不等价就整段回退**逐点 seek —— 宁慢不假。

实测证据（`.work/batch_equivalence.py`，50 秒 / 1280×720 / 5fps 真实画面素材）：

| 指标 | 结果 |
| --- | --- |
| 采样点 | 5 个（5/15/25/35/45s） |
| 最大平均像素差 | **0.0017**（阈值 2.0） |
| 全部等价 | ✅ 5/5 |
| 逐点 seek | 0.65s（0.130 s/帧） |
| 批量抽帧 | 0.32s（0.063 s/帧） |
| 加速 | **2.05×** |

结论写入 `.work/real-test/frame2-equivalence.json`，脚本可对任意视频复跑。

> ⚠️ 诚实说明：这是**短素材**上的 2 倍加速（每次进程启动固定开销占比高）。
> 真实 45 分钟课堂的加速比与绝对耗时**尚未实测**（等真实视频），不要在文档里写死数字。
> 另：抽帧从来不是耗时瓶颈——45 分钟课 270 帧，即使按 0.13 s/帧也只有约 35 秒，
> 真正的耗时在 VLM 调用（每帧 1–3 秒）。

### 2.3 offset 只过滤、不平移（决策②）

```python
SamplingPolicy(start_offset_ms=10_000, end_offset_ms=10_000)  # 默认都是 0
plan = build_sampling_plan(...)   # 60s 媒体
→ 保留 [15s, 25s, 35s, 45s]，窗口 id 仍是 win-002..win-005（不重排）
→ policy_version 变为 "10s-window-midpoint-v0.2+offset-10000-10000"
```

- **只丢弃**落在 offset 之外的采样点，保留点时间戳不变、窗口编号不重排
  （重排会让"第 N 窗"在两次运行间漂移）；
- v0.2 默认 `0/0`，因此现有契约、冻结样例、真实验证全部不受影响；
- 启用非零 offset 时 `effective_policy_version()` 自动带上 offset 后缀（决策②的升版要求）。

### 2.4 帧文件与证据链（plan §10/§26）

```text
frame-000120000.jpg      # 文件名直接带 timestampMs（9 位补零，字典序=时间序）
```

`FrameSample{timestampMs, imagePath, type, windowId}`，类型枚举 `PRIMARY / REVIEW_BEFORE /
REVIEW_CENTER / REVIEW_AFTER`（plan §5.2）。任何结果都能反查到"模型看的是哪一张图"。

### 2.5 错误码与阶段（决策⑤）

沿用契约 §5 现有码，阶段放在 `error_stage`：

| 场景 | 契约码 | stage |
| --- | --- | --- |
| ffmpeg/ffprobe 缺失 | `FFMPEG_MISSING` | `PROBING_VIDEO` / `EXTRACTING_FRAMES` |
| 文件不存在/为空/是目录/容器损坏 | `DECODE_FAILED` | `PROBING_VIDEO` |
| 无视频流（纯音频） | `UNSUPPORTED_MEDIA` | `PROBING_VIDEO` |
| 抽帧失败、批量数量不足 | `DECODE_FAILED` | `EXTRACTING_FRAMES` |
| 网格不等间隔（调用方错误） | `PROCESSING_FAILED` | `EXTRACTING_FRAMES` |

plan §9 的 `FFMPEG_NOT_AVAILABLE / VIDEO_UNREADABLE / UNSUPPORTED_VIDEO` 作为**内部阶段语义**
表达，不新增对外错误码。

### 2.6 探测能力补齐（FRAME-0）

原 `probe_media` 只取时长+宽高；新 `VideoProbeService` 补齐 **fps / codec / container /
bit_rate / nb_frames / has_audio**，并且：

- 失败**发生在任何模型调用之前**（`test_missing_ffmpeg_environment_is_explicit`）；
- 环境缺失（`FFMPEG_MISSING`）与媒体损坏（`DECODE_FAILED`）严格区分；
- 音频流存在性用于后续判断"无语音 ≠ 模块失败"（与转写模块的口径一致）。

---

## 3. 测试结果

```text
python -m unittest discover -s tests -t .
Ran 229 tests in 59.4s
OK
```

本轮新增 34 个用例：

| 分组 | 覆盖 |
| --- | --- |
| FRAME-0 正常路径 | 时长/分辨率/fps/编码/容器/音频、JSON 可序列化、str 路径、50 秒素材独立校验 |
| FRAME-0 异常路径 | 不存在、空文件、目录、损坏容器、纯音频（`UNSUPPORTED_MEDIA`）、无 ffmpeg 环境、错误可被任务层当 `TaskError` 捕获 |
| FRAME-1 | 文件名带时间戳、10s/15s/19s/0 四个时间点、review 类型与后缀、越界 clamp、不同时间点出不同帧、缺工具显式失败 |
| FRAME-2 网格 | 窗口中点与冻结语义一致、末窗短、position 可配、非法输入拒绝、**不依赖 ffmpeg**、45 分钟=270 点 |
| FRAME-2 offset | 默认 0、只过滤不平移、窗口 id 不重排、非零 offset 升版、plan 可 JSON 化 |
| FRAME-2 批量 | 逐点一对一、时间戳递增、**与逐点 seek 等价**、不等间隔拒绝、重复时间戳拒绝、等间隔通过、空输入、真实素材等价性 |

---

## 4. 遗留问题

| # | 问题 | 计划 |
| --- | --- | --- |
| 1 | 采样策略尚未接进 `pipeline.run_job()`（当前 pipeline 仍用自己的 `plan_frames`） | **FRAME-3** 一起切，避免双份网格实现长期并存 |
| 2 | `start_offset/end_offset` 还不能从配置读 | FRAME-3 加配置项，默认 0/0 |
| 3 | workspace 生命周期（成功即删 / 失败 TTL 保留）未实现 | **FRAME-5** |
| 4 | 任务状态机（PROBING_VIDEO → … → AGGREGATING）未落库 | FRAME-5（含 store 迁移） |
| 5 | 逐窗口样本表 `head_up_sample` 未建 | 与 FRAME-5 一起做 SQLite 迁移 |
| 6 | `analyzeWindow([三帧])` 一次多图请求未实现（当前 review 仍是三次单图） | **FRAME-4**（决策③） |
| 7 | 真实 45 分钟视频未到，批量加速比与整节课耗时/成本仍是外推 | 等用户提供视频后补 FRAME-2/FRAME-6 实测 |
| 8 | 批量路径在"关键帧间隔很大"的视频上可能整段回退（有保护但未实测） | 真实视频上观察回退率 |

---

## 5. 验收标准核对

| 验收标准（plan §23 FRAME-0/1/2） | 结果 |
| --- | --- |
| FRAME-0：正常 MP4 可可靠读取元数据；损坏/不存在/无 ffmpeg 均显式失败 | ✅ 11 个用例覆盖四类场景 |
| FRAME-1：`extractFrame(video, timestampMs)`，输出 `frame-{timestampMs}.jpg`，人工可核对 | ✅ 帧精确 seek + 文件名带时间戳；四个时间点实测可抽 |
| FRAME-2：默认每 10 秒、单次 FFmpeg 批量抽帧、时间戳递增、数量合理 | ✅ 网格由 `SamplingPolicy` 生成（45 分钟=270 点）；批量实测与逐点等价 |
| **采样网格只由 SamplingPolicy 生成，FFmpeg 不得自行定义** | ✅ 网格是纯计算；批量接口拒绝不等间隔 |
| **批量只有在证明与旧网格完全兼容后才能启用** | ✅ 首帧等价性校验 + 全采样点对照实测（最大像素差 0.0017）+ 不等价整段回退 |
| **offset 只过滤不平移，默认 0，启用时升版** | ✅ 用例钉住"时间戳不变、窗口 id 不重排"与版本后缀 |
| 不破坏既有口径与测试 | ✅ 229 个用例全绿，冻结样例逐字段一致未受影响 |

**本轮（FRAME-0/1/2）验收通过。** 按要求停在此处，等指示继续 **FRAME-3**（把采样策略接进 pipeline，
把视觉层独立成 `vision/`，并保持严格口径与 REVIEW 语义不变）。
