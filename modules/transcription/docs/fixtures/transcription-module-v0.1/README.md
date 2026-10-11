# 转写模块 fixture（与官方样例等价）

> 版本：契约 v0.1 / wire `schemaVersion=1.0`。
> 对应[公共契约](../../../classroom-analysis-contract-v0.1.md)与[转写接口规范](../../../transcription-module-v0.1.md)。
> 官方样例包见主项目的 `docs/integration/fixtures/classroom-analysis-v0.1/`。

## 这批文件是什么

**本模块真实产出的信封**，供主项目 M2（标准接收）与 M3 使用。

与官方样例的区别只有一处：**字段值来自本模块实现，而不是人工手写**。
主项目可以直接把它们灌进自己的接收端点做回归，因为字段名、可空语义、
错误体形状都经过模块自己的序列化路径。

| 文件 | 用途 |
| --- | --- |
| `transcription-request.json` | 提交请求样例（`POST /api/v1/jobs` 的 body） |
| `transcription-success.json` | 成功信封：`SUCCEEDED` + 完整 payload |
| `transcription-failed.json` | 失败信封：`FAILED` + `error`，payload 为 null |
| `transcription-rejected-422.json` | HTTP 拒绝体（**不是**任务失败，形状完全不同） |
| `module-health.json` | `GET /api/v1/health` 响应，含交接表要登记的能力数值 |

## 怎么生成（可复现）

```powershell
# 需要在仓库根目录执行；会真实下载媒体、核验 sha256、调用 ffmpeg
mvn -B -s .mvn/settings-sandbox.xml test -Dtest=TranscriptionFixtureGenerationTest
```

生成器是 `TranscriptionFixtureGenerationTest`。它**不手写 JSON**，而是：

1. 起真实 HTTP 服务与真实媒体服务器，走真实的契约路由；
2. 媒体下载、sha256 核验、ffprobe 探测、ffmpeg 抽音轨**全部真实执行**；
3. 只有识别引擎被替换成受控替身——真实引擎需要凭证与额度，
   且同一段媒体在不同时间可能给出不同切句，那样产出的 fixture 不可复现；
4. 把模块**真正返回的响应体**原样落盘；
5. 落盘前先用 `ContractInvariants`（官方 `verify_contract.py` 的 Java 移植）逐条自检。

## 怎么校验（用官方校验实现，不是我自己写的断言）

```powershell
# 1) 先拿到主项目的官方样例包，把 verify_contract.py 放到同一目录
# 2) 用官方校验函数验本模块的 fixture
$env:PYTHONIOENCODING='utf-8'
python docs/fixtures/transcription-module-v0.1/tools/verify_module_fixtures.py `
       docs/fixtures/transcription-module-v0.1
```

脚本 import 官方 `verify_contract.py`，直接调用它的
`envelope()` / `association()` / `request_matches()`，再补 8 条负例，
确认"校验器真的会拒绝坏样例"。

**本轮实测结果**（官方原件基线同样是 PASS）：

```
PASS envelope(): 成功与失败信封都满足官方不变量
PASS association(): 成功信封与请求一致；失败信封身份一致且 hash 如实记录为不匹配值
PASS 失败码 MEDIA_HASH_MISMATCH 属于契约任务失败码，且不含媒体 URL
PASS request_matches(): 请求与成功结果逐键对上
PASS 负例被拒: revision 改成 2 / payload=null / error 非空 / segment 越界 /
              segment id 重复 / schemaVersion=2.0 / timeBase 改错 / 失败样例缺 error
ALL PASS
```

## 与官方样例的值对照

结构完全一致（`segments` 的时间与文本刻意对齐官方样例，便于主项目直接对照）：

| 字段 | 官方样例 | 本模块 fixture |
| --- | --- | --- |
| `mediaSha256` | `aaaa…`（占位） | 真实 mp4 的 SHA-256 |
| `durationMs` | 60000 | 60000（与官方同量级） |
| `providerId` / `moduleVersion` | `transcription-demo` / `demo-1.0` | 由部署配置注入 |
| `payload.processedRanges` | `[0,60000)` | 同 |
| `payload.segments` | seg-001/002/003 三段 | 同（时间与文本一致） |
| `payload.warnings` | `[]` | 含一条 `DURATION_MISMATCH` |

## ⚠️ 需要主项目注意的一条：自定义 warning

本模块的成功样例 `warnings` 里有一条官方样例没有的：

```
DURATION_MISMATCH:registered=60000,actual=52208
```

含义：请求登记 `durationMs=60000`，而 ffprobe 探到的真实媒体时长是 52208ms
（测试素材只有 52 秒）。契约要求「真实容差需在 M4 登记核验时处理，
**不在接收时静默裁切**」，因此本模块的做法是：

- 信封的 `durationMs` **仍与请求一致**（不静默裁切）；
- 不做拒绝（容差由双方在 M4 约定）；
- 把差异作为 `DURATION_MISMATCH` 如实放进 `warnings`。

**主项目接收时请把未知 warning 当信息而非错误处理**，否则会拒掉合法结果。
同理，以下与本模块实现相关的 warning 也可能出现：

| warning | 含义 |
| --- | --- |
| `PARTIAL_PROCESSING` | 契约标准码：未覆盖完整 `analysisRange` |
| `NO_SPEECH` | 契约标准码：处理成功但无可转写文本 |
| `DURATION_MISMATCH:registered=X,actual=Y` | 登记时长与实际不符（不裁切，仅如实告知） |
| `SEGMENTS_CLIPPED_TO_RANGE:N` | N 个片段被裁剪到处理范围内 |
| `SEGMENTS_DROPPED_OUT_OF_RANGE:N` | N 个片段因越界或跨未处理间隙被丢弃 |
| `SEGMENTS_DROPPED_BLANK:N` | N 个片段文本为空被丢弃 |
| `EMPTY_PROCESSED_RANGE` | 没有已处理区间，因此不返回任何片段 |

## 这批文件不是什么

- **不是**真实识别质量的证据：识别引擎被替换成受控替身，
  它给出的分句是固定文本，只用于验证协议形状与时间映射。
- **不是**已联签的契约版本：契约仍需三方评审确认（见主项目计划的 M1）。
- `module-health.json` 里的 `providerId`/`moduleVersion` 是本仓库默认配置值，
  真实部署必须由环境变量覆盖并在交接表登记实际值。
