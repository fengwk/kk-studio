# 内置 Bash 的行为验证

修改 `process.exec` 命令执行、输出捕获或终态处理时，需要分别验证进程是否收敛、输出是否保真、协议是否只提交一次终态。
这三件事由不同层负责，不能用“收到了失败结果”代替“后代进程已经退出”。
工具契约见[内置工具设计](../modules/builtin-tools-design.md)，运行与安装见
[Environment Daemon](environment-daemon.md)。

## 选择验证入口

运行测试时区分 Node Daemon 与后端 Java 模块。下面的测试会启动真实本地进程、创建临时文件并发送终止信号，
不调用模型、不连接部署数据库。必须使用可供测试创建和收敛子进程的工作站或 CI runner。

```bash
# Daemon：类型检查、构建、命令执行、范围收敛、输出发布与协议终态（Node 24）
npm --prefix daemon ci
npm --prefix daemon run check
npm --prefix daemon run build
npm --prefix daemon run test -- test/coding/process.test.ts test/native/process-supervisor.test.ts
npm --prefix daemon run coverage

# 后端：提示词与 capability schema（JDK 21）
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/builtin -am test \
  -Dtest='BuiltinHarnessContributorTest,EnvironmentCapabilityCatalogTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# 后端：静态权限面分析，不执行被分析的命令（JDK 21）
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/runtime -am test \
  -Dtest='BashSurfaceAnalyzerTest,BashSurfaceAnalyzerCoverageTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Daemon 的重点测试文件位于 [`test/coding/process.test.ts`](../../daemon/test/coding/process.test.ts)、
[`test/native/process-supervisor.test.ts`](../../daemon/test/native/process-supervisor.test.ts)、
[`test/native/posix-session.test.ts`](../../daemon/test/native/posix-session.test.ts)、
[`test/native/process-supervisor-windows.test.ts`](../../daemon/test/native/process-supervisor-windows.test.ts)、
[`test/coding/output.test.ts`](../../daemon/test/coding/output.test.ts) 与
[`test/runtime.test.ts`](../../daemon/test/runtime.test.ts)。

矩阵 `daemon.ready` 是 L4 的 capability 登记验证，不执行 Bash；当前 E2E inventory 没有 Bash
执行用例。隔离容器中的命令 smoke 由
[`NativeToolSmoke.mjs`](../../scripts/dev/verify/reliability/fixtures/NativeToolSmoke.mjs) 承担，
通过 `stack.sh tool-smoke` 运行，不经过 Agent、Provider 或 App command batch。
它需要已经启动的可靠性栈，准备与清理边界见
[开发与测试](development-and-testing.md#隔离栈与真实-agent-矩阵)，不能把它称为 Platform 调用端到端证据。

## 命令与退出结果

[`process.ts`](../../daemon/src/coding/process.ts)
以宿主配置的 `bash -lc <command>` 启动命令，`workdir` 每次必填，没有隐式 cwd 或 stdin 命令传输模式。
捕获模式把 stdout/stderr 合到同一流，stdin 写端立即关闭，等待 EOF 的命令必须能自然退出。

| 要验证的行为 | 主要断言 |
| --- | --- |
| 自然退出与非零退出码保真，非零退出标记错误 | `process.test.ts` 验证自然退出携带权威退出码、用户 exit trap 与退出状态在会话结束后保留 |
| stdin 确定性 EOF、大输出不死锁 | 验证写入端关闭使等待 EOF 的命令自然结束、大输出完整合并捕获不死锁 |
| 启动前取消或超时不运行命令、不触碰输出存储 | `process.test.ts` 验证 pre-aborted 上下文立即失败，不启动 shell 或触碰存储 |
| 启动失败不留下中转文件 | 进程启动失败时清理 staging 文件并报告错误 |
| 超时预算原样传递，零值无 deadline，极大值不变成立即超时 | `runtime.test.ts` 验证 wire timeout 原样生效，0 表示无 deadline |
| 缺 workdir 不回退、未知字段在副作用之前拒绝 | 缺少 workdir 或包含未知参数在发生副作用前被拒绝 |

机器事实在 `detailsJson.process` 中：`outcome` 为 `EXITED`、`TIMED_OUT` 或 `CANCELLED`，
只有自然退出报告权威 `exitCode`，不以 `128 + signal` 合成被杀进程的退出码。

## 进程范围与清理

原生进程监管器（[`posix-supervisor.ts`](../../daemon/src/native/posix-supervisor.ts) 与
[`process-supervisor-windows.ts`](../../daemon/src/native/process-supervisor-windows.ts)）
使用 POSIX 进程组或 Windows 命名 Job Object。终态通知前必须确认整组收敛，首个进程退出并不充分。
POSIX 先发 SIGTERM、等待宽限、再强杀；Windows 用 Job 的 `KILL_ON_JOB_CLOSE` 和活动成员计数确认收敛。

- 自然退出、后台作业持有 stdout、`disown` 和嵌套 fork：由 `process.test.ts` 与 `process-supervisor.test.ts` 验证自然退出时收敛存活的后台子进程，后台持有流不阻塞退出结算。
- 超时、取消、忽略温和信号和命令 trap：验证超时与取消终止整棵后代进程树，忽略 SIGTERM 的顽固进程在宽限期后被 SIGKILL 强制回收。
- 许可前取消、并发终止与私有目录清理：由 `process-supervisor.test.ts` 验证终止后的存活状态，无法判定收敛时报告失败。
- 不依赖平台 shell 的进程层级与 stdin/duplex 语义：由 `process-supervisor.test.ts` 使用真实 Node 夹具进程和原生 pid 验证。LSP 使用同一个 supervisor 的 duplex 模式，stdin/stdout/stderr 保持独立。

范围不是恶意命令沙箱：命令主动 `setsid`、`set -m` 或交给其他 session 时不受这条边界约束。

## 输出与协议终态

[`output.ts`](../../daemon/src/coding/output.ts)
负责内联预算、有界预览和全文捕获与存储发布。
默认全文捕获预算为 1 GiB，耗尽后继续排空输出并计数。全文发布优先原子改名，不支持时使用普通改名；
文件编辑提交的原子替换要求则见[文件变更验证](builtin-mutation-tests.md#原子提交权限并发和取消)。

| 情况 | 主要证据与预期 |
| --- | --- |
| 小输出 | `output.test.ts` 验证阈值内输出保持内联，不落盘 |
| 字节或行数超阈值 | 超过 50 KiB 或 2000 行时外化到本地文件并返回有界预览与绝对路径 |
| 预览与计数 | 保留有界头尾，不破坏 UTF-8，计数包含 CR/LF/CRLF |
| 捕获预算耗尽 | 停止文件捕获但继续计数，不终止命令 |
| 存储或发布失败 | 降级为无路径预览、不留中转文件，分别报告捕获截断与发布失败 |
| 超时/取消后的输出 | 保留已捕获文本，终态说明不写入全文或字节/行计数 |

`detailsJson.textOutput` 的 `path`、`captureTruncated`、`captureFailed`、`totalBytes` 与 `totalLines`
是判断结果是否完整的事实，不按文本里的 “limit” 等字样猜测截断。私有目录被外部删除时降级为预览；
当前调用返回降级结果；排查时同时检查预览、捕获状态和 staging 清理。

[`runtime.test.ts`](../../daemon/test/runtime.test.ts)
验证超时裁决仍为 `FAILED`、取消裁决仍为 `CANCELLED`，两者在收尾窗口携带捕获输出；
能力未回调、调度器拒绝、收尾中停机和迟到回调都只能产生一个终态。
超过 16 MiB wire 预算时整体丢弃能力文本，而非发送超大终态。

## 权限面分析

[`BashSurfaceAnalyzer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/permission/BashSurfaceAnalyzer.java)
只分析文本，不运行 shell。`BashSurfaceAnalyzerTest` 与 `BashSurfaceAnalyzerCoverageTest` 覆盖：

- 顶层链、管道与后台分隔；引号、转义、重定向、注释、CRLF 与续行不产生伪段；
- heredoc 归属、多个 delimiter、可展开与字面量正文、未闭合正文；
- 命令/进程替换、复合语法、动态可执行名、重定向和 shell/launcher 包装器的 unsupported 原因；
- quoted/escaped 字面量、赋值前缀、绝对路径 basename，以及稳定的整段和前缀候选；
- 不完整语法按保守路径处理：unsupported 只有完整命令明确命中 DENY 才拒绝，否则 ASK，不猜内部命令；
  支持的复合命令按静态段组合权限。

## 平台证据与覆盖率

后端 Java 模块 Surefire 结果在各模块 `target/surefire-reports`，JaCoCo 报告在 `target/site/jacoco`；Runtime 的固定核心类门禁不包含 `BashSurfaceAnalyzer`。

Daemon 覆盖率由 Vitest 与 `@vitest/coverage-v8` 统计（`npm --prefix daemon run coverage`），并在 [.github/workflows/daemon-ci.yml](../../.github/workflows/daemon-ci.yml) 的多平台矩阵中执行（覆盖 Linux amd64/arm64、macOS arm64/amd64 与 Windows amd64）。Windows 必须在 Daemon 配置的 `bashExecutable`（Web 安装设置或 `daemon.json`）指定 Git Bash：裸名 `bash` 可能命中 `System32\bash.exe` 的 WSL 转发器。五个平台都真跑 native 进程范围、退出、Job/session 与能力层用例，任一平台失败即整体失败。本机单平台测试不能代替其他平台的原生证据。

终端折叠、计时文案与渲染回退不是 Daemon 的职责，以上测试不提供前端展示证据。
