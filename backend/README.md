# 爱教学后端

> 当前已完成Round 2.1核心14表、Round 2.2固定fixture、Round 2.3认证授权、Round 3.1 HTML纯Parser、Round 3.2事务导入后端、Round 3.3授权CourseOffering列表查询、Round 3.5授权只读详情、Round 3.6多教师比较后端及Round 3.7 Sprint 1前端集成（含lookup集成支撑端点与后端真实HTTP纵向测试）。数据模型见[摘要](../docs/architecture/data-model.md)，解析/导入见[importing.md](../docs/architecture/importing.md)，认证授权见[authorization.md](../docs/architecture/authorization.md)，组合查询见[querying.md](../docs/architecture/querying.md)，详情见[course-detail.md](../docs/architecture/course-detail.md)，比较见[teacher-comparison.md](../docs/architecture/teacher-comparison.md)。生产迁移仍为V1＋V2；真实538行已在一次性H2/MySQL测试库完成导入和匿名列表/详情/比较查询后清理，尚无正式课程映射、Excel Adapter或浏览器E2E证据。

Java 17、Spring Boot 4.0.8、MyBatis Starter 4.0.1、MySQL、Flyway、Spring Security、Validation、Maven。批准依据：[DEC-010](../docs/decisions/decision-log.md)。

## 运行与验证

从项目根目录执行：

```powershell
java -version
mvn -version
mvn -B -ntp -f backend/pom.xml test
mvn -B -ntp -f backend/pom.xml package
```

测试自动启用test profile，H2为test作用域，不需要本地MySQL，不进入生产jar。该测试不证明MySQL业务SQL或数据库迁移兼容性。Maven版本本轮实测3.9.4；未引入Wrapper。

实际启动前提供已有MySQL实例及专用数据库（数据库版本待实际环境核验），通过环境变量配置，不提交口令：

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3306/ai_teaching'
$env:DB_USERNAME = '<数据库用户>'
$env:DB_PASSWORD = '<数据库密码>'
java -jar backend/target/ai-teaching-backend-0.1.0-SNAPSHOT.jar
```

服务端口默认8080。GET /api/v1/system/status与/auth/csrf匿名开放；POST /auth/login、GET /auth/me、POST /auth/logout使用数据库账号和Session Cookie。GET /api/v1/course-offerings提供授权范围内的分页组合查询，GET /api/v1/course-offerings/{offeringId}提供授权只读详情，GET /api/v1/teachers/comparison按1～5个teacherIds提供授权多教师比较，GET /api/v1/lookups/semesters与GET /api/v1/lookups/colleges提供既有学期与来源学院选择来源（GLOBAL全部、其他读取者按授权Offering投影，DEC-023）；这些GET均须登录但不要求CSRF。POST /api/v1/imports/timetable接受HTML multipart、semesterId和sourceScopeCollegeId，须登录、CSRF及ADMIN＋GLOBAL；超过5 MiB的文件返回413及导入错误`FILE_TOO_LARGE`。lookup不创建主数据，生产没有内置账号，其余未实现业务路径默认拒绝。

## 模块边界

- common：错误/分页DTO、Web配置、请求标识及技术状态入口。
- identity：数据库认证、CurrentUser、角色/Scope、权限能力及统一身份异常。
- course：开课授权数据集Mapper/Repository、组合查询、保持Schedule→Week/Teacher/Location关系的只读详情，以及保持Teacher→实际Schedule和完整共授关系的多教师比较API/DTO；前端仍未实现。
- importing：HTML `COURSES`纯提取、Source DTO/校验；事务化Source/Domain写入、幂等/冲突结果与最小上传API。
- resource：历史 V3 数据库结构保留；DEC-028 后不再提供运行时资源/备课模块。
- supervision：督导记录与覆盖职责预留。
- classroom：单次课堂视频、ffprobe可信时长/流探测、双模块持久编排、严格结果契约、短效签名媒体下载及客观报告输入汇合。

业务模块目前只有package-info；按需增加controller/service/mapper/dto，避免无行为的占位类。Controller负责HTTP，Service负责业务和事务，Mapper只处理本模块持久化；模块间不得直接访问对方Mapper，common不依赖业务模块。不拆微服务。

## 通用API约定

应用包内Controller由Web配置统一添加/api/v1前缀，方法映射不要重复添加该前缀。

错误字段为code、message、path、requestId、fieldErrors；字段错误只输出字段名与安全提示，不返回原始拒绝值。框架异常保留HTTP状态/必要响应头；意外异常返回统一500并记录服务端日志。

X-Request-ID仅接收1～64位字母、数字、下划线或连字符；其他输入替换为UUID。同步请求返回同一标识并放入MDC，结束后清理；本轮不引入异步线程上下文传播或分布式链路系统。

PageQuery采用1起始页码，默认20、最大100；offset用long。PageResult返回items/total/page/size，并提供Java侧totalPages计算方法。CourseOffering查询入参使用@Valid，默认按displayName、offeringId稳定排序；用户可选排序规则尚未批准。

Flyway目录：[db/migration](src/main/resources/db/migration/README.md)。V1验证迁移链，V2创建14张核心表；Round 3.2不改schema，直接使用V2结构。clean禁用、自动baseline禁用、schema.sql自动初始化关闭。H2回归与本项目隔离MySQL 8.0.44均执行同一导入集成测试。

## 测试

ApplicationTest覆盖上下文、MyBatis/Flyway、状态入口、请求标识、默认拒绝、字段校验、JSON格式错误及500隐藏内部详情；PaginationTest覆盖分页边界。DataModel、Fixture、Authentication测试验证V2数据结构、固定样例和服务端身份/Scope。

HTML Parser测试覆盖纯解析、字段错误、UTF-8、重复保留和本地538行快照；TimetableImportIntegrationTest覆盖IMP-01～18、最小HTTP API、Source/Domain全关系、幂等、冲突及事务回滚，并在H2与隔离MySQL运行。测试ProbeController、fixture迁移和故障注入实现仅位于src/test，不进入交付jar。以上仍不等于完整DAT-01/02 Story、前端或人工交叉验收完成。

CourseOfferingQueryIntegrationTest复用同一Round 2.2 fixture，在H2与隔离MySQL运行QRY-01～24、API校验、AC-03、授权total、去重分页及真实538行匿名smoke。查询后端不等于QRY前端、DET-01详情、CMP-01比较或人工交叉验收完成。

CourseOfferingDetailIntegrationTest在H2与隔离MySQL运行DET-01～20、列表ID到详情、对象授权、可空Course、来源/归属分离、Schedule关系、离散Week、ONLINE/TBD、`p=0`、大纲状态、固定四查询及真实538行匿名smoke。当前只完成DET-01后端，不包含详情前端、CMP-01或人工交叉验收。

TeacherComparisonIntegrationTest在H2与隔离MySQL运行CMP-01～18、参数/Session、请求顺序、空组、授权交集、Teacher→Offering、Teacher→实际Schedule、完整共授关系、去重、固定两条身份查询＋五条领域查询及真实538行匿名smoke。

Round 3.7新增LookupIntegrationTest（lookup授权与稳定性6项）及Sprint1VerticalAcceptanceE2ETest（RANDOM_PORT后端真实HTTP纵向链路4项：登录/CSRF轮换、导入及幂等、查询与AC-03纵向、详情、五教师比较、401/403/404/413/415/422失败关闭、可选真实538纵向smoke），两者均加入Test-MigrationDatabase.ps1白名单。后者使用Java HttpClient，不执行Vue或浏览器。前端集成说明见[frontend-integration.md](../docs/architecture/frontend-integration.md)，纵向验收见[sprint1-vertical-acceptance.md](../docs/testing/sprint1-vertical-acceptance.md)。仍不包含评分/排名、CMP-02导出、浏览器E2E或人工交叉验收。

## 开发环境与空库验证（DEV-ENV-001）

当前统一首次启动方式见 [根README](../README.md)。本地隔离MySQL 8.0.44已用于真实空库验证；生产数据库版本仍待批准。本轮新增migrate profile及V1技术迁移，早期“仅迁移说明、无SQL、未做MySQL验证”是Round 1.1历史快照。V1不建业务表；ApplicationTest验证V1状态，MigrationModeTest验证无Web迁移与重复执行不重跑。

SERVER_PORT可覆盖端口，FILE_STORAGE_DIR对应app.storage.root预留文件目录；当前没有文件存取实现。使用scripts/Use-DevEnvironment.ps1加载本地变量，根.env示例不会由Spring自动加载。默认状态GET及业务拒绝策略保持不变。
