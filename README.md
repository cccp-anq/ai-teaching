# 爱教学 · 第一阶段“查课程”

当前实现覆盖授权课程查询、详情和教师比较、按授课/督导用途区分的“我的课表”、督导评价文本录入，以及覆盖汇总与下钻。评价可直接粘贴常见格式文本，也可上传 UTF-8 TXT 后编辑；课表导入仅保留后端接口。备课数据与教学资源功能已按 [DEC-028](docs/decisions/decision-log.md#dec-028角色课表督导评价文本录入与备课范围撤销) 完整撤销，历史 V3 migration 仅用于维护数据库版本链。因 [require1.md](require1.md) §6.7 要求的非 DRI 人工交叉验收尚未执行，第一阶段仍不宣称 COMPLETE。完整证据见 [PROGRESS.md](PROGRESS.md)。

本机项目隔离数据库已导入用户指定网页中的 2026 年秋季学期、软件学院课表数据；这是 `.work/` 下的本地运行数据，不随 Git 分发。数据库迁移和正式模型说明见[数据模型](docs/architecture/data-model.md)，认证授权见[authorization.md](docs/architecture/authorization.md)。唯一业务基线是 [require1.md](require1.md)，协作规则见 [AGENTS.md](AGENTS.md)，全部资料由 [docs/README.md](docs/README.md)导航。

## 环境

| 工具 | 本轮实测/要求 |
| --- | --- |
| JDK | 17；实测17.0.14，编译release 17 |
| Maven | 3.9.4 |
| Node / npm | Node 24.14.1 / npm 11.11.0；使用Node 24系列与package-lock.json |
| MySQL | 本地隔离开发实例8.0.44，使用已安装原生Windows二进制；生产版本仍待批准 |
| Shell | PowerShell 7；实测7.6.5，脚本基于Windows MySQL，不注册系统服务 |

组件决定见 [DEC-010/011/012](docs/decisions/decision-log.md)。本轮采用原生MySQL隔离启动作为Compose的等价方式，Docker不是前置条件；不启动或修改机器原有MySQL80服务，不引入其他中间件。MySQL安装包需自行安装并将bin加入PATH，或传入-MySqlBin。8.4 LTS仍是旧草案建议，不能把8.0.44本地实测当作8.4/生产验收。

## 快速打开项目

以下命令均从仓库根目录执行。先确认 `java -version`、`mvn -version`、`node --version` 和 `mysql --version` 与上表一致。

首次使用或数据库尚未运行时：

```powershell
# 1. 启动项目专用 MySQL；bin 已在 PATH 时可省略 -MySqlBin。
./scripts/Start-DevDatabase.ps1 -MySqlBin 'D:\MySQL\MySQL Server 8.0\bin'

# 2. 编译后端。
mvn -B -ntp -f backend/pom.xml package

# 3. 首次创建或需要轮换密码时，配置长期本地管理员。
# 密码由安全随机数生成，不显示在终端，仅保存到 Git 忽略的本地文件。
./scripts/Set-LocalAdmin.ps1 -Username local-admin -GeneratePassword -SaveCredential
```

然后分别打开两个 PowerShell 终端。后端终端：

```powershell
. ./scripts/Use-DevEnvironment.ps1
java -jar backend/target/ai-teaching-backend-0.1.0-SNAPSHOT.jar
```

前端终端：

```powershell
. ./scripts/Use-DevEnvironment.ps1
Set-Location frontend
npm ci                 # 首次或 package-lock.json 变化后执行
npm run dev
```

浏览器打开 <http://127.0.0.1:5173/login>，本地测试模式只需输入已有账号用户名，例如 `local-admin`，不需要在页面输入密码。`Use-DevEnvironment.ps1` 显式启用此模式并将后端绑定到 `127.0.0.1`；会话、CSRF 和服务端账号角色/授权范围仍照常工作。此模式只能在显式回环绑定时启动；其他环境默认仍使用用户名和密码。首次创建账号仍可运行第 3 步，该步骤生成的密码只用于账号引导，本地用户名模式登录时不使用；本机凭据文件在 `.work/` 且被 Git 忽略。再次执行第 3 步会轮换该账号密码并保持唯一 `ADMIN/GLOBAL` 授权。

当前工作站的项目隔离数据库另有演示身份：`demo-admin`（管理员）、`demo-director`（主任）、`demo-teacher`（教师）、`demo-supervisor`（督导）。为测试当前开课详情，本机`demo-supervisor`还被授予第120门课的督导范围；该演示数据和授权不随Git分发。登录后的可见数据取决于该用户名在数据库中的实际授权。

日常再次打开项目只需启动数据库，并分别运行上述后端、前端命令；无需重复迁移、导入或创建账号。正常后端启动会自动执行 Flyway 校验及待执行迁移。

Start-DevDatabase创建项目内.work/mysql-dev和独立13306端口，只监听127.0.0.1，禁用MySQL X端口。它先离线初始化数据目录，再通过首次启动init-file设置随机root密码、创建ai_teaching数据库及仅限该库的ai_teaching开发账号。不会读取系统my.ini，不会清空数据库。数据库/账号是基础设施引导；应用表及版本数据只能由Flyway建立。

本地生成的.work/dev-environment.json、mysql-client.cnf以及日志和数据均被Git忽略。密码随机生成且不会打印；不要把.work打包分享。数据库初始化时的“root空密码”提示来自离线初始化阶段；开放连接前init-file会设置随机密码。重复启动保留数据，端口已占用时拒绝操作。默认字符集utf8mb4/排序规则utf8mb4_0900_ai_ci仅为本地空库默认，不批准课程唯一键、大小写匹配等业务语义。

`Verify.ps1` 顺序执行 Maven test/package、npm ci/typecheck/test/build，失败即停止。项目内没有既有CI，本轮只标准化命令，不创建流水线。

`Test-EmptyDatabase.ps1` 只用于全新且完全空的本地开发库验收；它会先验证目标属于本项目且表数为 0。已有迁移或业务数据（包括本机已导入的课表）时会拒绝执行，不会删表或重置数据。

## 配置与migration

配置样例见 [.env.example](.env.example) 和 [frontend/.env.example](frontend/.env.example)。Spring不会自动读取根.env；在每个开发终端加载本地环境：

```powershell
. ./scripts/Use-DevEnvironment.ps1
# 只运行migration，不监听HTTP；成功退出0。
java -jar backend/target/ai-teaching-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=migrate
```

| 环境变量 | 用途 |
| --- | --- |
| DB_URL / DB_USERNAME / DB_PASSWORD | JDBC连接及凭据，由本地脚本生成；真实秘密不入Git |
| SERVER_PORT | 后端端口，默认8080 |
| FILE_STORAGE_DIR | 私有文件目录；课堂视频保存至classroom-media子目录，不暴露磁盘路径 |
| CLASSROOM_VIDEO_MAX_BYTES | 单视频应用层上限，默认1073741824（1 GiB） |
| CLASSROOM_FFPROBE_PATH / CLASSROOM_MEDIA_PROBE_TIMEOUT_SECONDS | 服务端可信媒体探测工具及超时；默认从PATH查找ffprobe、30秒，仓库不复制二进制 |
| CLASSROOM_DURATION_TOLERANCE_MS | 浏览器申报时长与服务端探测时长允许偏差，默认2000毫秒；超出拒绝上传 |
| CLASSROOM_MOCK_MODULES_ENABLED | 默认false；仅演示/测试时设true，内容全部标SIMULATED |
| CLASSROOM_MODULE_INTEGRATION_ENABLED | 默认false；仅当两个内部模块及下列全部配置齐备时启用自动提交/轮询 |
| CLASSROOM_PUBLIC_BASE_URL / CLASSROOM_MEDIA_SIGNING_KEY | 模块可访问的主项目地址与至少32字符的媒体HMAC密钥；生产使用HTTPS，秘密不入Git |
| TRANSCRIPTION_MODULE_BASE_URL / TRANSCRIPTION_MODULE_BEARER_TOKEN / TRANSCRIPTION_MODULE_PROVIDER_ID / TRANSCRIPTION_MODULE_VERSION | 本仓库`modules/transcription`地址、独立Bearer及从health登记的providerId/moduleVersion |
| HEADUP_MODULE_BASE_URL / HEADUP_MODULE_BEARER_TOKEN / HEADUP_MODULE_PROVIDER_ID / HEADUP_MODULE_VERSION | 本仓库`modules/headup`地址、独立Bearer及从health登记的providerId/moduleVersion |
| HEADUP_MODULE_WINDOW_MS / HEADUP_MODULE_DEFINITION_ID | 抬头率请求窗口和双方登记的定义版本；默认10000 / head-up-attendance-qwen-vl-v2（当前模块健康接口登记值；其他部署须显式按health配置） |
| CLASSROOM_REPORT_ENABLED / CLASSROOM_REPORT_API_KEY | 默认false；显式启用后，后台对双模块READY的课堂自动向阿里云提交报告。密钥只放后端进程环境，不放VITE_*或仓库 |
| CLASSROOM_REPORT_BASE_URL / CLASSROOM_REPORT_MODEL | 默认阿里云兼容接口与qwen3.7-plus；仅接受适配器允许的HTTPS地址 |
| CLASSROOM_REPORT_TIMEOUT_SECONDS / CLASSROOM_REPORT_MAX_OUTPUT_TOKENS | 默认30秒 / 1024输出token |
| CLASSROOM_REPORT_CALL_CEILING_CENTS / CLASSROOM_REPORT_TOTAL_CAP_CENTS | 默认单次预留100分、累计预留2000分；是本地保守提交门，不等于阿里云账单硬上限 |
| SESSION_COOKIE_SECURE | 生产默认true；本地加载脚本为纯HTTP设置false，生产HTTPS必须保持true |
| VITE_API_BASE_URL | 前端公开构建变量，默认/api/v1，唯一HTTP入口读取 |
| DEV_API_TARGET | Vite开发代理目标，默认http://127.0.0.1:8080，不作为浏览器公开变量 |

本机统一入口为`./scripts/Start-IntegratedClassroomAnalysis.ps1 -EnableUsernameOnlyLogin`。它构建并启动本仓库的转写模块（18082）、抬头模块（18083）和主后端（8080），自动核对两个健康身份；运行过程不读取兄弟项目或调用其进程。私有配置放在被Git忽略的`.work/integrated-classroom-analysis.json`，结构参考`modules/integrated-config.example.json`。两个Bearer分别生成并只进入对应模块和主后端进程。

启动两个模块并加载上述环境变量后，先执行只读预检：

```powershell
./scripts/Test-ClassroomModules.ps1
```

脚本只访问两边的`GET /api/v1/health`，核对运行中的moduleType、providerId、moduleVersion、协议版本、headup definitionId及Bearer就绪状态，不提交分析任务、不下载媒体、不调用付费模型。主项目对每个新任务也会在生成签名媒体地址前执行同样的健康身份与能力检查；错连服务、登记值漂移或媒体超过模块声明上限时失败关闭。

### 启用阿里云课堂报告（本地开发）

报告worker默认关闭。统一启动脚本会从本项目私有集成配置读取报告地址、模型和API key；配置完整时随主后端启用：

```powershell
./scripts/Start-IntegratedClassroomAnalysis.ps1 -EnableUsernameOnlyLogin
```

启动脚本本身不发起识别或报告请求，也不打印密钥。配置仅在进程启动时读取；变更后需重新运行启动脚本。

启用后，**已有待处理队列也可能自动外发并计费**。正常新上传要等两模块完成，客观结果为`EXTERNAL READY`后才会派发报告；页面可查看排队、处理中、待督导处理或已生成状态。每个run最多一次HTTP提交，提交状态不确定或草稿校验失败不会自动重试，须由该课督导按页面提示决定。当前外发仍经过既有白名单/规则去标识包，不发送原视频；本轮不改其规则。长课时证据包超限时会从转写和观测窗口按时间抽样，保留所选证据ID与抽样局限；完整历史快照和全量统计不变。单条证据仍过大时会在发送前拒绝，抽样报告也不能代替全程逐段分析或长视频真实联调。真实调用前请检查凭证、费用与内容外发约束；零付费回环测试及一次性合成证据付费探针见[测试说明](docs/testing.md)。

修改端口时同一终端同时设置SERVER_PORT与DEV_API_TARGET，或修改仅本机的.work/dev-environment.json并重新加载；不要修改样例为真实凭据。VITE_*会打进公开产物，禁止放秘密。本地JDBC明确禁用TLS并允许公钥获取，仅用于回环隔离开发；生产应另行配置TLS及凭据策略，不直接复制开发连接串。

迁移文件在 [backend/src/main/resources/db/migration](backend/src/main/resources/db/migration/README.md)，当前最高V20。正常后端启动先运行Flyway；失败会阻止应用启动。已执行SQL不得改写；clean、自动baseline和schema.sql初始化均关闭。

登录后从开课查询进入详情，在“课堂视频与AI分析”选择排课、实际日期及MP4/WebM视频。该课督导或持有效`ADMIN/GLOBAL`授予的管理员可上传；管理员还可查看全部授权开课的课堂AI分析与报告，但报告/失败模块的再次生成仍须该课督导确认。前端为一次上传生成并在同参数重试时复用 `Idempotency-Key`；服务端只创建一份课堂、媒体和双模块任务，同键改传其他内容会返回409。首次创建前服务端用ffprobe复核容器时长、视频流和音轨，浏览器时长只作申报校验，数据库及两模块统一使用服务端时长。上传会保存视频并自动建立两个模块任务；模块未配置时页面显示“等待外部模块”，配置就绪后后台会自动排队。模块终态失败时不会自动产生新调用；两项任务结束后，该课督导可确认可能额外计费并重建失败任务，旧审计保留。如需验证模拟闭环，可仅为当前演示进程设置CLASSROOM_MOCK_MODULES_ENABLED=true；页面会标明均为模拟数据。

已有开发数据时可运行 [scripts/Test-MigrationDatabase.ps1](scripts/Test-MigrationDatabase.ps1)，在本项目运行中的隔离MySQL内另建随机空测试库。默认验证DataModelTest；可用`-TestClass FixtureIntegrationTest`或`-TestClass AuthenticationIntegrationTest`运行固定数据/授权验证。测试库保留，重复调用会新增测试库；运行条件见[测试说明](docs/testing.md)。

## 启动与联调

终端一（根目录）：

```powershell
. ./scripts/Use-DevEnvironment.ps1
java -jar backend/target/ai-teaching-backend-0.1.0-SNAPSHOT.jar
```

终端二（根目录）：

```powershell
. ./scripts/Use-DevEnvironment.ps1
cd frontend
npm run dev
```

访问 <http://127.0.0.1:5173/login> 并使用上文的本地管理员登录；系统状态页为 <http://127.0.0.1:5173/system>。Vite 将 `/api` 代理到后端，无需放开 CORS 或业务权限。`GET /api/v1/system/status` 返回 `UP` 只证明后端可达，不等于数据库健康、认证成功或业务就绪；代理不能绕过服务端权限。

正常结束前后端使用各终端Ctrl+C；停止隔离数据库使用：

```powershell
./scripts/Stop-DevDatabase.ps1
```

停止脚本先核对服务端datadir属于本项目再执行shutdown，保留数据。下次再次执行Start-DevDatabase即可启动原数据；不提供自动删除/清库命令。

## 标准验证命令

根目录执行：

```powershell
mvn -B -ntp -f backend/pom.xml test
mvn -B -ntp -f backend/pom.xml package
cd frontend
npm ci
npm run typecheck
npm test
npm run build
```

等价一键入口：./scripts/Verify.ps1。后端默认自动化测试用H2，真实MySQL空库验收由Test-EmptyDatabase完成；两者不等价。生产静态产物在frontend/dist，需配置非API路径的index.html回退以及/api反向代理；Vite开发代理不会被打包进dist，也不用于生产部署。实际测试记录见 [PROGRESS.md](PROGRESS.md)。

## 常见故障

- 无法找到工具：核对PATH与版本，重新打开终端；PowerShell脚本执行策略受组织管理时遵循本机策略，不全局解除限制。
- 13306/8080/5173被占用：选择空闲端口并同步配置；不要停止不属于本项目的进程。Start-DevDatabase的Port必须与已保存配置一致。
- 初始化失败：查看.work/mysql-init.log或mysql-dev.log；已有数据目录但无配置时脚本拒绝重建，先检查并保留现场，不覆盖机器原有库。
- 数据库密码错误或DB_URL缺失：在当前终端重新加载Use-DevEnvironment；Java进程只继承启动时环境。
- Flyway校验失败：检查版本SQL是否被改动、当前连接库是否正确；不要开启baseline/clean掩盖问题。
- /api返回HTML或连接失败：确认后端已启动、DEV_API_TARGET端口正确，重启Vite；连接检查会拒绝SPA HTML的假成功响应。
- 401/403：业务入口默认拒绝是当前状态，不通过permitAll或关闭CSRF修复。
- npm提示根目录无package.json：切换frontend；Maven使用-f backend/pom.xml。
- Docker daemon不可达：本次原生等价方案不依赖Docker；没有把容器启动标为已验收。
