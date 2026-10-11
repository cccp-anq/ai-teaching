# 内置课堂分析模块

`ai_teaching` 直接维护并交付两项课堂识别能力：

- `transcription/`：Java 17 课堂音轨提取、语音转写供应商适配和标准结果封装。
- `headup/`：课堂抽帧、匿名可见学生计数、抬头状态判定和标准结果封装。

这两项能力原先分别位于 `videoreact` 与 `headup` 工作区。2026-09-27 根据用户要求迁入本仓库；运行、构建和配置不得再引用兄弟项目路径。模块继续使用回环 HTTP 契约与主后端隔离耗时任务和故障，但它们属于同一仓库、同一交付物，由 `scripts/Start-IntegratedClassroomAnalysis.ps1` 统一启动。

真实识别仍会访问所配置的云端 ASR、OSS 和视觉模型供应商。这里的“内置”表示不再依赖其他本地项目或其运行进程，不表示把云端模型权重放入 Java 进程。

## 构建与测试

```powershell
mvn test
python -m unittest discover -s modules/headup/tests
```

首次本机配置可复制样例、填写私有凭据和工具路径，再运行初始化脚本：

```powershell
Copy-Item modules/integrated-config.example.json .work/integrated-input.json
# 编辑 .work/integrated-input.json
./scripts/Initialize-IntegratedClassroomAnalysis.ps1 -SourceConfigPath '.work/integrated-input.json'
```

该操作校验配置，把随机内部令牌和签名密钥补齐后写入被Git忽略的`.work/integrated-classroom-analysis.json`，并把ffmpeg/ffprobe复制到本项目`.work/tools/ffmpeg/`。正常运行只读取本项目文件。
