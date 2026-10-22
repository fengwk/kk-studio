# Environment Daemon

Environment Daemon 是目标宿主上的独立 Node.js 进程，把 Platform 的原子能力调用落到文件系统、进程与本地工具链。网络连接只是消息管道：同一 Daemon 进程断线重连，不会重启已受理的执行。安装与三平台管理见 [Environment Daemon 安装与运行](../operations/environment-daemon.md)；共享契约与服务端会话见 [Harness Environment](harness-environment.md)。

模块运行在 Node.js 24 运行时之上，依赖 `ajv`、`diff`、`ignore`、`isomorphic-git`、`koffi`、`picomatch`、`re2-wasm`、`tar`、`vscode-jsonrpc`、`ws` 等生产依赖。官方发行归档内置冻结的 code-server 运行时（Node 24.x 与 code-server），不依赖宿主全局安装 Node 或 JDK 运行时；外部语言服务器与 Bash 由宿主提供。

## 启动与本地状态

入口 [`main.ts`](../../daemon/src/main.ts) 先校验命令行参数，再处理信息命令或配置校验，随后打开数据目录以属主独占权限（POSIX 0700）创建并持有目录内核锁，装配原生进程 supervisor、网络代理、能力与运行时，注册信号监听（SIGINT/SIGTERM），连接 gateway 并等待结束。

命令行只支持标准 Unicode 参数，不经过二次编码包装：
- `--help` / `-h` 与 `--version` 输出帮助或版本后以 0 状态退出，不触碰数据目录。
- `--check-config <绝对路径>` 只读取并校验配置、同目录凭证与配置的 bash 可执行文件，不打开数据目录、不抢锁、不建立连接也不启动任何进程；校验通过输出固定信息并退出，校验失败在 stderr 输出单行安全诊断（带有稳定前缀 `Invalid daemon configuration:`，不回显输入敏感值或堆栈）。
- `--config <绝对路径>` 加载指定配置并启动长驻服务。未知或多余参数一律失败关闭。

[`DaemonConfig`](../../daemon/src/config.ts) 只消费唯一的 `--config <绝对路径>`：配置文件固定为 `daemon.json`（包含 `studioUrl`、可选的 `note`、`bashExecutable`、`lsp`），其父目录即为运行数据目录，同目录的 `daemon.token` 是属主独占的注册凭证文件（POSIX 0600 普通文件，非符号链接）。gateway 地址由配置中的 `studioUrl` 派生为 `ws(s)://<authority>/api/harness/environment-daemon/v1`，心跳与重连间隔固定为 `PT15S`/`PT1S`/`PT30S`。进程不接受 token 文本参数，也不接受命令行传入的 gateway 或第二配置来源。

数据目录在启动时由 [`lock.ts`](../../daemon/src/native/lock.ts) 获取目录级独占内核建议锁 `daemon.lock`（POSIX `flock(LOCK_EX | LOCK_NB)`，Windows `LockFileEx`），同一目录上的第二个进程立即失败退出。锁随打开的文件描述符存活，进程异常崩溃时由操作系统内核自动释放，不残留陈旧锁。目录布局如下：

```text
<data-dir>/
  daemon.lock                # 内核独占建议锁（0600）
  tmp/workspaces/<uuid>/     # 受控临时 workspace（*.part 中转 → 原子发布 *.log）
  skills/<package>/          # 已安装技能包
  skill-work/staging/        # 技能安装暂存
  skill-work/backup/         # 技能替换备份
  updates/<operation-id>/    # 受管在线更新工作目录
```

POSIX 目录权限为 0700、敏感文件为 0600。`tmp/workspaces` 由受控临时存储按 TTL 自动清扫；技能安装器负责恢复或清理 staging/backup；已发布技能包不随服务卸载自动删除。

## 能力装配与运行时调度

装配阶段冻结 11 项原子能力（9 项模型工具与 2 项内部能力）；IDE 是独立受管服务。能力目录版本为 3，envelope wire 版本为 4，READY payload 版本为 5：
- 编码能力：文件读写编辑（`fs.read`、`fs.write`、`fs.edit`、内部审查能力 `fs.edit.preview`）、文件与内容检索（`fs.find`、`fs.grep`）、命令执行（`process.exec`）；
- 语言服务：`lsp.goto-definition`、`lsp.workspace-symbols`、`lsp.java-decompile`；
- 扩展与受管服务：内部 `skill.sync` 以及受管 IDE 服务（code-server，通过 `IDE_COMMAND` 与 `IDE_RESULT` 进行生命周期与流控制）。

运行时 [`Runtime`](../../daemon/src/runtime.ts) 通过 WebSocket 保持控制连接：
- 连接握手强制协商 `permessage-deflate`，缺失时以 1010 关闭；仅传输文本帧，超阈值单帧以 1008 关闭。
- `daemonInstanceId` 在构造时随机生成，同进程所有 HELLO 复用。连接断开指数退避重连，上限为 30 秒；重连不改变进程内已有执行事实。
- 注册被拒（REJECTED）进入 FAILED 状态、停止重连并退出进程；临时不可用（RETRY_LATER）退避重连。
- INVOKE 经过严格 JSON 解码后，以 [`Journal`](../../daemon/src/journal.ts) 的 `start(invocationId)` 原子去重。已有条目直接重放：RUNNING 状态重放 `STARTED(replayed=true)`，已完成条目重放冻结终态，副作用不再重复执行。
- 超时与取消：Runtime 维护唯一的 invoke timer，超时或取消时通过同一 `context.signal` 请求停止（`TIMED_OUT` / `CANCELLED`），不提前抢占终态；由 process、search 等执行器在 cleanup 后交付真实结果，已完成的文件修改提交成功决不能被 late abort 覆盖。系统不对 OS 阻塞做严格硬中止或承诺 60 秒内一定回包。
- 关停顺序：逆序释放连接与在途调用、能力资源、受控临时存储与目录锁。关停中发生的异常通过 `AggregateError` 聚合抛出，不静默吞掉清理错误。

## 本地存储与文本输出

超阈值命令与检索输出通过 [`output.ts`](../../daemon/src/coding/output.ts) 写入受控本地存储：
- 小输出内联在调用结果中。
- 超过 50 KiB 或 2000 行的输出先写入 `tmp/workspaces/<uuid>/<name>.part`，再原子改名为 `*.log`，返回有界头尾预览、绝对路径、总行数与字节数。预览切分对齐字符边界，不破坏 UTF-8 编码。
- 默认文本捕获预算为 1 GiB，达到预算后停止捕获文件并继续排空与统计，结果中显式标记截断。
- 定时清扫（默认 30 分钟一次）只回收超过保留期（默认 3 天）且未持用租约的 workspace，清扫不跟随符号链接、不越出受控根目录。

技能包由 [`installer.ts`](../../daemon/src/skill/installer.ts) 安装：在独立 staging 目录按请求的 exact commit 获取，检查整棵 tree 并校验后原子替换 `skills/<package>`，失败时从 backup 恢复；拒绝符号链接、submodule、路径穿越与非普通文件。

## 编码能力与原生监管

### 路径与执行约定

文件与 LSP 能力的 `path` 必须为绝对路径，相对路径直接拒绝；只有 `process.exec` 要求显式给出绝对、现存且可读的 `workdir`。workdir **不是文件系统沙箱**：符号链接照常跟随，进程继承 Daemon 运行用户的操作系统权限。

### 原生进程监管

子进程统一由平台原生监管器 [`native/index.ts`](../../daemon/src/native/index.ts) 管理，使用 `koffi` 绑定宿主系统调用：
- Linux/macOS：POSIX 监管器（[`posix-supervisor.ts`](../../daemon/src/native/posix-supervisor.ts)）使用 `setsid` 建立独立 session 与进程组。退出或终止时先发送 SIGTERM 并在宽限期后发送 SIGKILL，向系统确认所有后代进程收敛；
- Windows：监管器（[`process-supervisor-windows.ts`](../../daemon/src/native/process-supervisor-windows.ts)）使用 Windows Job Object，配置 `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`，通过 Job 活动进程计数为 0 确认范围完全结束。

`process.exec` 将 stdin 写端立即关闭，stdout 与 stderr 合并捕获。Bash 命令统一通过 `bash -lc <command>` 启动；Windows 推荐配置 Git Bash 绝对路径以避免裸名命中 WSL 转发器。

### 文件与检索

文件写入与编辑（[`files.ts`](../../daemon/src/coding/files.ts)）保留原有编码、BOM 与行尾，并通过 64-stripe 细粒度分段锁串行化同文件修改；原子写入临时文件后再替换目标文件（[`commit.ts`](../../daemon/src/coding/commit.ts)）。
内部只读审查能力 `fs.edit.preview` 与 `fs.edit` 共享参数校验、解码、行尾检测与 diff 计算：

- `fs.edit.preview` 使用 `diff@9.0.0` 的 `diffArrays` 计算所有变更行、4 行上下文与 EOF 缺换行标记，不设独立的计算超时或 `maxEditLength`；在文件锁内读取原文件原始字节，计算 canonical 绝对路径、原始字节 SHA-256 与带行号的 diff，返回 `{path, diff, sha256}`；完整 review 连同结果序列化后超过 1 MiB 时，在预览或提交前拒绝，不截断变更。
- `fs.edit` 在文件锁内执行时，若 INVOKE 携带内部元数据 `expectedFile`（`{path, sha256}`），核对其与当前磁盘文件的绝对路径和 SHA-256；若文件在审查后已被变动，直接拒绝写入，防止授权陈旧变更；执行成功后在结果 `details.editReview` 携带 `{path, diff, sha256}`，作为唯一权威 canonical 持久化 diff，供自动放行/YOLO 模式展示真实变更。

检索（[`search.ts`](../../daemon/src/coding/search.ts)）在进程内直接完成遍历与匹配，默认超时 15 秒（可由 `timeout_seconds` 覆盖），worker 共享原子 flag 在目录、行与 8KiB 块等检查点协作检查中断，超时保留 partial 匹配并标记 `search.incomplete`；非响应 stop 给予 5 秒清理宽限才 await terminate（非额外更短执行预算），清理失败明确暴露；支持分层 `.gitignore` 与 `.git/info/exclude`，不依赖外部 ripgrep/fd 二进制。

### 受管 IDE 与反向数据流

Daemon 受管运行内置的 code-server，响应 `IDE_COMMAND` 的 `OPEN_VIEW`、`CHANGE_DIRECTORY`、`FOCUS_TERMINAL`、`CLOSE_VIEW` 与 `OPEN_STREAM`，返回 `IDE_RESULT`。
`OPEN_STREAM` 使用根相对路径 `/api/harness/environment-stream/v1/{ticket}?environment={id}`；Daemon 基于由 `studioUrl` 派生的 `gatewayUri` 解析目标地址与 TLS 端点，在 code-server 与 Studio 之间透传 HTTP 和 WebSocket 数据。

### LSP 语言服务

LSP 组（[`lsp.ts`](../../daemon/src/coding/lsp.ts)）从配置中的 `lsp.servers` 初始化，按扩展名匹配语言服务器，并在目标文件所属项目根下复用 stdio JSON-RPC 连接。三项能力默认超时 180 秒覆盖启动等待、initialize 与 query 全程，统一消费 `context.signal`；共享池初始化默认 180 秒独立生命周期，单个 waiter 停止不影响其他等待者，最后退出等待 cleanup；闲置 5 分钟自动回收客户端与 1 秒 shutdown 机制保持不变；反编译能力（`lsp.java-decompile`）仅在匹配到 jdtls 服务器时可用。

## 二进制直传与在线更新

二进制文件与媒体结果先向服务端申请预签名上传票据，通过 [`transport/resources.ts`](../../daemon/src/transport/resources.ts) 直接 HTTP PUT 上传对象存储，字节不经过 WebSocket 控制管道。

受管更新由 [`update.ts`](../../daemon/src/update.ts) 驱动：校验官方发布物 URL、SHA-256 摘要与发行清单后，将暂存产物交给操作系统级独立瞬时任务（Linux `systemd-run`、macOS `launchctl submit`、Windows 临时计划任务）执行二进制目录原子替换并重启受管服务。

## 质量守卫与验证

Daemon 拥有独立的 Node.js 工程与测试套件：
- 类型检查：`npm --prefix daemon run check`
- 编译构建：`npm --prefix daemon run build`
- 单元与集成测试：`npm --prefix daemon run test`
- 覆盖率统计：`npm --prefix daemon run coverage`（基于 Vitest 与 `@vitest/coverage-v8`）

多平台 CI 由 [.github/workflows/daemon-ci.yml](../../.github/workflows/daemon-ci.yml) 守卫，覆盖 Linux (amd64/arm64)、macOS (arm64/amd64) 与 Windows (amd64) 五大目标环境。发行归档由 [`scripts/daemon/prepare-release.sh`](../../scripts/daemon/prepare-release.sh) 暂存，并由 [`scripts/daemon/smoke-archive.sh`](../../scripts/daemon/smoke-archive.sh) 与 [`scripts/daemon/smoke-archive.ps1`](../../scripts/daemon/smoke-archive.ps1) 验证自足运行能力。

上级：[系统设计](../system-design.md)。相关文档：[Harness Environment](harness-environment.md)、[Environment Daemon 安装与运行](../operations/environment-daemon.md)。
