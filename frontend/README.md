# 爱教学前端

前端实现第一阶段“查课程”及课堂分析入口。当前产品范围依据 [require1.md](../require1.md) 与 [DEC-028](../docs/decisions/decision-log.md#dec-028角色课表督导评价文本录入与备课范围撤销)。

## 环境与命令

实际包管理器为npm 11.11.0，Node.js 24.14.1；工程使用Node 24系列。提交package-lock.json，后续用npm ci复现依赖，不混用pnpm/yarn锁文件。直接依赖精确版本见 [package.json](package.json)。

在项目根目录执行：

```powershell
cd frontend
npm ci
npm run typecheck
npm test
npm run build
npm run dev
```

首次建锁使用npm install，后续安装使用npm ci。dev默认仅监听127.0.0.1:5173；生产产物在dist，使用npm run preview进行本地预览（默认127.0.0.1:4173）。生产托管须将非API的页面路径回退到index.html，以支持history路由刷新；不要把API错误回退为HTML。

现有项目没有批准ESLint/format方案，本轮未引入，也不提供空跑的lint脚本。typecheck覆盖Vue SFC、应用、配置及测试，build先执行typecheck。Vitest使用Node环境和Vue服务端渲染验证页面壳，没有引入浏览器模拟环境或业务数据。

## 目录与页面追踪

| 路径 | 职责 |
| --- | --- |
| src/api/client.ts | 唯一Axios入口、API基础地址、错误标准化与401信号 |
| src/components/ | 通用占位面板 |
| src/layouts/ | 页面导航、主体布局和未登录提示 |
| src/router/ | 命名路由、根跳转及404 |
| src/stores/ | Vue响应式HTTP未登录信号，无身份凭据或权限模型 |
| src/views/ | 业务页面及404页 |
| tests/ | HTTP边界及路由渲染测试 |

| 页面路径 | 页面文件 | 关联原Story（仅预留） | 当前状态 |
| --- | --- | --- | --- |
| /login | src/views/LoginView.vue | AUT-01 | 登录 |
| /my-schedule | src/views/MyScheduleView.vue | QRY-01～03、AUT-01 | 按授课/督导用途展示个人课表；多角色可切换 |
| /offerings | src/views/OfferingsView.vue | QRY-01、QRY-02、QRY-03 | 授权开课查询 |
| /imports | src/views/ImportView.vue | DAT-04 | 文本框＋TXT督导评价录入；不显示课表/备课导入 |
| /offerings/detail/:offeringId | src/views/OfferingDetailView.vue | DET-01 | 开课详情 |
| /comparison | src/views/ComparisonView.vue | CMP-01 | 教师比较 |
| /supervision/coverage | src/views/CoverageView.vue | EVA-01、EVA-02、EVA-03 | 覆盖汇总与下钻 |
| /offerings/:offeringId/classroom-analysis | src/views/ClassroomAnalysisView.vue | 课堂分析规划 | 课堂视频与分析状态 |

旧 `/my-courses` 兼容重定向到 `/my-schedule`。页面均通过统一 API client 请求真实后端，不在前端替代服务端授权。当前有效 18 个故事的映射由 [追踪表](../docs/story-traceability.md)维护。

## HTTP约定与未决事项

业务API调用将统一从src/api/client.ts导入http，返回标准AxiosResponse；失败拒绝为ApiClientError，保留status/code/requestId/fieldErrors。对400/401/403/404/服务异常/超时/断网/取消提供统一安全消息，不直接展示后端任意异常文本；字段错误供后续表单按文本渲染。

基础地址只在HTTP入口配置，默认同源/api/v1，可通过.env.local中的VITE_API_BASE_URL在构建时覆盖（参考 [.env.example](.env.example)）。VITE_*进入公开前端产物，不能存放秘密。当前页面不联网；尚未配置开发代理或跨域凭据，后端地址与联调部署确定后再接入。

401只触发内存未登录提示并提供登录页面链接，保持当前路由；不自动刷新凭据、重试、重定向或保存token。当前没有认证成功路径，提示保留到刷新；后续登录实现需补充会话状态恢复测试。403保持权限拒绝含义，不当作登录失效。页面壳可公开预览，不含受保护数据；真正的列表、详情、比较、导入、文件及统计权限必须由服务端校验。

登录方式、账号来源、CSRF对接、角色/字段权限、导入规则、查询/详情展示和覆盖统计口径继续由 [未决事项](../docs/decisions/open-questions.md)与 [决策门](../docs/decisions/phase1-decision-gate.md)管理。本轮未联调真实登录或业务API、未执行浏览器端到端或人工业务验收；19项骨架测试不能替代Story验收。

## 开发联调增量（DEV-ENV-001）

新增/system技术连接检查页，只通过统一HTTP client请求后端/system/status。该页不是业务Story，其他六页仍为占位。Vite开发服务器使用DEV_API_TARGET将/api代理到后端；变量可来自当前进程或.env.local，默认http://127.0.0.1:8080。上文“未配置代理、所有页面不联网”为前端骨架初始状态，以本节为准；业务页面依旧不请求真实数据。

完整本地数据库、migration、后端与前端联合启动见 [根README](../README.md)。连接检查拒绝HTML回退/无效状态，不把HTTP 200直接判为后端可达。开发代理不随dist发布，生产需单独反向代理。标准命令保持不变，当前新增6项状态接口测试，总25项；真实代理链另由scripts/Test-EmptyDatabase.ps1验证。
