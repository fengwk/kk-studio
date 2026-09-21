# 开发与测试

本文面向修改本仓库的开发者：准备工作站、跑日常开发循环、按意图选择质量检查，并说明 E2E、
可靠性、性能、供应链和 NAS 自迭代的操作方式。Fat JAR、Compose 栈和生产拓扑见
[部署与运行](deployment.md)；跨模块边界和全局不变量见[系统设计](../system-design.md)。

## 前置条件

| 工具 | 用途与约束 |
| --- | --- |
| JDK 21 | 所有 Maven 命令显式使用 `JAVA_HOME_21`；根 POM 的 `maven.compiler.release` 是 `21` |
| Maven | 通过 `mvn` 可用；[`scripts/dev.sh`](../../scripts/dev.sh)、[`scripts/e2e/lib.sh`](../../scripts/e2e/lib.sh)、[`regression.sh`](../../scripts/reliability/regression.sh)、[`scripts/supply-chain.sh`](../../scripts/supply-chain.sh) 都会校验 JDK 21 |
| Node 与 npm | Frontend 依赖由 [`package-lock.json`](../../frontend/package-lock.json) 固定；`distribution` profile 会自动安装 Node `v24.14.0` 与 npm `11.9.0` |
| Docker 与 Compose v2 | 本地栈、测试栈、性能基线和镜像扫描需要 |
| `curl`、`jq`、`lsof` | [scripts/dev.sh](../../scripts/dev.sh) 启动前后检查端口与健康状态 |
| Python 3 | E2E 与测试栈的 smoke 脚本 |

数据库与服务由容器提供：[deploy/local](../../deploy/local/README.md) 覆盖主要本地路径，
[deploy/test](../../deploy/test/README.md) 提供隔离离线栈。开发循环默认连接 PostgreSQL，
因此先把其中一个栈拉起来。

## 日常开发循环

[scripts/dev.sh](../../scripts/dev.sh) 是 Backend 与 Vite 的统一入口：

```bash
./scripts/dev.sh start
./scripts/dev.sh status
./scripts/dev.sh logs all
./scripts/dev.sh stop
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

`e2e` profile 启用时，宿主同步器会按需读取 Google、OpenAI Responses、MiniMax Anthropic 和
DeepSeek 四组完整 credential pair，经 backend API 写入 E2E database 中对应的 seed Provider
row。Backend、Vite 和 Daemon 长驻进程都显式移除 `TEST_*` 变量，这些变量只向短生命周期的同步器
透传；credential 不进入 seed SQL/resource，密钥与 endpoint 不打印。

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
| 隔离栈端到端 smoke | `./deploy/test/run.sh --with-app` |
| 免费 API 契约矩阵 | [`./scripts/e2e.sh`](../../scripts/e2e.sh) |
| 确认矩阵有哪些 case | `./scripts/e2e.sh --list`、`./scripts/e2e.sh --docs` |
| 文档与敏感数据门禁 | `node scripts/docs/check.mjs`、`python3 scripts/security/check-sensitive-data.py` |
| 可靠性确定性回归 | `./scripts/reliability/regression.sh --iterations 1` |
| 离线性能基线 | `./scripts/performance.sh` |
| 供应链 SBOM / 漏洞门禁 | `./scripts/supply-chain.sh all` |

真实 Provider、真实 Tool、UI、分布式和镜像扫描都只在显式开关下运行，默认路径不产生模型费用。

越靠上的检查越便宜，越靠下的越接近真实环境；日常改动先跑上两行，涉及契约或执行路径时再往下走：

| 目的 | 入口 |
| --- | --- |
| 静态与格式门禁 | `mvn validate`、`npm --prefix frontend run lint` |
| Java 与 Frontend 单测、覆盖率 | `mvn test`、`mvn verify`、`npm --prefix frontend run test\|coverage` |
| 免费端到端契约 | `./scripts/e2e.sh`、`./deploy/test/run.sh --with-app` |
| 真实 Provider、Tool、UI、分布式栈 | `./scripts/e2e.sh --real`、`--with-tools`、`--ui`、`--distributed` |
| 可靠性、性能、供应链 | [`scripts/reliability`](../../scripts/reliability/)、`./scripts/performance.sh`、`./scripts/supply-chain.sh` |

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
case inventory 由 `node scripts/e2e/run-matrix.mjs --list` 与 `--docs` 提供，不在文档里复制。

## Compose、静态资源与文档门禁

```bash
docker compose -f deploy/local/compose.yaml config --quiet
docker compose -f deploy/test/compose.yaml config --quiet
docker compose -f deploy/test/compose.yaml --profile app config --quiet
docker compose -f deploy/reliability/compose.yaml config --quiet
./deploy/distributed/run.sh verify
```

`deploy/distributed/run.sh verify` 只静态校验双节点 Compose config 与网络不变量，不启动容器。
`./deploy/test/run.sh` 把配置检查、镜像构建、依赖 health、非 root runtime、PostgreSQL、MinIO
bucket 与 HTTP mock smoke 组合成一个可清理入口，`--with-app` 再覆盖全局 Blob、Canvas Resource、
signed GET、fake Function、容器内 OpenCLI fake Hub 与离线 Chat。

Fat JAR 的 static 资源检查由 `-Pdistribution` 的三个插件完成；应用在 `/actuator/health` 通过后
再检查浏览器入口。文档、敏感数据与 Git 空白检查：

```bash
node scripts/e2e/run-matrix.mjs --docs
node scripts/docs/check.mjs
python3 scripts/security/check-sensitive-data.py
git diff --check
```

[scripts/docs/check.mjs](../../scripts/docs/check.mjs) 负责固定文档布局、Markdown 链接、H1、源码
路径和旧词守卫。[check-sensitive-data.py](../../scripts/security/check-sensitive-data.py) 扫描
tracked 文件与非 ignored 未跟踪文件，覆盖高置信密钥、Webhook、个人绝对路径和已知私有环境标识；
命中时只输出规则与 `path:line`，不要回显完整敏感值。该入口不扫描 Git 历史，历史审计是公开策略中
的独立步骤。

## E2E

标准入口是 [scripts/e2e.sh](../../scripts/e2e.sh)：

```bash
./scripts/e2e.sh
./scripts/e2e.sh --rebuild
./scripts/e2e.sh --real
./scripts/e2e.sh --with-tools
./scripts/e2e.sh --real --with-branch
./scripts/e2e.sh --with-canvas-storage
./scripts/e2e.sh --with-canvas-function
./scripts/e2e.sh --real --with-tools --with-canvas-storage
./scripts/e2e.sh --distributed
./scripts/e2e.sh --ui
./scripts/e2e.sh --only CASE_ID
./scripts/e2e.sh --level L1
./scripts/e2e.sh --list
./scripts/e2e.sh --docs
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
`node scripts/e2e/run-matrix.mjs --list` 与 `--docs` 生成，不要把它们抄进文档；默认执行哪些 case
由 flag 组合和 case 的 `requires` 共同决定。

默认 backend URL 是 `http://127.0.0.1:18081`，frontend URL 是 `http://127.0.0.1:5173`。
`--rebuild` 默认允许 Maven 在线解析依赖，只有 `E2E_MAVEN_OFFLINE=true` 时才加 `-o`；
`E2E_WORK_DIR` 默认 `runtime/e2e`，Daemon environment root 默认是其下的 `environment`，可由
`DAEMON_ENV_ROOT` 覆盖。执行 `--ui` 前必须完成 `npm --prefix frontend ci`，因为 Playwright 从
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
./scripts/reliability/regression.sh --help
./scripts/reliability/regression.sh --iterations 1
./scripts/reliability/regression.sh --iterations 3 --report-root reports/reliability
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
./scripts/reliability/stack.sh snapshot
./scripts/reliability/stack.sh case-reset <case-id> <pi|pi-base>
./scripts/reliability/stack.sh case-deps <case-id>
./scripts/reliability/stack.sh tool-smoke
```

`snapshot` 不猜测宿主目录，`PI_ANCHOR` 和 `PI_BASE_ANCHOR` 都必须显式指向 clean Git worktree。
`tool-smoke` 把 [`NativeToolSmoke.java`](../../scripts/reliability/NativeToolSmoke.java) 经 stdin 送入
Daemon 容器编译并运行 find/grep/bash assertions，不经过 Agent、Provider 或 App command batch。

真实 Agent runner 只在显式执行时调用 Provider：

```bash
node scripts/reliability/run-agent-matrix.mjs --help
node scripts/reliability/run-agent-matrix.mjs --list
node scripts/reliability/run-agent-matrix.mjs --only CASE_ID
node scripts/reliability/reassess-agent-run.mjs <runId>
```

| Flag | 默认/语义 |
| --- | --- |
| `--list` | 列出冻结矩阵，不做 HTTP/model call |
| `--only CASE_ID` | 可重复选择 case |
| `--base-url URL` | `http://127.0.0.1:18091` |
| `--daemon-env NAME` | `docker-reliability` |
| `--report-root DIR` | `reports/reliability` |
| `--max-cost-usd N` | `0..5`，硬上限为 USD 5 |

case 集合、模型/变体组合与 tool policy 由 [scripts/reliability/matrix.mjs](../../scripts/reliability/matrix.mjs)
和 [policy.mjs](../../scripts/reliability/policy.mjs) 定义。真实执行前 runner 要求 provider、
model、variant、Tool catalog 和 Environment `READY` 全部匹配；未知 cost、超过上限、测试或工作区
隔离证据缺失都 fail closed。`--real`/`--with-tools` 等 flag 只属于
[`scripts/e2e.sh`](../../scripts/e2e.sh)，不适用于该 runner。

## 性能基线

```bash
./scripts/performance.sh --help
./scripts/performance.sh
./scripts/performance.sh --duration-seconds 5
./scripts/performance.sh --skip-build
./scripts/performance.sh --report-root /tmp/kk-studio-performance
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
./scripts/supply-chain.sh sbom
./scripts/supply-chain.sh audit
./scripts/supply-chain.sh image
./scripts/supply-chain.sh all
./scripts/supply-chain.sh test
./scripts/supply-chain.sh help
```

| 子命令 | 内容 |
| --- | --- |
| `sbom` | backend/frontend CycloneDX JSON SBOM，校验文件非空可解析 |
| `audit` | frontend `npm audit` + Maven OWASP Dependency-Check |
| `image` | 构建 App/Daemon，运行功能 smoke，再用 pinned Trivy 扫描 |
| `all` | `sbom`、`audit`、`image` 的合取结果 |
| `test` | [supply-chain.test.mjs](../../scripts/supply-chain/tests/supply-chain.test.mjs)，不联网 |

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

## NAS Main/Dev 自迭代运行规范

<a name="44-nas-maindev-自迭代运行规范" id="44-nas-maindev-自迭代运行规范"></a>

NAS 自迭代使用共享数据面的两个 App 节点。它们属于同一个 KK Studio 集群，不是数据隔离的测试
环境：

| 节点 | Git branch | Human 入口 |
| --- | --- | --- |
| `vps-kk-studio` | `main` | `https://studio.kk1.fun` |
| `vps-kk-studio-dev` | `dev` | `https://studio-dev.kk1.fun` |

Main 是共享 schema 的唯一 Flyway owner；Dev 使用与 Main 相同的生产 profile，但关闭 Flyway 与
进程内 Harness worker，且不加载 dev/e2e seed。容器、镜像、挂载、控制面 origin、网络地址与
环境变量契约的完整定义见[部署与运行](deployment.md#nas-maindev-外部部署边界)，本文只保留操作者
在 Dev 节点上直接执行的动作与自迭代约束。

异步 Harness 执行只有 Main 一个执行者，因此 Dev 入口是同步 preview 面：

| 面 | 节点 |
| --- | --- |
| 异步 Work（Thread/Model/Tool processor，含 Dev Daemon Environment 的 Tool） | 仅 `vps-kk-studio` 的 worker |
| Dev 入口的 Vite/HMR、HTTP API、查询投影、应用事件 WebSocket | `vps-kk-studio-dev` 的 Backend |

Human 在 Dev 入口提交命令时，同步 HTTP 处理使用 Dev 代码，随后产生的异步 Harness Work 使用
Main 代码，同一用户流程可能跨两个版本边界。Harness 持久状态、Entry JSON、Provider/Tool wire、
Storage 生命周期和数据库约束必须保持向后兼容。Dev 中修改 processor/runtime 等异步执行路径不会
在普通 Dev preview 中生效，这些改动必须由自动化测试或显式隔离环境验证后才能验收；普通 Dev
preview 只覆盖前端、同步 API 和查询行为。

### 共享数据库重建

共享 database 的标准重建入口是
[scripts/operations/rebuild-database.sh](../../scripts/operations/rebuild-database.sh)：

```bash
# 只读预检；不创建文件、不停止容器、不修改 database
./scripts/operations/rebuild-database.sh --dry-run

# 仅在 Human 批准的维护窗口内执行
./scripts/operations/rebuild-database.sh
```

脚本仓库只保留完整声明当前结构的 [`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql)，
不维护增量 migration 链；修改 V1 必须先停止两个 App 节点，并在 Human 明确批准的维护窗口内重建空库。
普通自迭代不得重置共享 database 或删除共享 bucket。执行前必须让 Main 容器指向由当前仓库 revision
构建的镜像但保持停止；脚本会把仓库 V1 的 Flyway checksum 与 Main 实际写入的 checksum 对比，不一致
就停止回灌并保持 App 关闭。

重建要先认清源库属于哪种结构，这一步由只读的
[scripts/operations/database_rebuild_source.py](../../scripts/operations/database_rebuild_source.py)
完成（同一份投影既用于导出 bundle，也用于导出后比对的目标指纹）：

| 源结构 | 判定依据 | 回灌方式 |
| --- | --- | --- |
| current | 六张 durable 表的列集合与当前 V1 完全一致 | 逐列原样搬运 |
| legacy-main | 六张 durable 表的列集合与 `main` 上的旧结构一致 | 按当前 V1 投影后搬运 |

legacy-main 的投影逐字段定义目标值：`toolIds` 改写为当前 V1 的内建名或已发现 `mcp_tool.model_name`，
`subagents` 原样保留，`inheritParentEnvironment` 在旧结构里不存在、按当前默认值 `true` 初始化；只有
旧结构里本来就是空数组的 `skills` 才投影为当前空列表。旧结构的非空 Skill 引用不会被静默清空，而是
视为当前 V1 无法承接的 durable 事实，在预检阶段直接失败；非空 `agent_definition.environment_id`
同样不可表达（环境选择已移到 Thread/Session 侧），作为已废弃事实只在 manifest 中计数。以下情况一律
fail closed，脚本在停止容器、写文件与改库之前就报错退出并保持源库不变：

- 列集合既不匹配 current 也不匹配 legacy-main；
- 旧结构的 Skill 引用非空（当前 V1 没有对应的 durable 事实可承接）；
- 工具标识无法映射到内建名或已发现的 `mcp_tool.model_name`，或映射后出现重名；
- Provider/Agent 配置不是合法对象、字段类型错误、子 Agent 列表非法。

流程固定为：

```text
停止 Main/Dev
  -> 全库 custom-format 安全备份
  -> 按源结构投影导出 durable 配置 bundle
  -> 记录目标库应有的逐表指纹
  -> 旧库原地改名并冻结
  -> 按原 owner/locale 创建空库
  -> Main 执行 V1
  -> 停止 Main
  -> 单事务回灌并逐表比对内容指纹
  -> 启动 Main/Dev
  -> 等待 healthcheck 与 Environment Daemon 重连
```

回灌由 `psql --single-transaction -v ON_ERROR_STOP=1` 执行 bundle，事务、提交与回滚全部交给
PostgreSQL，脚本不自己拼接 `BEGIN`/`COMMIT`：任何一行失败都让整次回灌回到「六张表都空」的状态，
不会留下半份 durable 配置。bundle 内的 `\set VERBOSITY terse` 与自身的错误输出清洗保证失败信息只
包含出错语句的位置和错误类别，不打印行内容、凭据或加密载荷。

哪些表属于 durable 保留集合、哪些运行数据在重建后由 V1 或重连重新生成，由
[Schema 模块](../modules/schema.md#修改-v1-的代价)持有。除 `--dry-run` 外还可用 `--yes` 跳过确认、
`--skip-snapshot` 放弃保留旧库快照（完整备份仍然必须）、`--work-dir PATH` 指定输出目录；不提供
`--skip-snapshot` 时脚本把旧库改名为带时间戳的快照并冻结。默认输出目录在仓库外的
`~/.local/state/kk-studio/database-rebuild`（目录 `0700`、文件 `0600`），脚本拒绝把输出目录设到
仓库内。

备份 archive 含 Provider credential 与 Environment registration token，必须按敏感数据处理：禁止
把它们提交到 Git、写入文档、粘贴到日志或工单、上传到公共存储；维护完成并确认冻结快照的保留策略
后，由 Human 按部署侧备份策略安全处置。

### gh 首次登录

`gh` 使用持久 volume 内的默认配置目录 `/home/kkdaemon/.config/gh`，在 NAS 上执行一次交互式登录
即可；host key 校验与 SSH key 安装由 entrypoint 完成，契约见
[部署与运行](deployment.md#挂载与持久化)。

```bash
docker exec -it vps-kk-studio-dev gh auth login --hostname github.com --git-protocol ssh --web --skip-ssh-key --scopes repo,workflow,read:org,gist
docker exec vps-kk-studio-dev gh auth status
```

token 只保存在容器的 `/home/kkdaemon/.config/gh/hosts.yml`，不进入本仓库、镜像、环境变量或日志。
SSH key 只让 Git 能读写仓库，`gh` 的 API 权限继承登录账号自身的权限和上面请求的 scope。

### Dev 重载

普通迭代只运行稳定命令 `kk-studio-dev-reload`：它做增量 package 后重启受管 Backend/Vite，
Daemon 与容器保持存活，Main 与 Daemon 的连接不中断，并等待两端 readiness。容器内等价手写路径：

```bash
cd /workspace/kk-studio
env JAVA_HOME="$JAVA_HOME" mvn -B -ntp -pl web -am -DskipTests package

env SPRING_PROFILES_ACTIVE=prod \
  SPRING_FLYWAY_ENABLED=false \
  KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false \
  BACKEND_HOST=127.0.0.1 \
  BACKEND_PORT=8080 \
  FRONTEND_HOST=0.0.0.0 \
  FRONTEND_PORT=5173 \
  DEV_SKIP_PACKAGE=true \
  ./scripts/dev.sh restart
```

`kk-studio-dev-reload` 与 entrypoint 使用同一组 fail-closed 默认值：即使 ad hoc 覆盖
`KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED`，reload 也会拒绝执行，不会让 Dev Backend 变成第二个
Harness worker。各项默认值与全部 Dev 环境变量的完整契约见
[部署与运行](deployment.md#dev-运行环境变量契约)。

### Agent 闭环

Agent 在 Dev 节点遵循以下闭环：

1. 开始前检查 Git 状态并保留 Human 的并行修改，不覆盖未提交工作。
2. 对实际变更执行定向测试；Java 关键路径同时遵守覆盖率门禁。
3. 普通 Frontend 变更由 Vite HMR 生效；Java 变更先增量构建，再重启 Dev Backend/Vite 受管进程，
   不重启 Main 或 Daemon。只有 Dev 容器、镜像入口或 Daemon 代码变化才重建并重启 Dev 容器，那会
   重建 Main 与 Daemon 的连接。
4. 重启前提交源码和必要的 durable 进度。`kk-studio-dev-reload` 执行时不中断当前 Environment
   Tool，等待 Dev Backend/Vite readiness 后正常返回；浏览器的 HTTP、应用事件与 HMR 连接会在
   reload 期间短暂断开并自动重连。只有重启 Dev 容器或 Daemon 本身时，当前 Tool outcome 才可能
   不确定，这类操作必须是当前 Agent 回合最后一个 Tool 操作。
5. 验证 Dev health、Frontend、应用事件 WebSocket 和 Daemon `READY` 后再继续下一轮。
6. 功能达到可验收状态后提交并 push 目标分支，同时报告变更、验证和已知风险；涉及
   processor/runtime 等异步执行路径的改动必须附带自动化测试证据或显式隔离环境验证。

必须停止自动重启并交给 Human 决策的变更包括：

- 未合入 Main 的 Flyway migration 或破坏性 schema 变更；
- 删除或重命名持久 JSON 字段、数据库枚举值或 wire 字段；
- 改变 Work/Invocation 状态机、claim/lease/fencing 语义；
- 改变 S3 object key、Blob 引用计数或 cleanup 生命周期；
- 需要重建 Dev 镜像、重启 Daemon，或使 Main 与 Dev 中任一节点无法读取共享 durable 状态的数据
  变更。

完整协作顺序是：

```text
Agent modifies dev
  -> targeted tests
  -> commit and push dev
  -> reload vps-kk-studio-dev
  -> observe and validate studio-dev
  -> merge dev into main
  -> main workflow builds immutable image
  -> update vps-kk-studio
  -> Dev synchronizes the new main baseline
```

源码仓库、Dockerfile 和 image layer 只保存环境变量名与无敏感默认值。数据库、S3、Provider、
Gateway 和 Daemon registration credential 由 NAS 私密环境文件在运行时注入，Git key 由只读挂载
提供，`gh` OAuth credential 位于持久配置 volume；Dev 镜像中的源码快照、构建日志、测试报告和 Git
历史不得包含真实值。database 与 bucket 使用应用专用权限；Git 与 `gh` 有意继承所挂载 SSH key 与
登录账号自身的仓库和 API 权限。

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

开发循环自己的清理是 `./scripts/dev.sh stop`；本地与测试栈、分布式栈和可靠性栈的清理命令、
保留与删除语义见[部署与运行](deployment.md#清理)。[deploy/test](../../deploy/test/README.md) 与
performance 入口每次运行都会自行清理 PostgreSQL/MinIO/test network，`--distributed` 入口在退出时
清理双节点栈。

| 现象 | 先执行 | 边界 |
| --- | --- | --- |
| JDK/compile/checkstyle 失败 | `"$JAVA_HOME_21/bin/java" -version`；`env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp validate` | 必须是 JDK 21；先修复 Spotless/Checkstyle |
| Frontend 找不到依赖或 Playwright | `npm --prefix frontend ci`；`npm --prefix frontend run test` | 依赖由 [`package-lock.json`](../../frontend/package-lock.json) 固定 |
| dev 端口占用 | `./scripts/dev.sh status`；`ss -ltnp \| grep -E ':18080\|:5173'` | 用 `DEV_KILL_PORTS=true` 或换端口 |
| local app unhealthy | `docker compose -f deploy/local/compose.yaml ps`；`docker compose -f deploy/local/compose.yaml logs app postgres` | 先确认 PostgreSQL health，再检查 `/actuator/health` |
| Canvas test 健康失败 | `docker compose -f deploy/test/compose.yaml ps`；`docker compose -f deploy/test/compose.yaml logs` | 检查 MinIO bucket、mock `/health`、ffmpeg/ffprobe |
| E2E 只跑少数 case | `node scripts/e2e/run-matrix.mjs --list`；确认 `--real`、`--with-tools`、`--with-canvas-storage`、`--with-canvas-function` | 通过 `requires` 和 level 过滤是当前行为 |
| 真实 Provider 不可用 | 检查 8 个 `TEST_{GOOGLE,OPENAI,ANTHROPIC,DEEPSEEK}_{BASE_URL,API_KEY}` 变量是否均非空 | 必须显式 `--real`，只用宿主同步器；不要放入 Compose/image/container |
| reliability 环境未 READY | `./scripts/reliability/stack.sh status`；`./scripts/reliability/stack.sh logs app daemon` | `inspect` 先检查 non-root、volume 和 gateway |
| 敏感数据门禁失败 | `python3 scripts/security/check-sensitive-data.py` | 只按输出的规则和位置排查；不要把完整敏感值复制到日志或 Issue |
| performance/supply-chain 失败 | 阅读 `reports/performance/latest/report.md` 或 `reports/supply-chain/latest/summary.md` | 阈值、在线源、JSON 完整性和 zero-vulnerability 都不能放宽 |
| loopback proxy 下 build 失败 | 检查 `HTTP_PROXY`/`HTTPS_PROXY`、`CANVAS_TEST_BUILD_NETWORK`；再执行 `./deploy/test/run.sh --with-app` | loopback proxy 使用 host build network，代理值不进入镜像 |

---

上级：[系统设计](../system-design.md)。相关文档：[部署与运行](deployment.md)、
[本地一键启动栈](../../deploy/local/README.md)、[Canvas/Storage 隔离测试栈](../../deploy/test/README.md)、
[Environment Daemon 安装与运行](environment-daemon.md)。
