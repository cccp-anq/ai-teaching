# Migration目录

当前最高版本为V20：V1技术链；V2课表核心；V3教学资源；V4督导评价；V5建立课堂媒体、模块任务和客观报告基础；V6增加模块编排元数据、不可变结果和处理区间；V7增加有界失败计数与期限；V8增加上传幂等；V9增加分析运行快照；V10增加付费预留账本；V11增加规范化AI报告；V12增加报告派发；V13增加发送前恢复；V14增加督导确认的AI报告再次生成；V15增加保留旧审计的失败模块任务重建；V16按已批准的DEC-015补齐空值的2026-FALL首周日期；V17将模块结果原始信封扩至MEDIUMTEXT以容纳长课堂结果；V18记录督导恢复已有成功转写的来源与操作类型；V19记录免密登录成功事件；V20约束模块重试操作类型。后续只追加版本，不修改已执行migration；V5～V20实现边界见[课堂分析说明](../../../../../../docs/architecture/classroom-analysis.md)。

Flyway随正常应用启动执行，也可在加载DB环境变量后执行：

```powershell
java -jar backend/target/ai-teaching-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=migrate
```

命令从项目根目录运行。migrate profile不启动HTTP，成功退出0；SecurityFilterChain仅在Servlet应用装配，Web模式原有拒绝策略不变。

V1__initialize_migration_chain.sql仅SELECT 1，用于验证版本发现、执行、历史记录与重复启动不重跑；不创建业务表。业务规则批准后用V2及后续迁移，不修改已执行文件。Flyway维护自己的schema history；baseline-on-migrate关闭，clean禁用，schema.sql初始化关闭。

空库与重复migration验收见根目录scripts/Test-EmptyDatabase.ps1，执行前必须没有业务表或Flyway历史表；不会删表重置。完整运行方式见 [根README](../../../../../../README.md)。
