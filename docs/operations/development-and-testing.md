# 开发与测试

本文面向修改本仓库的开发者：准备工作站、跑日常开发循环、按意图选择质量检查，并说明 E2E、
可靠性、性能、供应链和 NAS 自迭代的操作方式。Fat JAR、Compose 栈和生产拓扑见
[部署与运行](deployment.md)；跨模块边界和全局不变量见[系统设计](../system-design.md)。

## 前置条件

| 工具 | 用途与约束 |
| --- | --- |
| JDK 21 | 所有 Maven 命令显式使用 `JAVA_HOME_21`；根 POM 的 `maven.compiler.release` 是 `21` |
| Maven | 通过 `mvn` 可用；[`scripts/dev/app.sh`](../../scripts/dev/app.sh)、[`scripts/dev/verify/e2e/lib.sh`](../../scripts/dev/verify/e2e/lib.sh)、[`scripts/dev/verify/supply-chain/run.sh`](../../scripts/dev/verify/supply-chain/run.sh) 优先取 `JAVA_HOME_21`，否则取 `JAVA_HOME`，检查 java 可执行但不检查版本号，调用方须确保为 21；[`regression.sh`](../../scripts/dev/verify/reliability/regression.sh) 只接受 `JAVA_HOME_21` 并校验版本 |
| Node 与 npm | Frontend 依赖由 [`package-lock.json`](../../frontend/package-lock.json) 固定；`distribution` profile 会自动安装 Node `v24.14.0` 与 npm `11.9.0` |
| Docker 与 Compose v2 | 本地栈、测试栈、性能基线和镜像扫描需要 |
| `curl`、`jq`、`lsof` | [scripts/dev/app.sh](../../scripts/dev/app.sh) 的启动硬依赖；端口与健康探测使用 lsof/curl |
| Python 3 | E2E 与测试栈的 smoke 脚本 |

数据库与服务由容器提供：[deploy/local](../../deploy/local/README.md) 覆盖主要本地路径，
[deploy/test](../../deploy/test/README.md) 提供隔离离线栈。开发循环默认连接 PostgreSQL，
因此先准备可丢弃的数据库与 S3。不要不加区分地把两个栈都启动：
本地栈默认数据库是 `kk_studio`，test 栈是 `canvas_test`，而宿主 `e2e` profile 默认连接
`127.0.0.1:5432/kk_studio_e2e`；还需按 profile/环境配置实际连接项，启动依赖不等于已经创建所需数据库。

## 日常开发循环

[scripts/dev/app.sh](../../scripts/dev/app.sh) 是 Backend 与 Vite 的统一入口：

```bash
./scripts/dev/app.sh start
./scripts/dev/app.sh status
./scripts/dev/app.sh logs all
./scripts/dev/app.sh stop
```

| 项 | 默认值 |
| --- | --- |
| Backend | `http://127.0.0.1:18080`，`SPRING_PROFILES_ACTIVE=e2e` |
| Frontend | `http://127.0.0.1:5173` |
| 工作目录 | `runtime/dev`，可由 `DEV_WORK_DIR` 覆盖 |
| Backend log / JAR | `runtime/dev/backend.log`、`web/target/kk-studio-web-1.0.2.jar` |

`start` 会先执行 stop 流程、检查端口占用、按需用 Maven 打包 Backend、按需安装前端依赖，等
Backend API ready 后再启动 Vite；`restart` 等价于 `stop` 后再 `start`，`logs` 与 `tail` 接受可选
目标 `backend`、`frontend`、`all`。

默认 `DEV_KILL_PORTS=true`：`start`、`restart` 和 `stop` 都会终止 dev 端口上的**任意监听进程**，
不局限于本脚本启动的进程，温和终止后仍存活会强杀。运行前确认端口归属；
不允许清理其他进程时设 `DEV_KILL_PORTS=false`，端口冲突将由启动检查报错。
PID 文件也应只属于当前受管实例，不要复用其他工作区的运行目录。

可用环境变量：

| 变量 | 默认 | 含义 |
| --- | --- | --- |
| `BACKEND_HOST` / `BACKEND_PORT` | `127.0.0.1` / `18080` | Backend 监听地址与端口 |
| `FRONTEND_HOST` / `FRONTEND_PORT` | `127.0.0.1` / `5173` | Vite 监听地址与端口 |
| `SPRING_PROFILES_ACTIVE` | `e2e` | Backend profile |
| `DEV_WORK_DIR` | `runtime/dev` | log 与 PID 目录 |
| `DEV_KILL_PORTS` | `true` | stop 流程终止 dev 端口的任意监听者，start/restart 也调用它；false 只停止 PID 文件管理的进程 |
| `DEV_SKIP_PACKAGE` | `false` | JAR 与 `web/target/.kk-studio-revision` 记录的修订都匹配当前 `HEAD` 时跳过 Maven |
| `DEV_SKIP_NPM_INSTALL` | `false` | 完全跳过前端依赖安装或刷新 |
| `DEV_READY_TIMEOUT_SECONDS` | `90` | 等待 Backend/Vite ready 的预算，超时打印日志并以非零状态退出 |
| `JAVA_OPTS` | 空 | 传给 Backend JVM |
| `SHARED_PREVIEW_ENV_FILE` | 未设置 | 显式给出时按字面量读取外部数据面配置；见[本机 preview 的外部数据面](#本机-preview-的外部数据面) |

`e2e` profile 启用时，宿主同步器会按需读取 Google、OpenAI Responses、MiniMax Anthropic 和
DeepSeek 四组完整 credential pair，经 backend API 写入 E2E database 中对应的 seed Provider
row。Backend、Vite 和 Daemon 长驻进程都显式移除 `TEST_*` 变量，这些变量只向短生命周期的同步器
透传；credential 不进入 seed SQL/resource，密钥与 endpoint 不打印。

要让本机 Backend 连 NAS 上已有的 PostgreSQL/S3，用 [scripts/dev/shared-preview.sh](../../scripts/dev/shared-preview.sh)
代替 `scripts/dev/app.sh`：它固定 `prod` profile、关闭 Flyway 与 Harness worker，并从一份 owner-only
配置文件读取数据面 endpoint 与凭据；完整契约与配置键见下文
[本机 preview 的外部数据面](#本机-preview-的外部数据面)。

Vite 默认只监听 `127.0.0.1` 并使用自带 Host allowlist。需要容器或远程开发时由调用方用
`--host` 显式覆盖，仓库默认不向全部网卡开放。

## 按意图选择检查

| 意图 | 命令 |
| --- | --- |
| 只做 Java 静态与格式检查 | `env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp validate` |
| 编译 main/test 源码，不执行测试 | `env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp test-compile` |
| 跑 Java 单元与集成测试 | `env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp test` |
| 触发关键类覆盖率门禁 | `env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp verify` |
| 只格式化本次改动的 Java 文件 | `env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp -pl <module> spotless:apply '-DspotlessFiles=<file-regex>'` |
| 前端单元测试 / lint / 类型与构建 / 覆盖率 | `npm --prefix frontend run test`、`run lint`、`run build`、`run coverage` |
| 校验 Compose 配置 | `docker compose -f deploy/local/compose.yaml config --quiet` 等，见下文 |
| 隔离栈 smoke | `./scripts/dev/verify/smoke/offline-chat.sh`；`--with-app` 增加应用契约，见 [deploy/test](../../deploy/test/README.md) |
| 免费 API 契约矩阵 | [`./scripts/dev/verify/e2e/run.sh`](../../scripts/dev/verify/e2e/run.sh) |
| 确认矩阵有哪些 case | `./scripts/dev/verify/e2e/run.sh --list`、`./scripts/dev/verify/e2e/run.sh --docs` |
| 文档与敏感数据门禁 | `node scripts/dev/verify/repository/check.mjs`、`python3 scripts/dev/verify/repository/check-sensitive-data.py` |
| 可靠性确定性回归 | `./scripts/dev/verify/reliability/regression.sh --iterations 1` |
| 离线性能基线 | `./scripts/dev/verify/performance/run.sh` |
| 供应链 SBOM / 漏洞门禁 | `./scripts/dev/verify/supply-chain/run.sh all` |

真实 Provider、真实 Tool、UI、分布式和镜像扫描都只在显式开关下运行，默认路径不产生模型费用。

日常改动先做静态检查与受影响模块的编译/定向测试，再按风险选择更接近真实环境的验证。
免费只表示不调用付费模型，不表示无副作用：E2E 会写测试数据，离线 smoke/性能入口会建镜像、
启停容器并删除同名测试栈卷；供应链除离线 `test` 子命令外还可能下载依赖、漏洞库或镜像。

内置工具的定向测试、平台条件与报告判断分别见
[Read](builtin-read-tests.md)、[文件变更](builtin-mutation-tests.md)、[检索](builtin-search-tests.md)、
[Bash](builtin-bash-tests.md)、[LSP](builtin-lsp-tests.md) 和 [Task/Thread Join](builtin-task-tests.md)。

E2E 自身的 L1–L5 是 API case 的 level 分组，含义见下文 E2E 章节。

### CI 验证与镜像发布

`docker-publish` 在 `main` 上先执行仓库、Unix 与 Windows 安装器门禁再发布；`dev` push 默认只构建发布，
不会执行这些门禁，因此不能把 dev 镜像构建成功当作测试通过。需要在合入 main 前运行完整 CI 时，
先把待验证提交推送到目标分支，再显式运行不发布模式：

```bash
gh workflow run docker-publish.yml --ref dev -f validate_only=true
```

该模式运行同一套 Java、前端、脚本、文档和敏感数据检查，以及 Linux、macOS、Windows 安装器回归，
但整个镜像发布 job 会被跳过；它与发布使用独立并发组，不会取消同分支正在进行的镜像发布。
前端必须通过 lint、覆盖率、生产构建与 Chromium 离线布局回归；任一失败均阻断发布。
无论门禁是否成功，`frontend-diagnostics` artifact 都保留覆盖率与布局诊断产物 7 天。
Windows 两个 host 都会报告结果，前一个失败不会遮蔽后一个；任一失败仍阻断正常发布。
Unix 矩阵在 Ubuntu 与 macOS runner 上使用系统 `/bin/bash`，macOS 明确检查 Bash 3.2，
不以 Homebrew Bash 的结果替代系统自带版本。Daemon Release 发布也依赖同一套三平台安装器门禁。

数据库维护脚本的集成测试使用独立的 PostgreSQL 17 容器。CI 显式安装 17 版客户端并将其
bin 目录置于 PATH 首位，不依赖 runner 默认版本。本地运行 [scripts/ops/tests](../../scripts/ops/tests) 时，
`psql`、`pg_dump`、`pg_restore`、`createdb`、`pg_isready` 必须来自同一主版本且不低于 17；
测试在创建容器前检查工具链，不兼容时直接失败，不跳过数据库回归。

### Daemon 安装脚本回归

在仓库根运行 `python3 -m unittest discover -s scripts/daemon/tests -v`，覆盖安装与发布脚本契约。
Unix fixtures 替代网络与服务管理命令，真实执行 SHA 校验、文件替换与终端交互，
验证 latest/固定版本、预检失败保留安装、升级复用配置、受管身份与无 checkout 安装。
测试不注册真实 systemd/launchd 服务。
Windows 原生验收要求 JDK 21 在 PATH 上，并分别运行两个 host：

```powershell
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/daemon/tests/test_daemon_install_windows.ps1
pwsh -NoProfile -NonInteractive -File scripts/daemon/tests/test_daemon_install_windows.ps1
```

该套件检查真实 ScheduledTasks 定义、ACL、Java argv 和进程捕获，但不注册任务。
应用参数经安装器 serializer 与生产 `DaemonArguments.decode` 往返，
验证中文、emoji、空参数、引号和尾随反斜杠保真；同时覆盖非零退出、双流大输出与选项环境恢复。

只有 Linux/macOS 时，可用 PS7 与 PATH 上的 JDK 21 运行
`pwsh -NoProfile -NonInteractive -File scripts/daemon/tests/test_daemon_install_windows.ps1 -ProcessOnly`；
Python 入口在发现 `pwsh` 时也会运行这一子集，设置 120 秒超时以发现管道死锁。它只提供可移植
进程行为证据，不代替 Windows PowerShell 5.1 / PowerShell 7 的原生验收；镜像与 Daemon 发布 CI
保留两个 Windows host 的完整门禁。

## Java 质量检查

### Spotless

`validate` 阶段执行 Spotless Maven Plugin `2.43.0`：Google Java Format `1.18.0`（`GOOGLE` style）、
`removeUnusedImports`，import order 为 `#,,fun.fengwk.kkstudio,javax,java`。

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp spotless:check
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp -pl <changed-module> spotless:apply '-DspotlessFiles=.*(Foo|Bar)\.java'
```

`spotless:apply` 会修改源码；`<changed-module>` 限定变更模块，`spotlessFiles` 用正则进一步限定
实际改动的文件。示例匹配 `Foo.java` 与 `Bar.java`，不是逗号分隔的 glob；同名文件较多时应包含包路径。
只指定模块仍会格式化该模块全部 Java 文件，不用于普通开发切片。

### Checkstyle

`validate` 阶段执行 Checkstyle `3.3.0`，读取根目录 [checkstyle.xml](../../checkstyle.xml)，包含
test source 且 `failsOnError=true`。规则集中在两类：代码体内使用 import 而不是全限定类名；
`if`、`else`、`for`、`while`、`do` 等控制流必须带 braces。

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp checkstyle:check
```

### JaCoCo

根 [pom.xml](../../pom.xml) 的 JaCoCo 为测试 JVM 注入 agent，在 `test` 阶段生成各模块的
`target/site/jacoco/`。配置了 `check` 的模块在 `verify` 阶段执行覆盖率门禁；
检查范围、CLASS/BUNDLE 口径与阈值以对应模块 POM 为准，定向测试报告只代表选中的集合。

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp -pl web -am verify
find . -path '*/target/site/jacoco/index.html' -print
```

关键逻辑改动查看本次报告的行覆盖率与未覆盖路径，分支覆盖率作为参考；
低于模块配置的门禁会使 `verify` 失败。不要把静态检查、跳过测试或其它平台的结果算作本次覆盖。

### Fat JAR

根 POM 的 reactor 当前是 [`share`](../../share)、[`schema`](../../schema)、[`canvas`](../../canvas)、
[`project`](../../project)、[`harness`](../../harness)、[`platform`](../../platform)、
[`plugins`](../../plugins)、[`web`](../../web)。需要可运行产物时：

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp -Pdistribution -pl web -am clean package
```

`distribution` profile 在 `prepare-package` 安装 Node 与 npm、对 [`frontend/`](../../frontend/) 执行
`npm ci` 与 `npm run build`，并把产物打进 `BOOT-INF/classes/static`；普通 `mvn test` /
`mvn package` 不激活它。提取并加载 convention4j agent 的启动命令见
[构建可运行产物](deployment.md#构建可运行产物)。

## Frontend 检查

脚本绑定见 [frontend/package.json](../../frontend/package.json)：

```bash
npm --prefix frontend ci
npm --prefix frontend run test
npm --prefix frontend run lint
npm --prefix frontend run build
npm --prefix frontend run coverage
```

| 命令 | 行为 | 结果 |
| --- | --- | --- |
| `run test` | `vitest run` | jsdom 单元/组件测试 |
| `run test:layout` | Playwright Chromium 离线组件回归 | 图片尺寸与窄屏约束、Debug 预览、`/agent` 模型联动及无效配置保护；不依赖 Backend，产物在 `reports/layout/` |
| `run lint` | `eslint .` | TypeScript、React hooks、分层 import 规则 |
| `run build` | `tsc -b && vite build` | strict type-check + Vite production bundle |
| `run coverage` | `vitest run --coverage` | v8 text/html 报告与阈值门禁 |

[vite.config.ts](../../frontend/vite.config.ts) 定义 coverage include、exclude 与各项阈值，
报告目录是 `frontend/coverage/`。[test-setup.ts](../../frontend/src/test-setup.ts) 为每个测试清空
localStorage、固定 `zh-CN`，并为 ResizeObserver、DOMMatrix、SVG geometry、Canvas 2D、dialog、
scrollIntoView 和 React Flow layout 提供确定性 stub。

布局回归需要先在 `frontend/` 执行 `npx playwright install chromium` 安装浏览器。
用例位于 `frontend/browser-tests/*.pw.ts`，与 Vitest 的组件测试分开运行。
布局基座采用独立静态构建与预览机制（[`vite.layout.config.ts`](../../frontend/vite.layout.config.ts)），
通过 Rollup 多页（MPA）模式将真实 React 组件与 8 个测试 harness 预编译至 `reports/layout-site/`。
`npm run test:layout` 先执行 `build:layout`，成功后启动 Playwright；编译不占用服务器的启动预算。
Playwright 通过 `preview:layout` 在 loopback 5174 端口启动静态预览
（`strictPort: true` 且 `reuseExistingServer: false`，以真实 harness URL 进行 HTTP readiness 探测），
消除 dev HMR、实时转译与共享缓存依赖。调试时可在 `frontend/` 单独执行 `npm run build:layout`
和 `npm run preview:layout`，直接通过浏览器访问 `http://127.0.0.1:5174/browser-tests/<harness>.html` 检查页面。
首轮失败时，`reports/layout/` 下保留 `trace.zip` 和 `test-failed-*.png`，不依赖重试。
在 `frontend/` 执行 `npx playwright show-trace <trace-file-path>`，可检查时间线、DOM 快照、
网络请求与控制台报错；trace 路径相对此目录通常以 `../reports/layout/` 开头。

改动前端如果影响 API 契约、首发顺序或 usage 语义，需要同步更新 E2E 矩阵 case 与相关文档；精确
case inventory 由 `node scripts/dev/verify/e2e/run-matrix.mjs --list` 与 `--docs` 提供，不在文档里复制。

Catalog 的免费 L1 模型生命周期用例覆盖 `protocolOptionsJson` 在创建、读取、更新中的文本保真，
包括大整数与高精度小数；配置矩阵覆盖非法 JSON、重复键和非字符串 token 的拒绝。
这些用例只操作测试 Catalog，不调用真实模型；执行仍需可用的隔离 Backend、数据库与 S3。

异步 `task` 的工具结果只表示已接受；完成结果由 Runtime 在子执行首次 Idle 匹配 join 后，作为父 Thread
的一条 `CUSTOM_MESSAGE` 交付——wire 是 USER 角色、正文包在 `<system-reminder>` 中的
系统提醒形态（`SystemReminder.message`），内层唯一形状为
`<subagent_result thread_id agent state>`。
`real.task_delegation` 分别验证 JSON 受理收据与同一子 Thread 的完成消息，不能将受理当作完成；
它还断言子 Thread 的不可变执行父关系指回发起方（`HarnessThreadDTO.parentThreadId`），且子 ROOT
payload 只含 settings、不物化任何委派运行树元数据。
受理卡片的 Thread 链接进入 `/threads/:threadId`，复用独立 Thread 面板查看进度并处理工具审批，
不依赖 Chat 归属，也不开放无 owner 的会话命令发送。
[`ThreadWorkspacePage.test.tsx`](../../frontend/src/features/ai/thread/ThreadWorkspacePage.test.tsx)
通过真实组件交互验证子 Thread 的允许/拒绝审批目标，并覆盖非法 ID、加载失败与返回入口；
这些是 jsdom 回归，不替代真实浏览器验证。

真实 `read` 工具的 L4 用例 `tool.read_turn` 需要 `--real --with-tools --with-canvas-storage`，
断言非 YOLO tool turn 的审批链路，以及 durable
`tool_result` 内联的完整 `path`/`ends_with_newline`/`range` 投影。read 文本窗口恒在终态链路为 read
身份加宽的内联预算（320 KiB / 2020 行）内，因此不产生 resource 预览；工具结果外部化到全局 Blob 与
session 归属由 platform 集成测试守卫，E2E 没有真实模型 tool→blob 端到端证据。

## Compose、静态资源与文档门禁

```bash
docker compose -f deploy/local/compose.yaml config --quiet
docker compose -f deploy/test/compose.yaml config --quiet
docker compose -f deploy/test/compose.yaml --profile app config --quiet
docker compose -f deploy/reliability/compose.yaml config --quiet
./scripts/dev/verify/e2e/distributed.sh verify
```

[`scripts/dev/verify/e2e/distributed.sh verify`](../../scripts/dev/verify/e2e/distributed.sh) 只静态校验双节点 Compose config 与网络不变量，不启动容器。
[`scripts/dev/verify/smoke/offline-chat.sh`](../../scripts/dev/verify/smoke/offline-chat.sh) 把配置检查、镜像构建、常驻依赖 health、非 root runtime、PostgreSQL、一次性
`minio-init` bucket 初始化与 HTTP mock smoke 组合成一个可清理入口；不启用 app 时只等待常驻依赖 `healthy`，再用
`compose run --rm` 执行初始化。`--with-app` 验证全局 Blob、Canvas revision 与 typed command
变化集、signed GET、fake Function、容器内 OpenCLI fake Hub 与离线 Chat。
Chat command batch 使用 `owner.type=CHAT` 与 `owner.chatId`；画布使用 `CREATE_NODE`、
`SET_NODE_FUNCTION` 和语义组前置条件。完整检查与清理语义见 [deploy/test](../../deploy/test/README.md)。

Fat JAR 的 static 资源检查由 `-Pdistribution` 的三个插件完成；应用在 `/actuator/health` 通过后
再检查浏览器入口。文档、敏感数据与 Git 空白检查：

```bash
node scripts/dev/verify/e2e/run-matrix.mjs --docs
node scripts/dev/verify/repository/check.mjs
python3 scripts/dev/verify/repository/check-sensitive-data.py
git diff --check
```

[scripts/dev/verify/repository/check.mjs](../../scripts/dev/verify/repository/check.mjs) 负责固定文档布局、Markdown 链接、H1、源码
路径和旧词守卫。[check-sensitive-data.py](../../scripts/dev/verify/repository/check-sensitive-data.py) 扫描
tracked 文件与非 ignored 未跟踪文件，覆盖高置信密钥、Webhook、个人绝对路径和已知私有环境标识；
命中时只输出规则与 `path:line`，不要回显完整敏感值。该入口不扫描 Git 历史，历史审计是公开策略中
的独立步骤。

## E2E

标准入口是 [scripts/dev/verify/e2e/run.sh](../../scripts/dev/verify/e2e/run.sh)：

```bash
./scripts/dev/verify/e2e/run.sh
./scripts/dev/verify/e2e/run.sh --rebuild
./scripts/dev/verify/e2e/run.sh --real
./scripts/dev/verify/e2e/run.sh --with-tools
./scripts/dev/verify/e2e/run.sh --real --with-branch
./scripts/dev/verify/e2e/run.sh --with-canvas-storage
./scripts/dev/verify/e2e/run.sh --with-canvas-function
./scripts/dev/verify/e2e/run.sh --real --with-tools --with-canvas-storage
./scripts/dev/verify/e2e/run.sh --distributed
./scripts/dev/verify/e2e/run.sh --ui
./scripts/dev/verify/e2e/run.sh --only CASE_ID
./scripts/dev/verify/e2e/run.sh --level L1
./scripts/dev/verify/e2e/run.sh --list
./scripts/dev/verify/e2e/run.sh --docs
```

| Flag | 语义 |
| --- | --- |
| `--rebuild` | Java 21 clean package 并重启 backend/frontend；带 tools 时也处理 Daemon |
| `--real` | 启用真实 Provider case，要求四组完整 `TEST_*_BASE_URL` / `TEST_*_API_KEY` |
| `--with-tools` | 启用 Daemon/Tool case |
| `--with-branch` | 启用 branch case，并自动打开 `--real` |
| `--with-canvas-storage` | 启用 Canvas Resource/Blob contract，backend 必须有 S3 配置 |
| `--with-canvas-function` | 启用 fake Canvas Function，隐含 storage、rebuild 与 `KK_STUDIO_CANVAS_FUNCTION_FAKE_ENABLED=true`；与 `--ui` 组合时同时启用 Canvas 真实链路 UI 用例（编辑保存、重开读回、fake 运行产出资源、跨节点引用后再运行），该用例只绑定 `fake-image`，不会提交付费 Function |
| `--distributed` | 启停 [deploy/distributed](../../deploy/distributed) 双节点 mock topology，不与 `--real`、`--with-tools`、`--with-branch`、`--ui`、`--with-canvas-*` 组合 |
| `--ui` | 在 API 矩阵后执行 Playwright UI 矩阵，截图并入同一 run |
| `--only CASE_ID` | 只运行指定 case，可重复 |
| `--level L1\|L2\|L3\|L4\|L5` | 过滤 API level，可重复；UI 不受此过滤器影响 |
| `--list` / `--docs` | 只列出 API case，或只打印 case 标题、requires 与契约文档 |

矩阵分成 L1 免费 API 契约、L2 真实文本与推理、L3 真实分支、L4 Environment/Tool/approval、L5
双节点分布式，外加独立的 UI 矩阵。每个 level 的 case 数、标题与 `requires` 只由
`node scripts/dev/verify/e2e/run-matrix.mjs --list` 与 `--docs` 生成，不要把它们抄进文档；默认执行哪些 case
由 flag 组合和 case 的 `requires` 共同决定。

免费 L1 覆盖的部分产品契约面：

- `config_sync.inventory_contract` / `config_sync.provider_roundtrip_same_name` /
  `config_sync.environment_identity_and_token`：七类配置清单、凭据随 YAML 导出、
  同名 Provider 更新、Environment 身份与注册令牌恢复，以及成功和错误响应的 `no-store`。
  三个 case 均无真实模型或 tool 成本；完整 Git/MCP 准备与整批事务回滚另由 Web 集成测试覆盖。
- `project.issue_lifecycle`：Project workflow JSON 与设置 CAS、Issue 按 workflow `next`
  白名单流转、BLOCKED 专用阻塞/恢复、pause(UNKNOWN)/resolve-unknown/resume 门禁、COMMENT
  幂等与「无活动 Run 不得投递 INSTRUCTION」、Activity 有界窗口分页与 snapshot 投影。
- `project.issue_stage_budget`：阶段额度只能授权给启用且有 Agent 的工作阶段，首次
  `budget-reset` 即授权、重放精确、CAS 过期 409、高水位不回退。
- `canvas.command_revision_contract` / `canvas.command_conflict_contract`：11 种 typed
  command、`revision` 坐标与 patch 变化集、批量前置条件过期时整批 409 不写入。
- `interaction.pending_input_contract`：内置 `ask_user` 冻结出 WAITING_INPUT 后，统一
  `GET /api/interactions` 与 `POST /api/interactions/{id}/input` 的归属、分页、答案校验与
  物化门禁；该 case 需要 `host-mock`，不是 `requires=-`。

`interaction.pending_input_contract`、`thread.queued_command_batch`、
`model.attempt_failure_visibility` 依赖 case 内自建的宿主 `127.0.0.1` mock Provider，因此
属于 `requires=host-mock`：它们不读取真实凭据，但在 distributed 容器拓扑下不可用。
`canvas.storage_upload_contract` 需要 S3，`canvas.function_fake_runtime` 还需要
`--with-canvas-function` 打开 fake adapter。

Goal 工具目录、Branch 设置命令以及草稿请求预览的拒绝与零写入边界也由 L1 覆盖；
预览的 Provider wire body 与附件等价性由本地数据库/S3 集成测试覆盖。真实 Agent 的 Issue 接受、阶段交接与
Goal 进度链路不能仅靠 API 契约断言，需另行在真实 Runtime 上验收。

默认 backend URL 是 `http://127.0.0.1:18081`，frontend URL 是 `http://127.0.0.1:5173`。
runner 会复用已有服务，或杀掉上述端口监听后启动服务；执行前须确认端口和 E2E 数据库
（默认 `127.0.0.1:5432/kk_studio_e2e`）为独立可丢弃环境，不要指向共享或生产数据库。
Backend 启动还要求有效的 `KK_STUDIO_STORAGE_S3_*` 配置及可达的 bucket；即使只跑免费 L1 API
矩阵，也需要独立的 S3/MinIO 环境。不能用共享或生产存储桶充当测试基础设施。
`--rebuild` 默认允许 Maven 在线解析依赖，只有 `E2E_MAVEN_OFFLINE=true` 时才加 `-o`；
`E2E_WORK_DIR` 默认 `runtime/e2e`，工具 case 的任务工作目录默认是其下的 `environment`（fixture
路径与 Tool 调用的 `workdir` 都由它派生，不是 Daemon 配置），可由 `DAEMON_ENV_ROOT` 覆盖。执行
`--ui` 前必须完成 `npm --prefix frontend ci`，因为 Playwright 从
[frontend/package.json](../../frontend/package.json) 加载。

### 真实 Provider 与付费边界

E2E 矩阵只有 `--real` 会读取并同步宿主凭据，且必须提供四组完整 pair，缺一或只给一半都会在同步前失败：

- `TEST_GOOGLE_BASE_URL` + `TEST_GOOGLE_API_KEY`
- `TEST_OPENAI_BASE_URL` + `TEST_OPENAI_API_KEY`
- `TEST_ANTHROPIC_BASE_URL` + `TEST_ANTHROPIC_API_KEY`
- `TEST_DEEPSEEK_BASE_URL` + `TEST_DEEPSEEK_API_KEY`

对应模型固定为 `google/gemini-3.8-flash`、`openai/gpt-5.6-luna`、
`minimax-anthropic/MiniMax-M3` 和 `deepseek/deepseek-v4.1-flash`，不会静默换 provider/model。OpenAI
与 DeepSeek 的 Base URL 会去掉尾部斜杠并补齐 `/v1`；Gemini 与 MiniMax Anthropic 只去尾部斜杠。

同步通过 backend API 写入 E2E database 中对应的 seed Provider row，credential 不进入 seed
SQL/resource、Compose、Dockerfile、image layer、backend/Daemon environment 或报告。不要把
`docker inspect`、完整 endpoint 或数据库凭据内容放进报告。未带 `--real` 时，runner 会在首个 case
前对整个 Provider catalog 做 fail-closed 检查：任何 configured row 或非空 `baseUrl` 都会阻止免费
矩阵运行，因此复用曾执行真实 E2E 的 database 会失败，需要改用全新未配置的 E2E database。

真实 Agent 可靠性矩阵使用独立的 `TEST_MINIMAX_BASE_URL` + `TEST_MINIMAX_API_KEY`，只更新其隔离
database 中的 `minimax` Responses Provider。

四种协议的文本缓存验收已注册为 `real.text_cache.*`，可由矩阵自动执行，不需要另加手工探针。
取得付费授权、提供上述四组凭据后，必须使用隔离的新 DB、S3 与后端，不复用开发或生产环境。
两个指定模型可分别执行：

```bash
./scripts/dev/verify/e2e/run.sh --real --only real.text_cache.deepseek_chat
./scripts/dev/verify/e2e/run.sh --real --only real.text_cache.google_gemini
```

Google 固定首轮加 1 个 follow-up（最多 2 次），其他协议首轮加最多 3 个 follow-up（最多 4 次，
命中即停）；case 不自动加重试。每轮验证 marker、usage 七字段及厂商代数，同时要求 IDLE、
无 active `modelInvocation`、无 `queuedCommands`，对应 `TURN_END` 为 COMPLETED 且
`continueModel=false`。非 Google 必须至少一个 follow-up 的 `cacheReadTokens>0`。
Gemini implicit cache 是机会性能力，零缓存不会导致失败：**Gemini PASS 只代表协议执行与 usage
通过，不代表缓存命中**。

报告中的 `real-text-cache-*.json` 使用统一 `rounds`，每轮 `ordinal` 从首轮 1 开始，记录 usage
七字段及单请求 `cacheReadRatio=cacheReadTokens/(inputTokens+cacheReadTokens+cacheWriteTokens+cacheWriteLongTokens)`。
`modelRequestCount` 是已完成观测数，不是底层 transport retries 计数；`cachePolicy` 为 Google
`observed`、其他 `required`，`cacheOutcome` 按 follow-up 观测记录 `HIT` / `NOT_OBSERVED`，
尚无后续观测时为 `NOT_EVALUATED`。它与 case PASS/FAIL 独立。每轮成功观测立即写入白名单 artifact，
强缓存断言前已落盘；失败也保留此前完成轮次的数值，不输出原始 provider 配置、URL、错误或模型回复。

独立的 [`AnthropicHistoryCacheLiveProbeTest`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicHistoryCacheLiveProbeTest.java)
用于测量历史断点，而非仅验证 system 前缀命中。它直接调用 Provider，不启动应用或数据库；默认只执行免费 wire 形状检查。
已授权付费、且 `TEST_ANTHROPIC_BASE_URL` / `TEST_ANTHROPIC_API_KEY` 完整时，可显式运行：

```bash
env JAVA_HOME=$JAVA_HOME_21 KK_STUDIO_REAL_CACHE_PROBE=true \
  mvn -pl harness/provider -am -Dtest=AnthropicHistoryCacheLiveProbeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该探针固定使用 `minimax-anthropic/MiniMax-M3` 的 wire model `claude-fable-5-dd-3M-xaMiniM`，
关闭推理，每次输出上限 128 token，总共最多 4 次请求、不重试。两个独立 nonce 会话各先预热约 7k token
的用户历史，再增加 24 个文本块；一组保留历史端点，另一组只删除历史标记、保留末端，system 很短且不打标。
输出只包含标记位置、状态和数值 usage。上游可能自动缓存或不报告缓存写入，因此成功完成测量不等于证明断点带来提升；
应比较两组读缓存 token，并明确该结果只适用于凭据指向的线路，不等同于 Anthropic 官方服务行为。

两个内建工具 case `real.task_delegation` 与 `tool.read_turn` 默认使用
`minimax-anthropic/MiniMax-M3`，可用 `E2E_BUILTIN_MODEL` 显式换用另一个**已声明**模型；取值是
[real-models](../../scripts/dev/verify/e2e/lib/real-models.mjs) 的 `idSuffix`：
`google_gemini`、`openai_responses`、`minimax_anthropic`（默认）、`deepseek_chat`。非法取值在 case
开始前直接失败，不自动 fallback、不改 provider identity，实际选择写入 case artifact。该选项只让这两个
内建工具 case 换模型复用同一套断言，不替代各 provider 的专项验收（`real.text_cache.*`、
`real.reasoning_levels.*`、`real.tool.*` 仍按各自 provider 运行）。

隔离栈之外还有一条真实 Seedance prepare-only smoke：它只验证页面准备与 checkpoint，不点击生成、
不创建 FunctionRun、不下载或导入视频，因此必须在显式开关下由人工执行。它固定 `seedance2.0fast`、
`duration=4`、`submit=0`、`retry=0`，`OPENCLI_HUB_BASE_URL` 没有默认值。完整命令、参数约束与失败
边界见 [deploy/test 的真实 Seedance prepare-only 边界](../../deploy/test/README.md#真实-seedance-prepare-only-边界)；
任何正式 Seedance/GPT Image 提交都可能产生费用，只能由人工通过应用的独立真实提交开关启用。

### 分布式双节点栈

`--distributed` 启停 [deploy/distributed](../../deploy/distributed/compose.yaml) 的双 App/双 Daemon
栈，并在适用 case 上启用 L5；它完全免费，不读取宿主真实 Provider 凭据，也不改变默认单实例路径。
栈的手工命令、端口、故障注入与清理语义见
[部署与运行](deployment.md#deploydistributed双节点零-app-to-app-网络栈)。

## 可靠性

### 确定性回归

```bash
./scripts/dev/verify/reliability/regression.sh --help
./scripts/dev/verify/reliability/regression.sh --iterations 1
./scripts/dev/verify/reliability/regression.sh --iterations 3 --report-root reports/reliability
```

`--iterations` 取值 `1..100`（默认 `3`），首轮失败即停止后续轮次。runner 不启动
backend/frontend/daemon/Compose，只从仓库根目录用 JDK 21 运行冻结的 Java/Surefire 集合；
测试本身会按各自基座启动隔离 PostgreSQL Testcontainers，仍需 Docker，不操作部署数据库；
每轮开始还会删除目标模块原有 Surefire 报告目录，当前结果复制到本次报告目录。
`TARGET_MODULES` 与 `TARGET_FQCNS` 是该集合的唯一精确 inventory，覆盖 Web event/transport、
Canvas Function Work/dispatcher、Harness Work/notification 和 Platform Storage cleanup。每轮要求
目标模块的 Surefire XML 证明 `tests > 0`、`failures = 0`、`errors = 0` 且不是全 skipped；
缺失目标类或不符合 testsuite 身份/计数约束的报告、Maven `[ERROR]`、
`Surefire is going to kill` 或非零退出都失败。
XML 检查不是通用语法验证器，不能把这道门禁称为任意畸形 XML 的完整校验。

### 隔离栈与真实 Agent 矩阵

栈的 `up`/`status`/`logs`/`inspect`/`down` 生命周期与端口见
[部署与运行](deployment.md#deployreliabilityapp--environment-daemon)。`up` 在有宿主
`TEST_MINIMAX_*` 时同步该 credential pair；工具隔离由 `inspect` 断言。矩阵专用的准备命令是：

```bash
./scripts/dev/verify/reliability/stack.sh snapshot
./scripts/dev/verify/reliability/stack.sh case-reset '<case-id>' pi
./scripts/dev/verify/reliability/stack.sh case-deps '<case-id>'
./scripts/dev/verify/reliability/stack.sh tool-smoke
```

先把 `<case-id>` 替换为 inventory 中的实际值；`case-reset` 最后一项可选 `pi` 或 `pi-base`。
`snapshot` 不猜测宿主目录，`PI_ANCHOR` 和 `PI_BASE_ANCHOR` 都必须显式指向 clean Git worktree。
`tool-smoke` 把 [`NativeToolSmoke.java`](../../scripts/dev/verify/reliability/fixtures/NativeToolSmoke.java) 经 stdin 送入
Daemon 容器编译并运行 find/grep/bash assertions，不经过 Agent、Provider 或 App command batch。

真实 Agent runner 只在显式执行时调用 Provider：

```bash
node scripts/dev/verify/reliability/run-agent-matrix.mjs --help
node scripts/dev/verify/reliability/run-agent-matrix.mjs --list
node scripts/dev/verify/reliability/run-agent-matrix.mjs --only CASE_ID
node scripts/dev/verify/reliability/reassess-agent-run.mjs '<runId>'
```

| Flag | 默认/语义 |
| --- | --- |
| `--list` | 列出冻结矩阵，不做 HTTP/model call |
| `--only CASE_ID` | 可重复选择 case |
| `--base-url URL` | `http://127.0.0.1:18091` |
| `--daemon-env NAME` | `docker-reliability` |
| `--report-root DIR` | `reports/reliability` |
| `--max-cost-usd N` | 默认 `5`，取值 `0..5`，硬上限为 USD 5 |

case 集合、模型/变体组合与 tool policy 由 [scripts/dev/verify/reliability/matrix.mjs](../../scripts/dev/verify/reliability/matrix.mjs)
和 [policy.mjs](../../scripts/dev/verify/reliability/policy.mjs) 定义。真实执行前 runner 要求 provider、
model、variant、Tool catalog 和 Environment `READY` 全部匹配；未知 cost、超过上限、测试或工作区
隔离证据缺失都 fail closed。`--real`/`--with-tools` 等 flag 只属于
[`scripts/dev/verify/e2e/run.sh`](../../scripts/dev/verify/e2e/run.sh)，不适用于该 runner。

## 性能基线

```bash
./scripts/dev/verify/performance/run.sh --help
./scripts/dev/verify/performance/run.sh
./scripts/dev/verify/performance/run.sh --duration-seconds 5
./scripts/dev/verify/performance/run.sh --skip-build
./scripts/dev/verify/performance/run.sh --report-root /tmp/kk-studio-performance
```

| Flag | 当前值 |
| --- | --- |
| `--duration-seconds N` | `1..120`，默认 `10`；每场景先 warmup `1s` |
| `--report-root DIR` | 默认 `reports/performance`；拒绝 root、仓库根、非目录和 symlink |
| `--skip-build` | 复用 `kk-studio-app:performance-baseline` |

runner 使用 Node built-in `fetch`，单请求 timeout `5000ms`，固定使用
[deploy/test](../../deploy/test/README.md) 的离线 mock 与其默认宿主端口，不读真实凭证、不启动
Daemon。

运行开始与退出都无确认提示地执行同名测试栈的 `down --volumes --remove-orphans`，
删除 PostgreSQL/MinIO 数据；`--skip-build` 只跳过镜像构建，不取消这项副作用。
执行前确认 `kk-studio-canvas-test` project 没有需要保留的数据。

| 场景 | 并发 | 请求 | error | p95 | throughput RPS | 最少样本 |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| `health` | 16 | `GET /actuator/health`，验证 `status=UP` | 0 | `<=250ms` | `>=50` | 20 |
| `catalog` | 16 | `GET /api/ai/catalog/models?pageNumber=1&pageSize=20`，验证严格 catalog fields | 0 | `<=500ms` | `>=25` | 20 |
| `canvas` | 4 | `POST /api/canvases` 后 `DELETE /api/canvases/{id}`，验证 UUID/规范十进制 revision | 0 | `<=1500ms` | `>=5` | 20 |

阈值与响应校验由 [`runner.mjs`](../../scripts/dev/verify/performance/runner.mjs) 定义。
这是本机免费回归基线，不是容量规划；以本次 `reports/performance/latest/report.md` 的样本、
错误、延迟、吞吐与清理结果判断是否通过，字段契约失败应先按正确性问题处理。

百分位是 nearest-rank，按 `ceil(p / 100 × n)` 取值；错误请求仍计入延迟，少于 20 个测量样本
fail closed。Canvas worker 按 run title prefix 清理已知和扫描出的资源；正常、失败、超时、INT、
TERM 都执行 `down --volumes --remove-orphans`。

## 供应链门禁

```bash
./scripts/dev/verify/supply-chain/run.sh sbom
./scripts/dev/verify/supply-chain/run.sh audit
./scripts/dev/verify/supply-chain/run.sh image
./scripts/dev/verify/supply-chain/run.sh all
./scripts/dev/verify/supply-chain/run.sh test
./scripts/dev/verify/supply-chain/run.sh help
```

| 子命令 | 内容 |
| --- | --- |
| `sbom` | backend/frontend CycloneDX JSON SBOM，校验文件非空可解析 |
| `audit` | frontend `npm audit` + Maven OWASP Dependency-Check |
| `image` | 构建 App/Daemon，运行功能 smoke，再用 pinned Trivy 扫描 |
| `all` | `sbom`、`audit`、`image` 的合取结果 |
| `test` | [supply-chain.test.mjs](../../scripts/dev/verify/supply-chain/tests/supply-chain.test.mjs)，不联网 |

根 POM 的 `supply-chain` profile 是显式 profile：CycloneDX Maven Plugin `2.9.3` 生成 JSON schema
`1.6` 的非 test-scope aggregate BOM；OWASP Dependency-Check `13.0.0` 输出 HTML/JSON/SARIF，
`failBuildOnCVSS=0`、`failOnError=true`、关闭 OSS Index、启用 NVD update。

Boot 保持 `4.0.8`，Servlet 容器与 Netty 仍在受支持的 Tomcat 11.0.x / Servlet 6.1、
Netty 4.2.x 平台上。安全修订统一由根 POM 的 `dependencyManagement` 管理，模块不得另行覆盖；
Netty、Jackson 2/3 与 Kotlin BOM 在 Boot BOM 前导入，以 Maven 首次声明优先规则生效。
`run.sh test` 的声明守卫、
[ResolvedSecurityDependenciesTest](../../web/src/test/java/fun/fengwk/kkstudio/web/ResolvedSecurityDependenciesTest.java)
的真实 classpath 版本回归与
[BootPlatformCompatibilityIntegrationTest](../../web/src/test/java/fun/fengwk/kkstudio/web/BootPlatformCompatibilityIntegrationTest.java)
的真实启动断言共同守住该边界。Kotlin BOM 统一运行时版本；现代 stdlib 已包含 common metadata，
根 POM 的 OkHttp 管理项限定排除 legacy `kotlin-stdlib-common` 传递 JAR，不增加替代依赖。

- `NVD_API_KEY` 可选。有 key 时脚本在临时目录创建 mode `600` 的 `settings.xml`（server id
  `kk-studio-supply-chain-nvd`），Maven 进程不继承 key，key 不进入 command line、POM、summary 或
  log；无 key 时使用 NVD 官方 JSON 2.0 feed。
- 在线源、Maven Central、npm registry、NVD 或 report generation 不可用时保持 `FAIL`；缺失数据
  不能生成 `PASS`。策略是零 suppression 与零漏洞，Dependency-Check 与 npm audit 都不配置白名单。
- Trivy image 固定为脚本中的 immutable digest，并校验版本 `0.74.0`；扫描过滤 `HIGH,CRITICAL`
  且包含无修复版本的条目，不使用 suppression 或 `--ignore-unfixed`。cache volume 默认 `kk-studio-trivy-cache`，可用
  `SUPPLY_CHAIN_TRIVY_CACHE_VOLUME` 覆盖；已有完整 cache 时设置 `TRIVY_SKIP_DB_UPDATE=true`，
  cache 不完整仍失败。
- `SUPPLY_CHAIN_TRIVY_NETWORK` 可显式指定 Docker network 名称或 ID（字母/数字开头，后续仅允许
  字母、数字、`_`、`.`、`-`），例如 `SUPPLY_CHAIN_TRIVY_NETWORK=host ./scripts/dev/verify/supply-chain/run.sh image`。
  空值保持自动行为：loopback proxy 使用 host，其他情况使用 Docker 默认网络；该选项仅影响 Trivy，
  不修改 DNS、不依赖特定机器 hostname，代理值不进入 scanner argv。
- Trivy 非零、JSON 缺失或不可解析、二次解析发现任一 HIGH/CRITICAL、镜像 smoke 失败或默认 user
  为 root，都保持 `FAIL`。

`image` 构建的镜像与可覆盖 tag：

| 镜像 | Dockerfile | 默认 tag | 覆盖变量 |
| --- | --- | --- | --- |
| App | [`deploy/local/Dockerfile`](../../deploy/local/Dockerfile) | `kk-studio-app:supply-chain` | `SUPPLY_CHAIN_APP_IMAGE` |
| Daemon | [`deploy/reliability/daemon.Dockerfile`](../../deploy/reliability/daemon.Dockerfile) | `kk-studio-daemon:supply-chain` | `SUPPLY_CHAIN_DAEMON_IMAGE` |

App smoke 在默认 non-root user 下检查 Java、`ffmpeg`、`ffprobe`、`curl`；Daemon 额外检查 Node
`v22.23.3`、npm `11.21.0`、bash、git，并用一次 `npm install --package-lock-only` 验证 npm 工具链。
Daemon 使用 Java 21 / Jammy，并将 npm bundle 的 `brace-expansion`、`ip-address`、`undici`
分别更新到 `5.0.12`、`10.7.3`、`6.28.1`；`tar` 使用 npm 自带的 `7.5.22`。
`http-cache-semantics<=4.2.0` 的 HIGH `CVE-2026-93748` 当前尚无上游补丁，保持原包并报告未解决风险，
不能据此宣称镜像扫描通过。

Daemon 原有 smoke 成功后，通过 stdin 注入
[`npm-cache-probe.mjs`](../../scripts/dev/verify/supply-chain/npm-cache-probe.mjs)，无需重建镜像。
它使用默认 UID `10001`、真实 npm bundle、loopback HTTP 与 synthetic marker，不挂载宿主目录、
不传用户环境/代理/密钥；请求有界且无 retry，完整消费 body 后关闭 server、删除本次临时 cache：

```bash
docker run --rm -i --network none --read-only --tmpfs /tmp --entrypoint node \
  "$DAEMON_IMAGE" --input-type=module \
  < scripts/dev/verify/supply-chain/npm-cache-probe.mjs
```

供应链报告保留 `image/npm-cache-probe.json` 与独立 stderr `logs/npm-cache-probe.log`。
JSON 记录 runtime/shared、origin hits、第二次 body 来源、cache status 和 Set-Cookie 回放。7 个 case 分为：

- 同认证 fresh cache 正对照；两个认证/Cookie 变更的 Vary case 与响应 no-store 必须回源。
  这四项及 `shared=false` 断言失败均非零退出。
- 无 Vary 的认证变更、响应 `no-cache` + `Age: 120` 后请求 `max-stale` 只记录
  `OBSERVED` / `NOT_OBSERVED`；无 public 的 Set-Cookie case 观察头剥离，不证明 body 隔离。

实测 `shared=false` 下无 Vary 仍复用 A body，`no-cache` 回源保护仍被 `max-stale` 绕过。
`ASSERTIONS_SATISFIED` 不证明安全、漏洞修复或不受影响；同 OS 用户 cache 不是跨租户隔离，
**不要跨互不信任主体共享 npm cache**，认证响应需正确配置 Vary，禁止存储用响应 no-store。
退出非零、JSON 空/非法或预期报告缺失使 Daemon smoke 失败，随后仍运行 Trivy；
观测不改变漏洞 gate、不提供 suppression，不能将 `shared=false` 视为修复。

## 本机 preview 与 NAS 自迭代

本拓扑要求外部 NAS 部署以单个 `prod` App `vps-kk-studio` 承担共享
数据库唯一的 Flyway owner 与 Harness worker；本仓脚本不校验外部容器数量。镜像、挂载与变量契约见
[部署与运行](deployment.md#nas-运行拓扑)。

| 面 | 位置 |
| --- | --- |
| 异步 Work（Thread/Model/Tool processor，含 Tool 执行） | 仅 `vps-kk-studio` 的 worker |
| 本机 preview 的 Vite/HMR、同步 HTTP API、查询投影、应用事件 WebSocket | 笔记本上的 Backend 与 Vite |
| Environment 的宿主能力 | 笔记本（或其它主机）上的 Environment Daemon |

Human 在本机 preview 提交命令时，同步 HTTP 处理使用当前工作区代码，随后产生的异步 Harness Work
使用 NAS 上正在运行的镜像，同一用户流程明确允许跨两个版本边界；共享 PostgreSQL/S3 是唯一数据
事实源。两个版本的 schema、持久 JSON 与 Work wire 兼容时可继续迭代；
schema、持久 JSON 或 Work wire 确实不兼容时，停止 preview 写入并评估已授权的部署切换；
只有无法沿用现有 schema 且数据所有者批准重建时才按下文处理数据库，健康 S3 默认继续复用。
修改 processor/runtime 等异步执行路径不会在本机 preview 中
生效，这些改动在 NAS 镜像更新前只能由自动化测试验证，本机 preview 只覆盖前端、同步 API 和查询
行为。

### 本机 preview 的外部数据面

[scripts/dev/shared-preview.sh](../../scripts/dev/shared-preview.sh) 是笔记本上的一条命令入口：它用 NAS 上已有的
PostgreSQL/S3 启动已打包的 Backend 与 Vite/HMR，并复用
[scripts/dev/app.sh](../../scripts/dev/app.sh) 的全部子命令。

```bash
./scripts/dev/shared-preview.sh start
./scripts/dev/shared-preview.sh status
./scripts/dev/shared-preview.sh logs all
./scripts/dev/shared-preview.sh stop
```

配置默认来自 `$HOME/.config/kk-studio/shared-preview.env`，只有 `SHARED_PREVIEW_ENV_FILE` 能改成别的路径；模板是
[scripts/dev/shared-preview.env.example](../../scripts/dev/shared-preview.env.example)，只含键名与空值，真实
值永远留在仓库之外。文件必须是当前用户所有的绝对路径普通文件、不是符号链接、没有 group/other
权限位：

```bash
install -d -m 700 ~/.config/kk-studio
install -m 600 scripts/dev/shared-preview.env.example ~/.config/kk-studio/shared-preview.env
```

只在首次配置时复制模板；`install` 会覆盖同名配置文件，不要用它重置已有凭据。随后在仓库之外填入真实值。

[scripts/dev/app.sh](../../scripts/dev/app.sh) 只按 `KEY=VALUE` 逐行字面量解析，不使用 `source`/`eval`，
只按第一个 `=` 拆分，忽略空行与 `#` 注释行；键必须落在外部数据面的白名单内，值不缺失、不出现在
日志或命令参数里。任何失败（相对路径、目录、符号链接、属主不符、权限过宽、未知键、缺值）都在
启动任何服务之前报错，且不会回显文件内容。

| 变量 | 默认 | 含义 |
| --- | --- | --- |
| `BACKEND_HOST` / `BACKEND_PORT` | `127.0.0.1` / `18080` | 本机 Backend 监听地址与端口 |
| `FRONTEND_HOST` / `FRONTEND_PORT` | `127.0.0.1` / `5173` | 本机 Vite/HMR 监听地址与端口 |
| `SHARED_PREVIEW_ENV_FILE` | `$HOME/.config/kk-studio/shared-preview.env` | 外部数据面配置文件的绝对路径 |
| `DEV_WORK_DIR` | `runtime/dev` | log 与 PID 目录 |

入口固定 `SPRING_PROFILES_ACTIVE=prod`、`SPRING_FLYWAY_ENABLED=false` 和
`KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false`：即使调用方或环境里有相反取值也以这组为准，
缺配置或不一致时在启动前失败。`SPRING_PROFILES_ACTIVE` 不是 `prod`、Flyway 没有关闭、或进程
试图承担 Harness worker，都会直接报错，不会让本机进程成为第二个迁移执行者或第二个 worker。

Environment Daemon 不属于 NAS App 容器。需要在某台主机上执行文件、命令与检索时，按
[Environment Daemon 安装与运行](environment-daemon.md)在该主机安装常驻服务，连接 NAS App 的
gateway `wss://<studio-origin>/api/harness/environment-daemon/v1`。

### 共享数据库重建

配置保存在产品中，数据库维护只负责备份与建立空库：

```text
设置 -> 同步 -> 导出 YAML
  -> 停止写入 -> 备份并重建数据库
  -> 初始化当前 Schema -> 启动产品 -> 同步 -> 导入 YAML
```

这不是运行数据迁移。健康且与镜像兼容的数据面继续复用；
只有数据所有者批准数据范围、停机窗口与恢复方案时才执行重建。
恢复入口与对象存储保护要求见[恢复与重建](deployment.md#恢复与重建)。

#### 配置导入与导出

设置页的“同步”位于“高级”之后，提供“导入”和“导出”。
导出默认选择全部配置，也可只选具体条目；依赖自动加入，无需另选开关。
文件始终包含配置所需的凭据和注册令牌，请保存在私密位置，不提交到 Git、不粘贴到日志。

可导出 Provider、Model、Agent、Skill Package、Environment、MCP 服务与系统设置。
Agent 自动包含 Model、Provider、引用的 Skill Package、MCP 服务和 Subagent；
Model 包含 Provider；系统设置包含其引用的备用模型和提示词 Agent。
依赖只沿引用方向扩展，不包含无关的其它模型或 Agent，也不因选择 Agent 自动加入 Environment。

导入选择 `.yaml` 或 `.yml` 文件并确认，按名称新增或更新，不删除文件外的配置。
条目名称须非空且没有首尾空白，重复名称会使导入失败，不会依次覆盖。
Provider 凭据按文件恢复；文件中的 Provider 没有凭据时，同名目标的旧凭据也会清空。
系统设置只合并提供且支持的字段，省略字段保持原值。导入结果列出已导入条目和跳过原因；
不支持的条目及依赖它们的配置不会被悄悄改写。YAML 结构、类型或有效值错误使导入失败，
数据库写入失败整体回滚。设置页的未保存草稿不会被自动覆盖，可在导入结果中明确重新加载。
网络代理设置参与同步，导入后仍须重启 Backend 才会生效。

Skill Package 文件保存仓库地址、分支和已发布的 exact commit，不包含 Git 文件内容；
目标节点须能获取该 commit，失败时跳过，不用最新 HEAD 替代。MCP 工具在导入时重新发现，
发现失败明确报告；`${VAR}` 形式的 header 保持原值，目标部署仍须提供所需变量。
Environment 保留注册令牌，同名更新保持其身份；新库生成新的 UUID，已安装 Daemon 可用原令牌重新连接。

配置文件不保存 Chat、Session、Thread、Canvas、Project、Issue、执行历史、Blob 引用、
连接租约和发现快照，也不包含部署级数据库/S3 参数、Plugin 认证或主密钥。
需要完整恢复时使用全库备份及匹配的对象存储备份，而不是配置 YAML。

数据库脚本只有 [scripts/ops/reset-database.sh](../../scripts/ops/reset-database.sh)：
默认备份旧库、重命名冻结，并以原元数据创建同名空库；验收后可显式清理冻结快照。
它只连接 PostgreSQL，不管理应用或容器，也不执行 Flyway；重建目标库或删除快照时，
发现该库仍有其他会话就拒绝，不终止会话。

#### 连接

最直接的配置方式是导出以下变量：

```bash
export VPS_POSTGRES_HOST=
export VPS_POSTGRES_PORT=
export VPS_POSTGRES_USERNAME=
export VPS_POSTGRES_PASSWORD=
export VPS_POSTGRES_DATABASE=
```

也可以直接传入非敏感连接项：

```bash
./scripts/ops/reset-database.sh \
  --host <host> \
  --port <port> \
  --username <username> \
  --database <database> \
  --dry-run
```

连接项的优先级为：

```text
命令行参数 > VPS_POSTGRES_* > 标准 libpq 配置
```

标准 libpq 的 `PGHOST`、`PGPORT`、`PGUSER`、`PGDATABASE`、`PGPASSFILE`、`PGSERVICE` 和 TLS
参数仍可单独使用。只要提供了任一 CLI/VPS 非密码连接项，脚本就使用直接连接模式，不与
`PGSERVICE` 混合；未覆盖的字段仍可从对应 `PG*` 变量继承。CLI 或 VPS 显式提供 host 时还会清除
`PGHOSTADDR`，保证实际 socket 目标与所选 host 一致；纯 libpq 模式则保留 `PGHOSTADDR`。

密码没有命令行参数，只能来自 `VPS_POSTGRES_PASSWORD` 或标准 libpq 的
`PGPASSWORD`/`PGPASSFILE`。所有客户端均使用 `--no-password`，缺少凭据时直接失败，不弹出交互式
提示。目标库必须是普通 PostgreSQL 标识符，URI 和 conninfo 字符串会被拒绝。

部署基线是 PostgreSQL 17；`pg_dump` 不得比服务端旧。`reset-database.sh` 使用 PostgreSQL 15+
的 `createdb --locale-provider`，并通过 `KK_STUDIO_MAINTENANCE_DB` 选择维护库（默认 `postgres`）。
托管服务若禁止 `CREATE DATABASE` 或 `ALTER DATABASE ... RENAME`，使用服务商提供的数据库
生命周期能力；配置同步仍通过产品完成。

#### 执行

1. 在产品中导出需要保留的配置并确认文件可用；导出后停止配置编辑。
   批准维护窗口后停止全部 App/Worker、preview 与 Daemon，等待在途调用收敛，
   保持数据库重建和验收期间停写。

2. 确认仓库外备份位置、访问权限、空间与恢复能力，预检后备份并建立空库：

```bash
./scripts/ops/reset-database.sh --dry-run
./scripts/ops/reset-database.sh
```

reset 在任何改库操作前检查权限、目标库状态和其他会话；发现其他会话时直接拒绝，不主动终止，
也不代替调用者停止应用写入。调用者必须在整个 reset 期间保持目标库停写。preflight 会再次确认
当时没有其他会话，但一次会话计数不是后续写入的屏障。

真实执行在 preflight 后要求输入目标数据库名确认；无可读输入或名称不匹配就失败，
不会写备份或改库。`--dry-run` 不进入确认或改库流程。
`--yes` 可以跳过输入闸门，但只用于已有明确授权的非交互自动化，不能代替数据所有者批准。

确认后写入并验证 custom-format `pg_dump`。这份备份是 dump 开始时的一致时间点，不保证包含
dump 之后、冻结之前提交的写入。冻结后的 `<db>_pre_<UTCstamp>` 才是 reset 完成前的最新旧库。
备份经 `pg_restore --list` 检查并生成 SHA-256；它验证备份格式，不代替隔离恢复演练。
空库保持原 owner/encoding/locale/tablespace/connection limit。
目标空库就位前失败时，脚本尝试清理不完整空库、解冻并恢复原库名；恢复失败会报告人工处理步骤。
空库已就位后的初始化、回灌或验收失败不会自动回退。默认备份目录为
`${XDG_STATE_HOME:-$HOME/.local/state}/kk-studio/maintenance/backup`。

3. 通过部署侧既有的 schema/Flyway 初始化路径在空库上应用当前
[`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql)。这一步不属于维护
脚本。随后仅向维护人员开放产品入口，Worker 保持停止。

4. 在“设置 → 同步”导入之前保存的 YAML。检查已导入和跳过的条目，
   补齐目标部署的 Git/MCP 访问条件与 Plugin 认证后再恢复 Worker、Daemon 和对外访问。

5. 检查 App 健康、配置、Daemon 重连、关键业务和既有 bucket。
   验收结束前保留完整备份、冻结库和对象存储数据；失败时保持停写，按部署恢复流程决定回退。

维护脚本的自动化入口是 `python3 -m unittest discover -s scripts/ops/tests`，验证备份、
重建回滚、快照清理范围与确认、元数据保留、连接保护、owner-only 产物与敏感值不外泄。集成测试使用一次性 PostgreSQL 容器；
Docker 不可用时跳过的结果不能当作数据库流程通过。

#### 权限、产物与清理

- **重置权限**：`reset-database.sh` 需要能改目标库、能建库、并能把新库交给原 owner 的角色，**不要求 superuser**。具体检查（全部只读，缺哪一项就只读失败）：
  - 目标库的 owner 必须就是当前连接角色（或当前角色是 superuser）——`ALTER DATABASE ... RENAME` / `ALLOW_CONNECTIONS` 要求库所有权；
  - 非 superuser 角色必须拥有 `CREATEDB`；
  - 非 superuser 角色必须能对原 owner 角色 `SET ROLE`（即拥有其成员资格），否则 `createdb --owner=<原 owner>` 会被拒绝；
  - `pg_dump` 必须能读取库内全部表；备份步骤会在任何数据库变更前验证这一点；
- **敏感产物**：配置 YAML、冻结库和全库备份都可能含真实凭据；不得提交、粘贴到日志或上传公共存储；
- **本地备份**：默认位于 `${XDG_STATE_HOME:-$HOME/.local/state}/kk-studio/maintenance/backup`，目录为
  `0700`、文件为 `0600`，可通过 `--work-dir` 覆盖。浏览器下载的 YAML 不由脚本设置权限，须自行妥善保管；
- **维护后操作**：确认配置与重连，按需恢复 Plugin 认证；验收完成后按部署策略归档或清理 YAML、备份和冻结库。

#### 清理冻结快照

重建默认保留所有冻结库，不自动过期或删除。配置导入、关键功能与备份恢复能力验证通过，
确认不再需要快速切回旧库后，使用独立清理模式：

```bash
./scripts/ops/reset-database.sh --database kk_studio --cleanup-snapshots --dry-run
./scripts/ops/reset-database.sh --database kk_studio --cleanup-snapshots
```

连接来源与 reset 相同。预览列出库名、大小和数量；真实执行要求输入目标库名确认，
`--yes` 仅用于已授权的非交互执行。没有匹配项时直接成功退出。

只删除名称严格匹配 `<目标库>_pre_YYYYMMDDTHHMMSSZ`、禁止连接且不是模板的数据库，
并排除目标库与维护库。**范围包含最新快照，不额外保留一份**。当前业务库可保持在线；
清理需要每个快照的所有权或 superuser，不需要 `CREATEDB`、备份工具或备份目录。
它不执行重建，也不创建或删除本地备份、配置 YAML、S3 对象。

脚本在删除前检查整批权限和会话，并在确认后、每次删除前重新核对快照 OID、名称和冻结状态。
预览后新建的快照不纳入本次删除；清理期间不要并发重建、改名、解冻或替换快照。
出现状态变化或删除失败立即停止，不强杀连接。已完成的删除逐项输出且不能回滚，
后续恢复只能使用保留的全库备份和匹配的对象存储数据。

### 自迭代闭环

Agent 在本机 preview 上遵循以下闭环：

1. 开始前检查 Git 状态并保留 Human 的并行修改，不覆盖未提交工作。
2. 对实际变更执行定向测试；Java 关键路径同时遵守覆盖率门禁。
3. 普通 Frontend 变更由 Vite HMR 生效；Java 变更先构建，再用 `./scripts/dev/shared-preview.sh restart`
   重启受管的 Backend/Vite，不需要重建任何容器。
4. 重启前提交源码和必要的 durable 进度；重启只在当前回合内短暂中断 preview 的连接。
5. 验证 Backend health、Frontend、应用事件 WebSocket 后再继续下一轮。
6. 功能达到可验收状态后提交变更并报告验证和已知风险；只有获得发布授权后才 push 目标分支，涉及
   processor/runtime 等异步执行路径的改动必须附带自动化测试证据。

获得发布与部署授权后的协作顺序是：

```text
Agent modifies dev
  -> targeted tests
  -> commit and push dev
  -> GitHub publishes kk-studio:dev
  -> update the NAS app container to that tag and restart it
  -> observe and validate the entry point and Daemon reconnect
  -> merge dev into main
  -> main workflow runs the full gates and publishes kk-studio:main
```

以下变更可能使本机代码与 NAS Worker 不兼容，不能仅凭本机 preview 完成端到端验收；遇到它们时应
先评估 NAS 镜像切换；若需要空库，由数据所有者按独立维护流程批准数据范围与恢复方案：

- 未合入 `main` 的 Flyway migration 或破坏性 schema 变更；
- 删除或重命名持久 JSON 字段、数据库枚举值或 wire 字段；
- 改变 Work/Invocation 状态机、claim/lease/fencing 语义；
- 改变 S3 object key、Blob 引用计数或 cleanup 生命周期；
- 需要重建镜像与重启 NAS 容器，或使共享 durable 状态在旧镜像下不可读的数据变更。

同步 preview 与异步 Worker 可以使用不同提交，但必须遵守同一 durable 契约。
触发上述不兼容边界时先收敛版本，再恢复共享数据面的写入。

源码仓库、Dockerfile 和 image layer 只保存环境变量名与无敏感默认值。数据库、S3、Provider、
Gateway 和 Daemon registration credential 由 NAS 私密环境文件在运行时注入，本机 preview 的
credential 只存在于 owner-only 配置文件，两者都不进入 Git、镜像、命令参数或报告。

## 报告目录

所有 report 目录都被 Git ignore；每个入口保留 timestamped run 和 latest 副本：

```text
reports/e2e/
  <runId>/{report.md,summary.json,cases/,artifacts/,logs/}
  latest/{report.md,summary.json,cases/,artifacts/,logs/}
  LATEST_RUN.txt

reports/performance/
  <timestamp>-<id>/{report.md,summary.json}
  latest/{report.md,summary.json}
  LATEST_RUN.txt

reports/reliability/
  <timestamp>-regression-<pid>/{report.md,summary.json,iterations/}
  latest-regression/
  <runId>/{report.md,summary.json,cases/,artifacts/}
  latest-agent/
  LATEST_AGENT_RUN.txt

reports/supply-chain/
  <timestamp>-<pid>/{backend-sbom,frontend-sbom,frontend-audit,backend-audit,image,logs/,summary.md,summary.json}
  latest/
  LATEST_RUN.txt
```

`latest-agent` 是目录副本，不是 symlink。Supply-chain 报告包含 backend/frontend SBOM、npm audit、
Dependency-Check HTML/JSON/SARIF、App/Daemon Trivy JSON、image id/digest、smoke log 和 summary。

分布式 E2E 在销毁栈前收集脱敏日志：`logs/distributed-<service>.log` 是容器标准输出，
`logs/distributed-app-a/` 和 `logs/distributed-app-b/` 是容器 `/app/logs` 中的文本 `.log` 文件。
压缩归档和符号链接不复制；节点或日志目录不可用时继续采集其他节点。

## 清理与故障排查

开发循环自己的清理是 `./scripts/dev/shared-preview.sh stop` 或 `./scripts/dev/app.sh stop`；本地与测试栈、
分布式栈和可靠性栈的清理命令、
保留与删除语义见[部署与运行](deployment.md#清理)。[deploy/test](../../deploy/test/README.md) 与
performance 入口每次运行都会自行清理 PostgreSQL/MinIO/test network，`--distributed` 入口在退出时
清理双节点栈。

| 现象 | 先执行 | 边界 |
| --- | --- | --- |
| JDK/compile/checkstyle 失败 | `"$JAVA_HOME_21/bin/java" -version`；`env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp test-compile` | 必须是 JDK 21；validate 只检查静态规则，不能证明编译通过 |
| Frontend 找不到依赖或 Playwright | `npm --prefix frontend ci`；`npm --prefix frontend run test` | 依赖由 [`package-lock.json`](../../frontend/package-lock.json) 固定 |
| dev 端口占用 | `./scripts/dev/app.sh status`；`ss -ltnp \| grep -E ':18080\|:5173'` | 先确认监听者归属；换端口或设 `DEV_KILL_PORTS=false`，不要默认强杀不属于本次开发的服务 |
| 本机 preview 启动即失败 | `./scripts/dev/shared-preview.sh status`；核对 `SHARED_PREVIEW_ENV_FILE` 指向的文件 | 必须是绝对路径的 owner-only 普通文件；权限、属主、未知键或缺值都 fail closed，输出不回显文件内容 |
| local app unhealthy | `docker compose -f deploy/local/compose.yaml ps`；`docker compose -f deploy/local/compose.yaml logs app postgres` | 先确认 PostgreSQL health，再检查 `/actuator/health` |
| Canvas test 健康失败 | `docker compose -f deploy/test/compose.yaml ps`；`docker compose -f deploy/test/compose.yaml logs` | 检查 MinIO bucket、mock `/health`、ffmpeg/ffprobe |
| E2E 只跑少数 case | `node scripts/dev/verify/e2e/run-matrix.mjs --list`；确认 `--real`、`--with-tools`、`--with-canvas-storage`、`--with-canvas-function` | 通过 `requires` 和 level 过滤是当前行为 |
| 真实 Provider 不可用 | 检查 8 个 `TEST_{GOOGLE,OPENAI,ANTHROPIC,DEEPSEEK}_{BASE_URL,API_KEY}` 变量是否均非空 | 必须显式 `--real`，只用宿主同步器；不要放入 Compose/image/container |
| reliability 环境未 READY | `./scripts/dev/verify/reliability/stack.sh status`；`./scripts/dev/verify/reliability/stack.sh logs app daemon` | `inspect` 先检查 non-root、volume 和 gateway |
| 敏感数据门禁失败 | `python3 scripts/dev/verify/repository/check-sensitive-data.py` | 只按输出的规则和位置排查；不要把完整敏感值复制到日志或 Issue |
| performance/supply-chain 失败 | 阅读 `reports/performance/latest/report.md` 或 `reports/supply-chain/latest/summary.md` | 阈值、在线源、JSON 完整性和 zero-vulnerability 都不能放宽 |
| proxy 下 build 失败 | 检查 `HTTP_PROXY`/`HTTPS_PROXY`、`CANVAS_TEST_BUILD_NETWORK`；再执行基础设施 smoke | 离线 smoke 对可解析 proxy 默认使用 host build network；performance 仅自动处理 loopback proxy，代理值不进入 runtime 镜像 |

---

上级：[系统设计](../system-design.md)。相关文档：[部署与运行](deployment.md)、
[本地一键启动栈](../../deploy/local/README.md)、[Canvas/Storage 隔离测试栈](../../deploy/test/README.md)、
[Environment Daemon 安装与运行](environment-daemon.md)。
