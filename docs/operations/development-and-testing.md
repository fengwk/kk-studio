# 开发与测试

本文面向修改本仓库的开发者：准备工作站、跑日常开发循环、按意图选择质量检查，并说明 E2E、
可靠性、性能、供应链和 NAS 自迭代的操作方式。Fat JAR、Compose 栈和生产拓扑见
[部署与运行](deployment.md)；跨模块边界和全局不变量见[系统设计](../system-design.md)。

## 前置条件

| 工具 | 用途与约束 |
| --- | --- |
| JDK 21 | 所有 Maven 命令显式使用 `JAVA_HOME_21`；根 POM 的 `maven.compiler.release` 是 `21` |
| Maven | 通过 `mvn` 可用；[`scripts/dev/app.sh`](../../scripts/dev/app.sh)、[`scripts/dev/verify/e2e/lib.sh`](../../scripts/dev/verify/e2e/lib.sh)、[`regression.sh`](../../scripts/dev/verify/reliability/regression.sh)、[`scripts/dev/verify/supply-chain/run.sh`](../../scripts/dev/verify/supply-chain/run.sh) 都会校验 JDK 21 |
| Node 与 npm | Frontend 依赖由 [`package-lock.json`](../../frontend/package-lock.json) 固定；`distribution` profile 会自动安装 Node `v24.14.0` 与 npm `11.9.0` |
| Docker 与 Compose v2 | 本地栈、测试栈、性能基线和镜像扫描需要 |
| `curl`、`jq`、`lsof` | [scripts/dev/app.sh](../../scripts/dev/app.sh) 启动前后检查端口与健康状态 |
| Python 3 | E2E 与测试栈的 smoke 脚本 |

数据库与服务由容器提供：[deploy/local](../../deploy/local/README.md) 覆盖主要本地路径，
[deploy/test](../../deploy/test/README.md) 提供隔离离线栈。开发循环默认连接 PostgreSQL，
因此先把其中一个栈拉起来。

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
| Backend log / JAR | `runtime/dev/backend.log`、`web/target/kk-studio-web-1.0.0.jar` |

`start` 会先停止受管进程、检查端口占用、按需用 Maven 打包 Backend、按需安装前端依赖，等
Backend API ready 后再启动 Vite；`restart` 等价于 `stop` 后再 `start`，`logs` 与 `tail` 接受可选
目标 `backend`、`frontend`、`all`。

可用环境变量：

| 变量 | 默认 | 含义 |
| --- | --- | --- |
| `BACKEND_HOST` / `BACKEND_PORT` | `127.0.0.1` / `18080` | Backend 监听地址与端口 |
| `FRONTEND_HOST` / `FRONTEND_PORT` | `127.0.0.1` / `5173` | Vite 监听地址与端口 |
| `SPRING_PROFILES_ACTIVE` | `e2e` | Backend profile |
| `DEV_WORK_DIR` | `runtime/dev` | log 与 PID 目录 |
| `DEV_KILL_PORTS` | `true` | `stop` 时同时释放 dev 端口上的监听进程 |
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
| 快速确认改动可编译、格式正确 | `env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp validate` |
| 跑 Java 单元与集成测试 | `env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp test` |
| 触发关键类覆盖率门禁 | `env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp verify` |
| 只格式化本次改动的模块 | `env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp -pl <module> spotless:apply` |
| 前端单元测试 / lint / 类型与构建 / 覆盖率 | `npm --prefix frontend run test`、`run lint`、`run build`、`run coverage` |
| 校验 Compose 配置 | `docker compose -f deploy/local/compose.yaml config --quiet` 等，见下文 |
| 隔离栈端到端 smoke | `./scripts/dev/verify/smoke/offline-chat.sh --with-app` |
| 免费 API 契约矩阵 | [`./scripts/dev/verify/e2e/run.sh`](../../scripts/dev/verify/e2e/run.sh) |
| 确认矩阵有哪些 case | `./scripts/dev/verify/e2e/run.sh --list`、`./scripts/dev/verify/e2e/run.sh --docs` |
| 文档与敏感数据门禁 | `node scripts/dev/verify/repository/check.mjs`、`python3 scripts/dev/verify/repository/check-sensitive-data.py` |
| 可靠性确定性回归 | `./scripts/dev/verify/reliability/regression.sh --iterations 1` |
| 离线性能基线 | `./scripts/dev/verify/performance/run.sh` |
| 供应链 SBOM / 漏洞门禁 | `./scripts/dev/verify/supply-chain/run.sh all` |

真实 Provider、真实 Tool、UI、分布式和镜像扫描都只在显式开关下运行，默认路径不产生模型费用。

越靠上的检查越便宜，越靠下的越接近真实环境；日常改动先跑上两行，涉及契约或执行路径时再往下走：

| 目的 | 入口 |
| --- | --- |
| 静态与格式门禁 | `mvn validate`、`npm --prefix frontend run lint` |
| Java 与 Frontend 单测、覆盖率 | `mvn test`、`mvn verify`、`npm --prefix frontend run test\|coverage` |
| 免费端到端契约 | `./scripts/dev/verify/e2e/run.sh`、`./scripts/dev/verify/smoke/offline-chat.sh --with-app` |
| 真实 Provider、Tool、UI、分布式栈 | `./scripts/dev/verify/e2e/run.sh --real`、`--with-tools`、`--ui`、`--distributed` |
| 可靠性、性能、供应链 | [`scripts/dev/verify/reliability`](../../scripts/dev/verify/reliability/)、`./scripts/dev/verify/performance/run.sh`、`./scripts/dev/verify/supply-chain/run.sh` |

E2E 自身的 L1–L5 是 API case 的 level 分组，含义见下文 E2E 章节。

## Java 质量检查

### Spotless

`validate` 阶段执行 Spotless Maven Plugin `2.43.0`：Google Java Format `1.18.0`（`GOOGLE` style）、
`removeUnusedImports`，import order 为 `#,,fun.fengwk.kkstudio,javax,java`。

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp spotless:check
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp -pl <changed-module> spotless:apply
```

`spotless:apply` 会修改源码；`<changed-module>` 用实际发生 Java 变更的模块，避免对无关模块执行
全仓格式化。

### Checkstyle

`validate` 阶段执行 Checkstyle `3.3.0`，读取根目录 [checkstyle.xml](../../checkstyle.xml)，包含
test source 且 `failsOnError=true`。规则集中在两类：代码体内使用 import 而不是全限定类名；
`if`、`else`、`for`、`while`、`do` 等控制流必须带 braces。

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp checkstyle:check
```

### JaCoCo

根 POM 提供 JaCoCo `0.8.11`：`prepare-agent` 注入 test JVM，`test` 阶段执行 `report`，报告位于各
模块的 `target/site/jacoco/`。[`harness/common`](../../harness/common/pom.xml)、
[`harness/mcp`](../../harness/mcp/pom.xml)、[`harness/tool`](../../harness/tool/pom.xml)、
[`harness/environment`](../../harness/environment/pom.xml)、
[`harness/environment-server`](../../harness/environment-server/pom.xml)、
[`harness/runtime`](../../harness/runtime/pom.xml)、
[`harness/contributor-api`](../../harness/contributor-api/pom.xml)、
[`harness/builtin`](../../harness/builtin/pom.xml)、
[`harness/provider`](../../harness/provider/pom.xml)、[`platform`](../../platform/pom.xml) 和
[`web`](../../web/pom.xml)
在各自 POM 中把 JaCoCo `check` 绑定到
`verify`，按 `CLASS` include 只检查当前关键类，line coverage 下限为 `0.90`，个别类要求 `1.00`：

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp -pl web -am verify
find . -path '*/target/site/jacoco/index.html' -print
```

普通 `mvn test` 只生成报告；低于门禁的 line coverage 会让 `mvn verify` 的 `jacoco:check` 失败。
branch coverage 作为参考指标，具体数字以对应模块的 `target/site/jacoco/jacoco.csv` 为准，被检查的
类清单与各自阈值以模块 POM 的 jacoco 配置为准。

### Fat JAR

根 POM 的 reactor 当前是 [`share`](../../share)、[`schema`](../../schema)、[`canvas`](../../canvas)、
[`harness`](../../harness)、[`platform`](../../platform)、[`web`](../../web)。需要可运行产物时：

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp -Pdistribution -pl web -am clean package
"$JAVA_HOME_21/bin/java" -jar web/target/kk-studio-web-1.0.0.jar
```

`distribution` profile 在 `prepare-package` 安装 Node 与 npm、对 [`frontend/`](../../frontend/) 执行
`npm ci` 与 `npm run build`，并把产物打进 `BOOT-INF/classes/static`；普通 `mvn test` /
`mvn package` 不激活它。

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
| `run lint` | `eslint .` | TypeScript、React hooks、分层 import 规则 |
| `run build` | `tsc -b && vite build` | strict type-check + Vite production bundle |
| `run coverage` | `vitest run --coverage` | v8 text/html 报告与阈值门禁 |

[vite.config.ts](../../frontend/vite.config.ts) 的 coverage include 是 `src/**/*.{ts,tsx}`，排除
`src/main.tsx` 和 `src/test-setup.ts`，lines、functions、branches、statements 阈值均为 `80%`，
报告目录是 `frontend/coverage/`。[test-setup.ts](../../frontend/src/test-setup.ts) 为每个测试清空
localStorage、固定 `zh-CN`，并为 ResizeObserver、DOMMatrix、SVG geometry、Canvas 2D、dialog、
scrollIntoView 和 React Flow layout 提供确定性 stub。

改动前端如果影响 API 契约、首发顺序或 usage 语义，需要同步更新 E2E 矩阵 case 与相关文档；精确
case inventory 由 `node scripts/dev/verify/e2e/run-matrix.mjs --list` 与 `--docs` 提供，不在文档里复制。

## Compose、静态资源与文档门禁

```bash
docker compose -f deploy/local/compose.yaml config --quiet
docker compose -f deploy/test/compose.yaml config --quiet
docker compose -f deploy/test/compose.yaml --profile app config --quiet
docker compose -f deploy/reliability/compose.yaml config --quiet
./scripts/dev/verify/e2e/distributed.sh verify
```

[`scripts/dev/verify/e2e/distributed.sh verify`](../../scripts/dev/verify/e2e/distributed.sh) 只静态校验双节点 Compose config 与网络不变量，不启动容器。
[`scripts/dev/verify/smoke/offline-chat.sh`](../../scripts/dev/verify/smoke/offline-chat.sh) 把配置检查、镜像构建、依赖 health、非 root runtime、PostgreSQL、MinIO
bucket 与 HTTP mock smoke 组合成一个可清理入口，`--with-app` 再覆盖全局 Blob、Canvas Resource、
signed GET、fake Function、容器内 OpenCLI fake Hub 与离线 Chat。

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
| `--with-canvas-function` | 启用 fake Canvas Function，隐含 storage、rebuild 与 `KK_STUDIO_CANVAS_FUNCTION_FAKE_ENABLED=true` |
| `--distributed` | 启停 [deploy/distributed](../../deploy/distributed) 双节点 mock topology，不与 `--real`、`--with-tools`、`--ui`、`--with-canvas-*` 组合 |
| `--ui` | 在 API 矩阵后执行 Playwright UI 矩阵，截图并入同一 run |
| `--only CASE_ID` | 只运行指定 case，可重复 |
| `--level L1\|L2\|L3\|L4\|L5` | 过滤 API level，可重复；UI 不受此过滤器影响 |
| `--list` / `--docs` | 只列出 API case，或只打印 case 标题、requires 与契约文档 |

矩阵分成 L1 免费 API 契约、L2 真实文本与推理、L3 真实分支、L4 Environment/Tool/approval、L5
双节点分布式，外加独立的 UI 矩阵。每个 level 的 case 数、标题与 `requires` 只由
`node scripts/dev/verify/e2e/run-matrix.mjs --list` 与 `--docs` 生成，不要把它们抄进文档；默认执行哪些 case
由 flag 组合和 case 的 `requires` 共同决定。

默认 backend URL 是 `http://127.0.0.1:18081`，frontend URL 是 `http://127.0.0.1:5173`。
`--rebuild` 默认允许 Maven 在线解析依赖，只有 `E2E_MAVEN_OFFLINE=true` 时才加 `-o`；
`E2E_WORK_DIR` 默认 `runtime/e2e`，工具 case 的任务工作目录默认是其下的 `environment`（fixture
路径与 Tool 调用的 `workdir` 都由它派生，不是 Daemon 配置），可由 `DAEMON_ENV_ROOT` 覆盖。执行
`--ui` 前必须完成 `npm --prefix frontend ci`，因为 Playwright 从
[frontend/package.json](../../frontend/package.json) 加载。

### 真实 Provider 与付费边界

只有 `--real` 会读取并同步宿主凭据，且必须提供四组完整 pair，缺一或只给一半都会在同步前失败：

- `TEST_GOOGLE_BASE_URL` + `TEST_GOOGLE_API_KEY`
- `TEST_OPENAI_BASE_URL` + `TEST_OPENAI_API_KEY`
- `TEST_ANTHROPIC_BASE_URL` + `TEST_ANTHROPIC_API_KEY`
- `TEST_DEEPSEEK_BASE_URL` + `TEST_DEEPSEEK_API_KEY`

对应模型固定为 `google/gemini-3.8-flash`、`openai/gpt-5.6-luna`、
`minimax-anthropic/MiniMax-M3` 和 `deepseek/deepseek-v4-flash`，不会静默换 provider/model。OpenAI
与 DeepSeek 的 Base URL 会去掉尾部斜杠并补齐 `/v1`；Gemini 与 MiniMax Anthropic 只去尾部斜杠。

同步通过 backend API 写入 E2E database 中对应的 seed Provider row，credential 不进入 seed
SQL/resource、Compose、Dockerfile、image layer、backend/Daemon environment 或报告。不要把
`docker inspect`、完整 endpoint 或数据库凭据内容放进报告。未带 `--real` 时，runner 会在首个 case
前对整个 Provider catalog 做 fail-closed 检查：任何 configured row 或非空 `baseUrl` 都会阻止免费
矩阵运行，因此复用曾执行真实 E2E 的 database 会失败，需要改用全新未配置的 E2E database。

真实 Agent 可靠性矩阵使用独立的 `TEST_MINIMAX_BASE_URL` + `TEST_MINIMAX_API_KEY`，只更新其隔离
database 中的 `minimax` Responses Provider。

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
`TARGET_MODULES` 与 `TARGET_FQCNS` 是该集合的唯一精确 inventory，覆盖 Web event/transport、
Canvas Function Work/dispatcher、Harness Work/notification 和 Platform Storage cleanup。每轮要求
目标模块的 Surefire XML 证明 `tests > 0`、`failures = 0`、`errors = 0` 且不是全 skipped；缺类、
invalid XML、Maven `[ERROR]`、`Surefire is going to kill` 或非零退出都失败。

### 隔离栈与真实 Agent 矩阵

栈的 `up`/`status`/`logs`/`inspect`/`down` 生命周期与端口见
[部署与运行](deployment.md#deployreliabilityapp--environment-daemon)。`up` 在有宿主
`TEST_MINIMAX_*` 时同步该 credential pair；工具隔离由 `inspect` 断言。矩阵专用的准备命令是：

```bash
./scripts/dev/verify/reliability/stack.sh snapshot
./scripts/dev/verify/reliability/stack.sh case-reset <case-id> <pi|pi-base>
./scripts/dev/verify/reliability/stack.sh case-deps <case-id>
./scripts/dev/verify/reliability/stack.sh tool-smoke
```

`snapshot` 不猜测宿主目录，`PI_ANCHOR` 和 `PI_BASE_ANCHOR` 都必须显式指向 clean Git worktree。
`tool-smoke` 把 [`NativeToolSmoke.java`](../../scripts/dev/verify/reliability/fixtures/NativeToolSmoke.java) 经 stdin 送入
Daemon 容器编译并运行 find/grep/bash assertions，不经过 Agent、Provider 或 App command batch。

真实 Agent runner 只在显式执行时调用 Provider：

```bash
node scripts/dev/verify/reliability/run-agent-matrix.mjs --help
node scripts/dev/verify/reliability/run-agent-matrix.mjs --list
node scripts/dev/verify/reliability/run-agent-matrix.mjs --only CASE_ID
node scripts/dev/verify/reliability/reassess-agent-run.mjs <runId>
```

| Flag | 默认/语义 |
| --- | --- |
| `--list` | 列出冻结矩阵，不做 HTTP/model call |
| `--only CASE_ID` | 可重复选择 case |
| `--base-url URL` | `http://127.0.0.1:18091` |
| `--daemon-env NAME` | `docker-reliability` |
| `--report-root DIR` | `reports/reliability` |
| `--max-cost-usd N` | `0..5`，硬上限为 USD 5 |

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

| 场景 | 并发 | 请求 | error | p95 | throughput RPS | 最少样本 |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| `health` | 16 | `GET /actuator/health`，验证 `status=UP` | 0 | `<=250ms` | `>=50` | 20 |
| `catalog` | 16 | `GET /api/ai/catalog/models?pageNumber=1&pageSize=20`，验证严格 catalog fields | 0 | `<=500ms` | `>=25` | 20 |
| `canvas` | 4 | `POST /api/canvases` 后 `DELETE /api/canvases/{id}`，验证 UUID/decimal version | 0 | `<=1500ms` | `>=5` | 20 |

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

- `NVD_API_KEY` 可选。有 key 时脚本在临时目录创建 mode `600` 的 `settings.xml`（server id
  `kk-studio-supply-chain-nvd`），Maven 进程不继承 key，key 不进入 command line、POM、summary 或
  log；无 key 时使用 NVD 官方 JSON 2.0 feed。
- 在线源、Maven Central、npm registry、NVD 或 report generation 不可用时保持 `FAIL`；缺失数据
  不能生成 `PASS`。策略是零 suppression 与零漏洞，Dependency-Check 与 npm audit 都不配置白名单。
- Trivy image 固定为脚本中的 immutable digest，并校验版本 `0.74.0`；扫描过滤 `HIGH,CRITICAL`
  且忽略无修复版本的条目。cache volume 默认 `kk-studio-trivy-cache`，可用
  `SUPPLY_CHAIN_TRIVY_CACHE_VOLUME` 覆盖；已有完整 cache 时设置 `TRIVY_SKIP_DB_UPDATE=true`，
  cache 不完整仍失败。
- Trivy 非零、JSON 缺失或不可解析、二次解析发现任一 HIGH/CRITICAL、镜像 smoke 失败或默认 user
  为 root，都保持 `FAIL`。

`image` 构建的镜像与可覆盖 tag：

| 镜像 | Dockerfile | 默认 tag | 覆盖变量 |
| --- | --- | --- | --- |
| App | [`deploy/local/Dockerfile`](../../deploy/local/Dockerfile) | `kk-studio-app:supply-chain` | `SUPPLY_CHAIN_APP_IMAGE` |
| Daemon | [`deploy/reliability/daemon.Dockerfile`](../../deploy/reliability/daemon.Dockerfile) | `kk-studio-daemon:supply-chain` | `SUPPLY_CHAIN_DAEMON_IMAGE` |

App smoke 在默认 non-root user 下检查 Java、`ffmpeg`、`ffprobe`、`curl`；Daemon 额外检查 Node
`v22.19.x`、npm `11.19.0`、bash、git，并用一次 `npm install --package-lock-only` 验证 npm 工具链。

## 本机 preview 与 NAS 自迭代

自迭代使用 NAS 上共享数据面的单个 App 容器 `vps-kk-studio`：它以 `prod` profile 常驻，是共享
数据库唯一的 Flyway owner，也是唯一的 Harness worker。镜像、挂载与变量契约见
[部署与运行](deployment.md#nas-运行拓扑)。

| 面 | 位置 |
| --- | --- |
| 异步 Work（Thread/Model/Tool processor，含 Tool 执行） | 仅 `vps-kk-studio` 的 worker |
| 本机 preview 的 Vite/HMR、同步 HTTP API、查询投影、应用事件 WebSocket | 笔记本上的 Backend 与 Vite |
| Environment 的宿主能力 | 笔记本（或其它主机）上的 Environment Daemon |

Human 在本机 preview 提交命令时，同步 HTTP 处理使用当前工作区代码，随后产生的异步 Harness Work
使用 NAS 上正在运行的镜像，同一用户流程明确允许跨两个版本边界；共享 PostgreSQL/S3 是唯一数据
事实源，不为本机 preview 复制数据，也不为短期版本错位增加运行时兼容层。兼容时直接继续迭代；
schema、持久 JSON 或 Work wire 确实不兼容时，先把 NAS App 推进到当前 revision，必要时按下文重建
共享数据库，再恢复本机 preview。修改 processor/runtime 等异步执行路径不会在本机 preview 中
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

Environment Daemon 不属于 NAS App 容器。需要在某台主机上执行文件、命令与检索时，规范路径是在那台
主机 clone 源码并通过平台对应的安装脚本常驻（Linux/macOS 用
[scripts/daemon/install.sh](../../scripts/daemon/install.sh)，Windows 用
[scripts/daemon/install.ps1](../../scripts/daemon/install.ps1)），连接 NAS App 的 gateway
`wss://<studio-origin>/api/harness/environment-daemon/v1`；前置条件、注册 token 文件、构建安装、
升级与卸载见 [Environment Daemon 安装与运行](environment-daemon.md)。

### 共享数据库重建

共享数据库的维护与升级采用职责分离的三段式工作流。旧有的单体脚本已被三个独立的公开入口替代：

- [scripts/ops/export-agent-catalog.sh](../../scripts/ops/export-agent-catalog.sh)：只读导出 durable Agent catalog（Provider / Model / Agent 定义）为版本化包；
- [scripts/ops/reset-database.sh](../../scripts/ops/reset-database.sh)：安全备份旧库、重命名冻结并以原元数据创建同名空库；
- [scripts/ops/import-agent-catalog.sh](../../scripts/ops/import-agent-catalog.sh)：在应用执行 Flyway V1 后的空表上单事务回灌 catalog 包。

三个入口共享底层连接封装 [scripts/ops/lib/database-maintenance.sh](../../scripts/ops/lib/database-maintenance.sh) 与数据结构投影 helper [scripts/ops/agent_catalog.py](../../scripts/ops/agent_catalog.py)（两者均为私有实现，非公开入口）。

#### 架构基线与自迭代禁令

代码仓库只保留完整声明当前结构的 [`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql)，不维护增量 migration 链。修改 V1 必须先停止应用，并在 Human 明确批准的维护窗口内重建空库。**普通自迭代严禁重置共享 database 或删除共享 bucket**。

#### 迁移范围与影响

维护脚本**仅迁移**以下三张持久 catalog 表（按外键依赖顺序）：

1. `agent_provider`
2. `agent_model`
3. `agent_definition`

以下数据与状态**明确不迁移**：
- `environment` 注册行：不迁移，且**不可自动恢复**。注册令牌（`registration_token`）只存在于这一行里，因此重建后既有的 Daemon token 文件一定无法再通过认证：维护人员必须为每个环境重新创建 Environment Card、把新令牌写回对应主机的 token 文件，随后 Daemon 才能重连并重建 `environment_connection` 这一行运行投影。仅当 Daemon 带着有效令牌重连时，`environment_connection` 才会自动重建；
- `skill_package` 与 `plugin_credential`：不迁移。重建后需由管理员或用户重新创建；
- Platform MCP 配置（`mcp_server`、`mcp_tool`）：不迁移。重建后按需重新创建配置或重新发现；
- `system_setting`：不迁移。应用启动时由 V1 自动插入一行安全的默认聚合配置；
- 全部运行时数据：Chat、Canvas、Project、Issue、Harness Work/Thread/Session、Storage Blob/Upload 等运行事实均不保留。

详细数据说明见 [Schema 模块](../modules/schema.md#修改-v1-的代价)。

#### 连接契约与环境配置

维护脚本全部通过系统已安装的原生 libpq 客户端（`psql`、`pg_dump`、`pg_restore`、`createdb`）与继承的连接设置访问数据库：
- **无容器与应用依赖**：脚本不依赖 Docker，不假设容器名称，不依赖主应用容器，不假设 SSH 配置，不解析 JDBC URL，绝不通过命令行参数传递口令；
- **非交互式强制失败**：所有 libpq 命令一律附带 `--no-password`，凭据缺失或错误时立即报错退出，绝不挂起等待交互式口令输入；
- **纯数据库标识符**：`PGDATABASE` 若指定必须为纯 PostgreSQL 标识符（字母、数字、下划线），显式拒绝 URI 或包含口令的 conninfo 连接串，防止连接歧义与日志回显泄露；未指定时默认使用 libpq 连接的当前库（`select current_database()`）；
- **维护数据库**：只有 `reset-database.sh` 需要连接非目标库执行改名与建库，通过 `KK_STUDIO_MAINTENANCE_DB` 指定非敏感的维护库名（默认 `postgres`）；导出与回灌只连接目标库本身；
- **版本边界**：部署基线是 PostgreSQL 17。原生客户端必须与服务端兼容，且 `pg_dump` 不得比服务端旧（用旧客户端 dump 新服务端会被拒绝或漏掉新特性）；`reset-database.sh` 依赖 `createdb --locale-provider`（PostgreSQL 15+）与 `ALTER DATABASE ... ALLOW_CONNECTIONS`（PostgreSQL 14+），因此 reset 面向 15+ 服务端与 15+ 客户端；
- **外部产物路径**：所有备份、catalog 包与失败日志必须落在代码仓库之外，且目录强制赋予 `0700`、文件赋予 `0600` 属主独占权限。默认基准路径为 `${XDG_STATE_HOME:-$HOME/.local/state}/kk-studio/maintenance`（可通过 `KK_STUDIO_MAINTENANCE_DIR` 覆盖），各入口对应子路径默认为 `catalog`（`KK_STUDIO_CATALOG_DIR`）、`backup`（`KK_STUDIO_RESET_DIR`）与 `log`（`KK_STUDIO_IMPORT_DIR`），亦可使用各脚本的 `--work-dir` 参数覆盖。

#### 部署拓扑与连接传输

脚本运行在本地机器（如运维工作站或宿主机），四种典型部署拓扑仅作为可选的网络传输手段：

| 拓扑场景 | 连接配置方式 | 说明 |
| --- | --- | --- |
| 1. 直接网络连接（TCP/TLS） | 配置 `~/.pg_service.conf` 服务节与 `~/.pgpass` | 运维机直接通过网络连接目标 PostgreSQL，备份与导出包存放在运维机 |
| 2. 堡垒机 / SSH 隧道 | 本地端口转发：`ssh -N -L 54322:127.0.0.1:5432 user@bastion` | 脚本在运维工作站运行并通过 `127.0.0.1:54322` 连接，敏感产物完整保留在工作站 |
| 3. Docker 宿主端口发布 | 容器将端口映射到宿主（如 `127.0.0.1:5432`） | 脚本在宿主机运行并通过本地端口与 libpq 客户端连接，不需要 Docker 容器内的客户端 |
| 4. 托管或云 PostgreSQL 实例 | 注入 `PGSERVICE` 或标准 `PGHOST`/`PGPORT`/`PGUSER`/`PGDATABASE` 环境变量 | 脚本直接访问云服务商提供的数据库端点 |

脚本绝不要求 Docker，绝不要求主应用容器处于运行状态。通过 SSH 隧道执行时，备份和包文件完整保存在执行脚本的机器本地。

**适用边界**：公司自管的非 SSH 直连（含 TLS）部署只要网络可达、且连接角色具备库与角色的生命周期权限，三个脚本都完整可用；托管服务若禁止 `ALTER DATABASE ... RENAME` 或 `CREATE DATABASE`（部分全托管实例），`reset-database.sh` **不可用**：此时导出与回灌仍然可用，重建空库这一步必须改用服务商提供的生命周期能力（例如控制台的 reset/restore 或服务商 CLI），之后照常执行步骤 4 起的流程。

**NAS 部署的具体编排**：NAS 上的 App 与数据库由 `vps-dockers-scripts-v2` 管理，停机与启动属于该编排，不属于便携数据库脚本。下面的命令按顺序对应 [标准执行流程](#标准执行流程) 的步骤 1、4、5、7，其中 `~` 是 NAS 上的运维账号家目录：

```bash
# 步骤 1：在 NAS 上停止 App（保留数据库容器）——外部编排命令，位于 vps-dockers-scripts-v2
cd ~/vps-dockers-scripts-v2 && ./vps-docker-compose.sh docker/nas stop vps-kk-studio
```

随后在运维工作站本地执行三个便携脚本。若工作站无法直连 NAS 数据库，先用 SSH 隧道把数据库端口映射到本地（对应上表拓扑 2）：

```bash
# 可选：在单独终端保持此前台进程，把 NAS 数据库端口映射到本机 54322
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:54322:127.0.0.1:5432 <nas-user>@<nas-host>
# 本地 libpq 连接设置（无口令入 argv）：口令文件保持 0600，真实口令只写在 ~/.pgpass
export PGSERVICE=kk_studio_nas PGPASSFILE="$HOME/.pgpass"
# 服务节示例：host=127.0.0.1 / port=54322（直连时改为 5432）/ dbname=kk_studio / user=<owner-role>

# 步骤 2：导出 catalog 包（只读）
./scripts/ops/export-agent-catalog.sh --dry-run
./scripts/ops/export-agent-catalog.sh
# 步骤 3：备份旧库、冻结并重建空库
./scripts/ops/reset-database.sh --dry-run
./scripts/ops/reset-database.sh
```

```bash
# 步骤 4：在 NAS 上启动 App，让 Flyway 在空库上执行 V1——外部编排命令
cd ~/vps-dockers-scripts-v2 && ./vps-docker-compose.sh docker/nas up -d --no-deps vps-kk-studio
# 等待 vps-kk-studio 健康检查通过，确认 Flyway 已完成后再执行步骤 5
./vps-docker-compose.sh docker/nas ps vps-kk-studio
# 步骤 5：再次停止 App，保证回灌时三张表无并发写入——外部编排命令
cd ~/vps-dockers-scripts-v2 && ./vps-docker-compose.sh docker/nas stop vps-kk-studio
```

```bash
# 步骤 6：本地单事务回灌
./scripts/ops/import-agent-catalog.sh --package <package-dir> --dry-run
./scripts/ops/import-agent-catalog.sh --package <package-dir>
# 步骤 7：在 NAS 上恢复服务——外部编排命令
cd ~/vps-dockers-scripts-v2 && ./vps-docker-compose.sh docker/nas up -d --no-deps vps-kk-studio
```

`docker/nas` 参数是编排脚本约定的 NAS compose 项目目录，`app.env` 与其他私密文件由该仓库在运行时注入，本仓库的维护脚本既不读取也不关心它们。

推荐的属主独占服务与免密凭据配置：

```bash
# 1. 创建（已有文件不清空）并编辑属主独占的服务配置文件
touch ~/.pg_service.conf && chmod 600 ~/.pg_service.conf
# 编辑 ~/.pg_service.conf 添加目标配置节：
# [kk_studio_prod]
# host=127.0.0.1
# port=5432
# dbname=kk_studio
# user=postgres

# 2. 创建（已有文件不清空）并编辑属主独占的口令文件（格式：host:port:database:user:password）
touch ~/.pgpass && chmod 600 ~/.pgpass
# 编辑 ~/.pgpass 添加连接凭据：
# 127.0.0.1:5432:*:postgres:YOUR_SECRET_PASSWORD

# 3. 指定服务名与口令文件环境变量
export PGSERVICE=kk_studio_prod
export PGPASSFILE="$HOME/.pgpass"
```

口令文件必须是 `0600` 且只有属主可读；`sslmode`、证书路径等 TLS 设置同样通过连接设置继承（脚本不覆盖它们）。

#### 权限要求与安全规范

- **重置权限**：`reset-database.sh` 需要能改目标库、能建库、并能把新库交给原 owner 的角色，**不要求 superuser**。具体检查（全部只读，缺哪一项就只读失败）：
  - 目标库的 owner 必须就是当前连接角色（或当前角色是 superuser）——`ALTER DATABASE ... RENAME` / `ALLOW_CONNECTIONS` 要求库所有权；
  - 非 superuser 角色必须拥有 `CREATEDB`；
  - 非 superuser 角色必须能对原 owner 角色 `SET ROLE`（即拥有其成员资格），否则 `createdb --owner=<原 owner>` 会被拒绝；
  - `pg_dump` 必须能读取库内全部表（应用角色通常就是这些表的所有者，因此以该角色连接即可）；这一项由备份步骤在**任何变更之前**自然验证，读不到就直接失败并保留原库；
- **导出与回灌权限**：`export-agent-catalog.sh` 与 `import-agent-catalog.sh` 只需要对三张 catalog 表拥有读/写权限，回灌时还需要读取 `flyway_schema_history`；两者都**不需要**访问维护数据库；
- **敏感凭据安全**：导出的 catalog 包（含 Provider 凭据明文）与旧库冻结快照/备份属于敏感数据，绝不可提交到 Git、写进文档、粘贴到日志或工单，亦不可上传至公共存储；维护结束并确认系统恢复后，由 Human 依据部署策略安全处置或销毁。

#### 标准执行流程

维护操作严格按以下八步顺序执行：

```text
1. 停止写入端（停止应用服务）
  -> 2. 导出 catalog 包（export-agent-catalog.sh）
  -> 3. 备份旧库、冻结并重建空库（reset-database.sh）
  -> 4. 正常启动应用，由 Flyway 在空库上执行 V1
  -> 5. 再次停止应用，确保无并发写入
  -> 6. 单事务回灌 catalog 包（import-agent-catalog.sh）
  -> 7. 启动应用恢复正常对外服务，验证健康状态并重新登记 Environment/Daemon
  -> 8. 按策略归档或清理备份快照与包文件
```

##### 步骤 1：停止应用服务

在维护窗口内停止所有 kk-studio 应用实例，确保没有任何写入端正在向数据库提交事务。

##### 步骤 2：导出 Agent Catalog 包

```bash
# 只读预检；探测源库结构与数据行数，不写出文件
./scripts/ops/export-agent-catalog.sh --dry-run

# 执行导出
./scripts/ops/export-agent-catalog.sh
```

- **参数与选项**：
  - `--work-dir PATH`：指定包输出根目录（默认：`${XDG_STATE_HOME:-$HOME/.local/state}/kk-studio/maintenance/catalog`）；
  - `--dry-run`：只读模式，探测源库结构并输出计划，不写出任何文件。
- **行为与校验**：
  - 结构识别只读三张持久 catalog 表的列集合：`agent_definition.environment_id` 是否存在决定源结构是 `legacy-main` 还是 `current`，随后按该结构要求三张表的列集合完全精确匹配；skill、plugin、environment 等**不迁移**域的表是否存在与结构识别无关（结构不匹配时直接失败，不做降级推测）；
  - 若为 `legacy-main`，将 Agent 配置映射为当前契约：`toolIds` 映射为内建名称或已发现的 `mcp_tool.model_name`，`subagents` 原样保留，`inheritParentEnvironment` 设为 `true`；
  - **Fail-Closed 规则**：结构未知或不完整（列缺失、多出列或不匹配任一结构）、遗留 `skills` 引用非空（环境绑定 Skill 来源不是全局 Git 仓库包）、工具无法映射或产生重名、配置非法等情况立即报错中止；导出期间对比生成 bundle 前后的数据指纹，若检测到源库数据发生变动则直接终止导出，并删除已写出一半的包目录；
  - **产物**：在输出目录下创建以 UTC 时间戳命名的目录（权限 `0700`），包含 `catalog.sql`（`0600`，COPY 格式 bundle）、`manifest.json`（`0600`，非敏感元数据、源库结构、V1 checksum、行数与指纹）与 `sha256sums.txt`（`0600`，bundle sha256 校验文件）；输出信息仅包含结构类别、行数与指纹，绝不打印行内容。

##### 步骤 3：重置目标数据库

```bash
# 只读预检与重置计划；不备份、不冻结、不改库
./scripts/ops/reset-database.sh --dry-run

# 执行重置（交互式输入数据库名称以确认）
./scripts/ops/reset-database.sh

# 自动化执行（跳过交互确认）：
# ./scripts/ops/reset-database.sh --yes
```

- **参数与选项**：
  - `--work-dir PATH`：指定备份文件存放目录（默认：`${XDG_STATE_HOME:-$HOME/.local/state}/kk-studio/maintenance/backup`）；
  - `--yes`：跳过交互式输入数据库名确认；
  - `--dry-run`：只读预检并打印重置计划。
- **行为与安全机制**：
  - **只读预检**：目标库必须存在、非模板库、允许连接、无其他活跃连接会话（发现其他会话时直接拒绝并提示停止写入端，绝不主动 kill 连接）、无自定义 ACL 或角色配置、目标库与维护库不同名、快照名与本次运行的临时建库名都未被占用、当前角色具备上文权限要求里的各项能力；实际执行在确认后、写备份前创建 owner-only 目录，并要求文件系统可用空间不低于目标库体积加 64 MiB；
  - **交互式确认**：未提供 `--yes` 时，必须由操作人员手动输入目标数据库名方可继续；
  - **全量安全备份**：使用 `pg_dump --format=custom --create` 写入完整备份并生成 sha256 校验文件，备份生成后立即通过 `pg_restore --list` 校验归档完整性；
  - **冻结旧库**：将原数据库原地重命名为 `<db>_pre_<UTCstamp>`，并设置 `allow_connections=false` 禁止任何后续连接；禁连后再次检查快照库的活动会话，若有写入端在预检后抢先接入则立即安全失败、解冻并恢复原库名，脚本绝不主动终止该会话；脚本**不提供** `--skip-snapshot` 选项，成功重置后快照库必须完整保留；
  - **重建空库**：以原库的 owner、encoding、locale provider/settings（`libc`、`icu`、`builtin` 及对应的 collate/ctype/icu-rules）、tablespace 与 connection limit 创建全新空库。由于 `createdb` 没有 connection limit 选项，脚本先在临时名 `<db>__kk_partial_<UTCstamp>` 下建库并套用连接数限制，最后把它改名为目标库名：目标库名要么不存在，要么已经是一个完整可用的空库，不会短暂暴露一个尚未套用连接数限制的新库；
  - **失败回滚**：任何一步失败都回到「目标库名指向原有数据」的状态。若新库已经建出（无论是否已改名），脚本先 `DROP DATABASE ... WITH (FORCE)` 清掉它（此时它是空库），再把快照库 `allow_connections` 打开并重命名回目标库名；顺序不可颠倒，否则改名会因目标库名被占用而失败。只有在改名回退本身失败时才停止自动修复，并明确报告数据现存于哪个快照库与哪个备份文件。

##### 步骤 4：启动应用执行 Flyway V1

正常启动应用。应用启动过程中，Flyway 自动在全新的空数据库上执行 [`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql)，建立完整的表结构与默认设置，并在 `flyway_schema_history` 表中写入 V1 记录与 checksum。应用必须由当前 revision 构建的镜像启动：回灌会把仓库 V1、包 manifest 与目标库 `flyway_schema_history` 三者的 checksum 精确比对，不一致时拒绝回灌并保留空库。

##### 步骤 5：再次停止应用

在回灌 catalog 前短暂停止应用节点，确保目标库中的三张 catalog 表处于无任何并发写入的干净状态（race-free）。

##### 步骤 6：回灌 Agent Catalog 包

```bash
# 校验包完整性与目标库状态；不写入数据
./scripts/ops/import-agent-catalog.sh --package <package-dir> --dry-run

# 执行单事务回灌
./scripts/ops/import-agent-catalog.sh --package <package-dir>
```

- **参数与选项**：
  - `--package PATH`（必填）：步骤 2 导出的时间戳包目录路径；
  - `--work-dir PATH`：指定脱敏失败日志目录（默认：`${XDG_STATE_HOME:-$HOME/.local/state}/kk-studio/maintenance/log`）；
  - `--dry-run`：校验包与目标库状态，不修改数据库。
- **行为与校验机制**：
  - **严格校验**：校验包目录与每个产物的属主独占权限（组/其他可读即拒绝）、产物不得是符号链接、manifest 字段类型严格（行数为非负整数、结构名与库名为字符串）、包内 manifest 与 bundle 文件的 sha256 摘要；校验本地当前代码 revision 的 V1 checksum、包 manifest 中的 V1 checksum、以及目标库 `flyway_schema_history` 表记录的 V1 checksum 三者精确相等；要求目标库列集合精确为当前 V1 形状，且 `agent_provider`、`agent_model`、`agent_definition` 三张表行数必须全为 0；
  - **事务内权威校验**：预检的空表检查只是提前拒绝。真正的恢复在 `psql --single-transaction -v ON_ERROR_STOP=1` 中执行 bundle，bundle 自身先取得三张表的排他锁（锁等待上限 5 秒，超时即失败）并复查三张表为空，再逐表 COPY，最后在提交前逐表比对与包一致的期望指纹；并发写入、脏表或指纹不符都会让整次事务回滚并保持事务开始前的状态（原本为空则仍全空），绝不提交与包不符的数据；
  - **非事务执行被拒绝**：`LOCK TABLE` 只能在事务块内执行，因此若有人手工用 `psql -f` 而不带 `--single-transaction` 运行 bundle，PostgreSQL 会直接报错中止；
  - **日志脱敏保护**：bundle 使用 `\set VERBOSITY sqlstate`，恢复期间 stdout 全部丢弃；若回灌失败，仅在 `--work-dir` 下保留一份权限为 `0600` 的脱敏失败日志，其中只有 SQLSTATE 错误码与脚本自己映射的安全类别说明（如 `check-constraint violation (SQLSTATE 23514)`），绝不保留可能引用行值的 PostgreSQL 原始报文，也不保留执行语句；
  - **提交后复核**：提交成功后脚本会再次计算三张表的内容指纹作为纵深防御；此时不符只可能来自提交后的并发写入，脚本会按「导入后 catalog 被并发修改」报告，并提示该次导入本身已在事务内校验通过；
  - **完全独立**：脚本不启动、不停止、也不检查应用，操作人员自主掌控应用启停节奏。

##### 步骤 7：恢复服务并验证

启动应用恢复对外正常服务：
1. 访问应用健康检查端点验证系统就绪；
2. **重新登记执行环境**（`environment` 行与注册令牌都不会迁移）：在应用里为每个环境重新创建 Environment Card，并把它签发的新注册令牌写入对应主机上的 Daemon token 文件（替换旧文件），然后重启/重连 Daemon。只有完成这一步 Daemon 才能通过认证，并重建 `environment_connection` 这一行运行投影；旧 token 文件在重建后一定认证失败，不要期待 Daemon 自动重新注册；
3. 根据业务需要重新创建 Skill packages、Plugin 凭据或 Platform MCP 配置。

##### 步骤 8：快照与敏感产物清理

导出的 catalog 包与全库冻结快照/备份包含真实凭据，在确认业务完全正常后，依据部署侧数据保留策略，由 Human 手动删除或离线归档冻结快照库（`<db>_pre_<UTCstamp>`）与本地临时包文件。

### 自迭代闭环

Agent 在本机 preview 上遵循以下闭环：

1. 开始前检查 Git 状态并保留 Human 的并行修改，不覆盖未提交工作。
2. 对实际变更执行定向测试；Java 关键路径同时遵守覆盖率门禁。
3. 普通 Frontend 变更由 Vite HMR 生效；Java 变更先构建，再用 `./scripts/dev/shared-preview.sh restart`
   重启受管的 Backend/Vite，不需要重建任何容器。
4. 重启前提交源码和必要的 durable 进度；重启只在当前回合内短暂中断 preview 的连接。
5. 验证 Backend health、Frontend、应用事件 WebSocket 后再继续下一轮。
6. 功能达到可验收状态后提交并 push 目标分支，同时报告变更、验证和已知风险；涉及
   processor/runtime 等异步执行路径的改动必须附带自动化测试证据。

完整协作顺序是：

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
先更新 NAS 镜像，涉及 V1 时在已批准的维护窗口重建共享数据库：

- 未合入 `main` 的 Flyway migration 或破坏性 schema 变更；
- 删除或重命名持久 JSON 字段、数据库枚举值或 wire 字段；
- 改变 Work/Invocation 状态机、claim/lease/fencing 语义；
- 改变 S3 object key、Blob 引用计数或 cleanup 生命周期；
- 需要重建镜像与重启 NAS 容器，或使共享 durable 状态在旧镜像下不可读的数据变更。

版本错位本身不是错误，也不要求每次本机修改都先发布镜像；只有实际触发上述不兼容边界时才收敛
版本。仓库不为旧 NAS 镜像保留兼容 shim。

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

## 清理与故障排查

开发循环自己的清理是 `./scripts/dev/shared-preview.sh stop` 或 `./scripts/dev/app.sh stop`；本地与测试栈、
分布式栈和可靠性栈的清理命令、
保留与删除语义见[部署与运行](deployment.md#清理)。[deploy/test](../../deploy/test/README.md) 与
performance 入口每次运行都会自行清理 PostgreSQL/MinIO/test network，`--distributed` 入口在退出时
清理双节点栈。

| 现象 | 先执行 | 边界 |
| --- | --- | --- |
| JDK/compile/checkstyle 失败 | `"$JAVA_HOME_21/bin/java" -version`；`env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp validate` | 必须是 JDK 21；先修复 Spotless/Checkstyle |
| Frontend 找不到依赖或 Playwright | `npm --prefix frontend ci`；`npm --prefix frontend run test` | 依赖由 [`package-lock.json`](../../frontend/package-lock.json) 固定 |
| dev 端口占用 | `./scripts/dev/app.sh status`；`ss -ltnp \| grep -E ':18080\|:5173'` | 用 `DEV_KILL_PORTS=true` 或换端口 |
| 本机 preview 启动即失败 | `./scripts/dev/shared-preview.sh status`；核对 `SHARED_PREVIEW_ENV_FILE` 指向的文件 | 必须是绝对路径的 owner-only 普通文件；权限、属主、未知键或缺值都 fail closed，输出不回显文件内容 |
| local app unhealthy | `docker compose -f deploy/local/compose.yaml ps`；`docker compose -f deploy/local/compose.yaml logs app postgres` | 先确认 PostgreSQL health，再检查 `/actuator/health` |
| Canvas test 健康失败 | `docker compose -f deploy/test/compose.yaml ps`；`docker compose -f deploy/test/compose.yaml logs` | 检查 MinIO bucket、mock `/health`、ffmpeg/ffprobe |
| E2E 只跑少数 case | `node scripts/dev/verify/e2e/run-matrix.mjs --list`；确认 `--real`、`--with-tools`、`--with-canvas-storage`、`--with-canvas-function` | 通过 `requires` 和 level 过滤是当前行为 |
| 真实 Provider 不可用 | 检查 8 个 `TEST_{GOOGLE,OPENAI,ANTHROPIC,DEEPSEEK}_{BASE_URL,API_KEY}` 变量是否均非空 | 必须显式 `--real`，只用宿主同步器；不要放入 Compose/image/container |
| reliability 环境未 READY | `./scripts/dev/verify/reliability/stack.sh status`；`./scripts/dev/verify/reliability/stack.sh logs app daemon` | `inspect` 先检查 non-root、volume 和 gateway |
| 敏感数据门禁失败 | `python3 scripts/dev/verify/repository/check-sensitive-data.py` | 只按输出的规则和位置排查；不要把完整敏感值复制到日志或 Issue |
| performance/supply-chain 失败 | 阅读 `reports/performance/latest/report.md` 或 `reports/supply-chain/latest/summary.md` | 阈值、在线源、JSON 完整性和 zero-vulnerability 都不能放宽 |
| loopback proxy 下 build 失败 | 检查 `HTTP_PROXY`/`HTTPS_PROXY`、`CANVAS_TEST_BUILD_NETWORK`；再执行 `./scripts/dev/verify/smoke/offline-chat.sh --with-app` | loopback proxy 使用 host build network，代理值不进入镜像 |

---

上级：[系统设计](../system-design.md)。相关文档：[部署与运行](deployment.md)、
[本地一键启动栈](../../deploy/local/README.md)、[Canvas/Storage 隔离测试栈](../../deploy/test/README.md)、
[Environment Daemon 安装与运行](environment-daemon.md)。
