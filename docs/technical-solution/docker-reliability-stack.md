# Docker Reliability 栈

Docker reliability 栈在独立 Compose project 中运行当前源码构建的
`postgres + app + workspace-init + daemon`。它用于免费基础验证和后续显式开启的
真实可靠性任务，但启动、快照、case 准备和隔离检查本身不会发起模型调用。

## 完整命令流

从仓库根目录执行：

```bash
./scripts/reliability/stack.sh --help
./scripts/reliability/stack.sh up
./scripts/reliability/stack.sh snapshot
./scripts/reliability/stack.sh case-reset smoke-pi pi
./scripts/reliability/stack.sh case-reset smoke-pi-base pi-base
./scripts/reliability/stack.sh case-deps smoke-pi
./scripts/reliability/stack.sh case-deps smoke-pi-base
./scripts/reliability/stack.sh inspect
./scripts/reliability/stack.sh tool-smoke
./scripts/reliability/stack.sh status
./scripts/reliability/stack.sh logs daemon
./scripts/reliability/stack.sh down
```

`app` 只在宿主 `127.0.0.1:18091` 提供 API；可用
`RELIABILITY_APP_PORT` 修改宿主端口。其余服务不发布宿主端口。Environment 名称默认
`docker-reliability`，可通过 `RELIABILITY_ENV_NAME` 改为另一个合法 canonical 名称。

`up` 构建当前 reactor 的 app 与 Daemon，等待 app health 和公共 Environment API
`READY`，最后从宿主调用既有 `scripts/e2e/sync_provider_credentials.py`。宿主同时提供
`TEST_MINIMAX_BASE_URL` 和 `TEST_MINIMAX_API_KEY` 时，同步器通过 app API 更新 E2E seed；
两者都不存在时明确输出 skip；仅存在一个时失败。同步输出只包含配置状态，不包含凭证值。

## 隔离模型

- Compose project 固定为 `kk-studio-reliability`；四个服务通过 `internal: true` 的专用网络
  通信，只有 app 额外挂入专用 ingress bridge 以支持 loopback 端口发布；
- PostgreSQL 数据和 `/workspace` 分别位于该 project 的 named volume；
- Daemon 只挂载一个目标为 `/workspace` 的 named volume，没有宿主 bind mount；
- `workspace-init` 只把 volume 根目录 owner 初始化为 uid/gid `10001`，Daemon 以该非 root
  身份运行；
- Daemon 镜像包含 JDK 21（含 `javap`）、bash、git、Node 22.19/npm，不安装
  `rg` 或 `fd`；
- app 使用 `e2e` profile，Gateway 固定使用 E2E profile 的
  `e2e-daemon-token`；Daemon note 固定为
  `Isolated Docker reliability environment.`，不包含 ready、status、时间或 workdir 等
  短期运行态。

`inspect` fail closed 检查实际 Daemon uid、Docker Mounts、命令可用性、`rg`/`fd` 缺失、
`/workspace` 可写和公共 Environment READY。它只输出安全摘要，不打印容器 command、
token 或 provider 配置。

`tool-smoke` 不经过 app、Agent 或 Provider。它把仓库内固定的 `NativeToolSmoke.java` 通过
stdin 送入正在运行的 uid 10001 Daemon 容器，使用镜像内
`daemon.jar:/opt/kk-studio/lib/*` 编译并直接执行 `FindTool`、`GrepTool` 与 `BashTool`。
断言覆盖 slash/double-star/字符类转义 glob、分层 `.gitignore`/`.git` 边界、grep 行号与
multiline、非法 regex、直接二进制目标和 ANSI bash 文本；Java class 与 fixture 在结束时
删除。

## 锚点与 case

`snapshot` 默认读取：

```text
PI_ANCHOR=$HOME/proj/pi
PI_BASE_ANCHOR=$HOME/proj/pi-base
```

两个源目录必须是 clean Git worktree。脚本记录源 HEAD，在 `mktemp -d` 中通过
`git clone --no-local --no-hardlinks` 获取当前 HEAD，显式校验 clone 与源 SHA 完全一致后建立
`reliability-baseline` 分支，删除全部 remote，再通过 tar stdin 写入 named volume；写入后再次
校验 volume 中两个仓库的 HEAD。临时目录在
成功或失败后都删除；脚本还会复查源 HEAD 和 clean status，因此不会修改或偷偷携带宿主未提交
内容。volume 布局为：

```text
/workspace/
  anchors/
    pi/
    pi-base/
  cases/
    <case-id>/
```

`case-reset <case-id> <pi|pi-base>` 从对应 anchor 做 `--no-local` clone，删除 remote，再原子
替换 `/workspace/cases/<case-id>`。case id 只允许小写字母、数字和连字符，最长 64 字符。
每个 case 都有独立可写 worktree；命令输出容器内路径、anchor 和 baseline SHA。

需要运行仓库原生 Node 测试时，`case-deps <case-id>` 使用当前 Daemon 镜像启动一次性 helper，
把同一个 workspace named volume 挂入容器，并仅把 helper 接入 `app-ingress` 网络执行
`npm ci`。npm HOME/cache 也位于该 named volume，helper 不接收 Provider 凭证且退出即删除；
长期运行的 Daemon 始终只连接 `internal: true` 网络，没有外网出口。依赖安装只修改指定的
disposable case，不修改 anchor 或宿主仓库。

## 确定性 Java 重复回归门禁

`scripts/reliability/regression.sh` 是与真实 Agent reliability matrix 分开的确定性
Java/Surefire 重复回归入口。它不调用 Provider、不创建付费 Agent/Chat/Thread，也不启动
backend、frontend、daemon 或 Compose 栈；选中的 JUnit 测试自行管理所需的 Testcontainers。
每轮都从仓库根目录使用 `JAVA_HOME_21` 执行同一个 Maven reactor 定向命令，失败即停止后续
轮次，不使用随机等待。

默认重复三轮，可将轮次限定为 `1..100`，也可指定报告根目录：

```bash
./scripts/reliability/regression.sh --help
./scripts/reliability/regression.sh --iterations 1
./scripts/reliability/regression.sh --iterations 3 --report-root reports/reliability
```

`--report-root` 会先规范化为绝对路径；空值、文件系统根、仓库根、已有符号链接以及
会使 `latest-regression` 越出该专用目录的路径都会被拒绝。`latest-regression` 不是符号链接，
发布时只替换报告根目录下的专用目录内容。

冻结测试集按模块分为：

- Web：`PostgresqlNotificationLoopTest`、`PostgresqlNotificationLoopPostgresqlIntegrationTest`、
  `DaemonOutboundSenderTest`、`ApplicationEventHubTest`、
  `ApplicationEventWebSocketHandlerTest`；
- Canvas：`CanvasFunctionWorkStoreIntegrationTest`、`CanvasFunctionDispatcherTest`、
  `CanvasFunctionExecutionContextImplTest`、`CanvasFunctionWorkerHeartbeatTest`；
- Harness Infra：`HarnessWorkDispatcherHandoffTest`、`HarnessWorkDispatcherLifecycleTest`、
  `PostgresqlWorkTest`、`PostgresqlWorkNotificationTest`、
  `PostgresqlRealtimeEventSourceConcurrencyTest`；
- Platform Storage：`StorageUploadCleanupLeaseIntegrationTest`、`StorageMaintenanceTest`、
  `StorageUploadServiceIntegrationTest`。

Runner 使用 `-Dsurefire.failIfNoSpecifiedTests=false` 允许上游 reactor 模块没有同名测试时
正常通过，但随后逐模块检查四个目标模块的 `target/surefire-reports`。每一轮的每个冻结
类都必须有实际 Surefire XML 证据，且 XML 必须证明 `tests > 0`、`failures = 0`、
`errors = 0`，并且不能全部 skipped；只有类名但没有这些执行结果的 XML 会被记为
invalid。Maven 非零、目标类缺失、invalid、`Surefire is going to kill` 或 Maven `[ERROR]`
都 fail closed。每轮独立保存 `iterations/NNN/maven.log` 和 Surefire 报告副本。

报告写入 Git 忽略的 `reports/reliability/`：

```text
reports/reliability/<timestamp>-regression-<pid>/
  report.md
  summary.json
  iterations/001/maven.log
  iterations/001/surefire-reports/<module>/
reports/reliability/latest-regression/
```

`report.md` 和 `summary.json` 记录 commit、JDK、Maven、请求/完成轮次、冻结目标类、每轮
命中数、时长、结果和失败证据。收到 `INT`/`TERM` 时 runner 会终止并等待当前 Maven
进程组，再保留当前报告；runner 自身不负责清理外部启动的服务或容器。

## 真实 Agent reliability matrix

`scripts/reliability/run-agent-matrix.mjs` 是零第三方 Node 依赖的真实 Agent runner。它只在
显式执行时调用模型；查看帮助、列举矩阵和单元测试都不会创建 Agent、Chat、Thread 或发起
Provider 请求：

```bash
node scripts/reliability/run-agent-matrix.mjs --list
node --test scripts/reliability/tests/*.test.mjs
```

冻结矩阵为 `2 models × 2 anchors × 2 task classes`：

| Model | Variant | pi | pi-base |
| --- | --- | --- | --- |
| `minimax/MiniMax-M2.7` | `high` | `m27-pi-investigate`, `m27-pi-repair` | `m27-pi-base-investigate`, `m27-pi-base-repair` |
| `minimax/MiniMax-M3` | `high` | `m3-pi-investigate`, `m3-pi-repair` | `m3-pi-base-investigate`, `m3-pi-base-repair` |

Runner 不接受 Provider、model 或 variant 覆盖。执行前会 fail closed 查询 Provider、Model、
tool catalog 与 Environment，要求 `minimax` 已配置、两个冻结 Model 都存在并支持 tools 与
reasoning、`high` variant 存在，且目标 Environment 为 `READY`。它不会 fallback 到其他
Provider 或 Model。

完整矩阵在已启动并完成 snapshot 的 reliability 栈上执行：

```bash
node scripts/reliability/run-agent-matrix.mjs
```

可重复使用 `--only` 选择切片：

```bash
node scripts/reliability/run-agent-matrix.mjs \
  --only m27-pi-investigate \
  --only m3-pi-repair
```

已归档 run 需要在 runner 策略修正后重新判定时，只读取 durable trace 与既有 postcheck：

```bash
node scripts/reliability/reassess-agent-run.mjs <runId>
```

该命令不会创建 Agent、Chat、Thread 或调用 Provider；它在原 run 的 `summary.json` / case
结果中记录 `offline-archived-trace` reassessment 元数据，并重新发布 `latest-agent`。重算后
矩阵通过时退出码为 0，仍有 fail/skip 时退出码为 1，CLI/归档输入错误时为 2。

CLI 选项：

- `--base-url`：默认 `http://127.0.0.1:18091`；
- `--daemon-env`：默认 `docker-reliability`；
- `--report-root`：默认 `reports/reliability`；
- `--max-cost-usd`：默认且硬上限为 USD 5，可设置为 `0..5`；累计成本达到 cap 后不再启动
  后续 case，已发生的成本仍写入报告。某个已启动 turn 无法取得可信 durable cost metadata
  时同样 fail closed，不再启动后续 case，并在报告中标记 unknown cost。

每个被选择的 Model 在一次 run 中只创建一个临时 Agent并跨该 Model 的 case 复用。两个
Agent 使用相同 System Prompt 和精确相同的
`tools=[read,write,edit,bash,grep,find], skills=[], subagents=[]`，只改变 Model 引用。
该 Agent System Prompt 不包含日期、时间、状态、ready、workdir 或 case path；日粒度日期只由
Platform 的稳定 `<current_environment>` 块提供。
每个 case 创建独立 Chat/Thread，Thread 固定 `yoloEnabled=true`、目标 Environment
（Chat 与 rootSettings 均为完整 `EnvironmentBinding{name, workspacePath: '.'}`）、
上述 active tools 和该 case 的 `high` Model；每个 case 通过一次原子
NEW_SESSION command batch 物化 Session + ROOT + Thread 并提交首条 USER message，
工具 continuation 仍属于同一个真实 turn。

执行流程为：

1. `case-reset` 创建独立 disposable clone；repair case 额外执行 `case-deps`；
2. repair 通过容器内 Node stdin 做 exact-one defect replacement，并要求目标测试在模型调用
   前确定失败；
3. 入队任务并等待完整 quiescent，默认单 case 最长 10 分钟；超时或异常时按最新 version
   尽力 stop；
4. 从最终 durable snapshot 的 MESSAGE payload 提取 tool call/result、最终文本与每条
   assistant metadata 的 usage/cost；只有每条 Assistant 行都携带 USD total metadata 时才把
   该 case 的成本视为已知；
5. investigate 要求 `find → grep → read` 且仓库保持 clean；repair 要求首次调用顺序
   `find → grep → read → edit → bash → write`，源码恢复到 baseline exact bytes、目标测试
   通过、`git diff --check` 通过，并验证 `.reliability-write-proof.txt` 的字节数和 SHA-256；
6. finally 删除 Chat；整个 run finally 删除临时 Agent。

repair USER message 携带同一份超过 12 KiB 的确定性 write payload，要求 Agent 只能用
`write` 原样写入。报告只记录 payload 的 byte length 与 SHA-256；trace 对
`write.content` 同样只保留摘要，用于区分模型 arguments 损坏、write tool 返回错误和落盘
内容不一致。路径校验按规范化后的 case root 执行：相对路径必须绑定 case 内 workdir，case
内绝对路径同样合法；`..`、`@` 前缀和绝对路径都在规范化后重新检查，不能越到 anchor 或其他
case。容器内 inspector 还会解析目标或其最近已存在祖先的 real path，拒绝通过 case 内
symlink 越出 case root。离线基于已归档 trace 复核时使用同一策略，不需要再次调用模型。

## 凭证边界

Compose、Dockerfile、镜像 layer 和容器 environment 均不接收
`TEST_MINIMAX_BASE_URL` / `TEST_MINIMAX_API_KEY`。这两个值只由宿主 Python 同步器读取，并
经 HTTP request 写入 reliability 专用数据库。不要把 `docker inspect`、宿主 environment
或数据库内容作为报告附件。`docker compose config` 不应出现上述变量、API key/base URL
字段或 bind mount。

## 报告与清理

免费隔离证据可保存到已被 Git 忽略的 `reports/reliability/`：

```bash
run_id=$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "reports/reliability/$run_id"
./scripts/reliability/stack.sh inspect \
  | tee "reports/reliability/$run_id/inspect.log"
./scripts/reliability/stack.sh status \
  | tee "reports/reliability/$run_id/status.log"
./scripts/reliability/stack.sh logs app daemon \
  >"reports/reliability/$run_id/stack.log"
```

Agent runner 写入：

```text
reports/reliability/<runId>/
  report.md
  summary.json
  cases/<id>.json
  artifacts/<id>/
    trace.json
    precheck.json
    postcheck.json
    git-diff-summary.json
reports/reliability/latest-agent/   # 上述 run 的目录副本，不使用 symlink
reports/reliability/LATEST_AGENT_RUN.txt
```

Markdown 矩阵包含 pass/fail/skip、Model、anchor、task class、工具次数与顺序、
tokens/cache/cost、测试结果以及
`setup/model/tool/oracle/postcheck/cost-cap` 分类。所有 JSON/Markdown 在写入前经过统一
敏感字段清理和字符串扫描；不保存 API key、完整 Provider endpoint、Gateway token、容器
environment 或完整长 payload。

普通停止保留 PostgreSQL 和 workspace named volume：

```bash
./scripts/reliability/stack.sh down
```

彻底清理只删除 `kk-studio-reliability` project 的容器、网络和 named volume：

```bash
./scripts/reliability/stack.sh down --volumes
```
