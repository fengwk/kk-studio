# Environment Daemon

Environment Daemon 是目标宿主上的独立 Node.js 进程，把 Platform 的原子能力调用落到文件系统、进程与本地工具链。网络连接只是消息管道：同一 Daemon 进程断线重连，不会重启已受理的执行。安装与三平台管理见 [Environment Daemon 安装与运行](../operations/environment-daemon.md)；共享契约与服务端会话见 [Harness Environment](harness-environment.md)。

模块运行在 Node.js 24 运行时之上，依赖 `@vscode/ripgrep`、`ajv`、`diff`、`isomorphic-git`、`koffi`、`tar`、`vscode-jsonrpc`、`ws` 等生产依赖。官方发行归档内置冻结的 code-server 运行时（Node 24.x 与 code-server）及对应平台的 ripgrep，不依赖宿主全局安装 Node、JDK 或 ripgrep；外部语言服务器与 Bash 由宿主提供。

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
  updates/install.lock       # 安装目录级内核互斥（独立更新器持有）
  updates/<operation-id>/    # 受管在线更新工作目录（handoff / permit / takeover / steps / result）
```

POSIX 目录权限为 0700、敏感文件为 0600。`tmp/workspaces` 由受控临时存储按 TTL 自动清扫；技能安装器负责恢复或清理 staging/backup；已发布技能包不随服务卸载自动删除。更新目录只保存本机交接事实，不承载凭据，也不进入 Server 状态。

## 能力装配与运行时调度

装配阶段冻结 11 项原子能力（9 项模型工具与 2 项内部能力）；IDE 是独立受管服务。控制 wire 版本为 7，READY payload 版本为 6，能力目录版本为 3（这些版本、payload 上限、错误码与枚举 wire 值都来自由 Java 协议常量生成的 [`protocol-contract.ts`](../../daemon/src/generated/protocol-contract.ts)）；IDE 数据 wire 版本为 2，由 [`mux-wire.ts`](../../daemon/src/transport/mux-wire.ts) 与 Java 侧 mux codec 共同实现：
- 编码能力：文件读写编辑（`fs.read`、`fs.write`、`fs.edit`、内部审查能力 `fs.edit.preview`）、文件与内容检索（`fs.find`、`fs.grep`）、命令执行（`process.exec`）；
- 语言服务：`lsp.goto-definition`、`lsp.workspace-symbols`、`lsp.java-decompile`；
- 扩展与受管服务：内部 `skill.sync` 以及受管 IDE 服务（code-server：`IDE_COMMAND` / `IDE_RESULT` 承载视图生命周期，`DATA_BIND` 承载复用数据连接，`IDE_LEASE` 承载使用证明）。

运行时 [`Runtime`](../../daemon/src/runtime.ts) 通过 WebSocket 保持控制连接：
- 连接握手强制协商 `permessage-deflate`，缺失时以 1010 关闭；仅传输文本帧，超阈值单帧以 1008 关闭。
- `daemonInstanceId` 在构造时随机生成，同进程所有 HELLO 复用。连接断开指数退避重连，上限为 30 秒；重连不改变进程内已有执行事实。
- 注册被拒（REJECTED）进入 FAILED 状态、停止重连并退出进程；临时不可用（RETRY_LATER）退避重连。
- 运行状态：READY v6 携带必需的 `runtimeStatus`，HEARTBEAT 固定携带 `{runtimeStatus, ideLeaseRound}`，关键变化立即另发 `RUNTIME_STATUS`（[`runtime-status.ts`](../../daemon/src/runtime-status.ts)）。`runtimeStatus` 是 `{revision, status}`，`status` 固定 `{daemonVersion, update}`；`update` 为 null 或 `{targetVersion, downloadedBytes, totalBytes}`，`totalBytes` 为 null 或不小于已下载量。revision 只在状态内容变化时递增，心跳重发同一内容沿用同一 revision。运行状态只在内存中，没有更新历史。
- IDE 使用证明：有活跃视图时每次心跳递增 `ideLeaseRound` 并与本轮发出时的单调时间一起记录；没有视图时该轮次为 null。Daemon 只接受当前未过期轮次、且 documentId 与 bindingEpoch 完全相符的 `IDE_LEASE` 批次；证明只为已有视图续期，不创建视图，也不复活已分离的 epoch。
- INVOKE 经过严格 JSON 解码后，以 [`Journal`](../../daemon/src/journal.ts) 的 `start(invocationId)` 原子去重。已有条目直接重放：RUNNING 状态重放 `STARTED(replayed=true)`，已完成条目重放冻结终态，副作用不再重复执行。退出确认单独保存在同一条目里，重复 INVOKE 或 CANCEL 时随条目重放 `EXECUTION_EXITED`；不能从终态报文推断。有界 journal 淘汰条目后不再重放该确认。
- 同实例重连后，Server 对在途调用发送 `RECOVER`：Runtime 只重放 journal，运行中的条目回放 `STARTED(replayed=true)` 并继续执行，已终结条目重放冻结终态与退出确认，未知条目保持静默；只有 `CANCEL` 会请求停止。
- 超时与取消：Runtime 维护唯一的 invoke timer，超时或取消时通过同一 `context.signal` 请求停止（`TIMED_OUT` / `CANCELLED`），不提前抢占终态；由 process、search 等执行器在 cleanup 后交付真实结果，已完成的文件修改提交成功决不能被 late abort 覆盖。系统不对 OS 阻塞做严格硬中止或承诺 60 秒内一定回包。部分结果编码失败时封存 `FAILED`，并通过同一 signal 请求中断；失败结果不代替执行范围退出。
- 退出确认：能力只在确知自己管理的范围已经结束时调用 `confirmExecutionExit()`。文件读写编辑在调用返回前没有留下 worker。`skill.sync` 只在进入安装前就能确定未开始，或安装调用成功返回时确认；安装进入后抛错（包括中断或收尾失败）保持退出未确认。`fs.find` / `fs.grep` 要等到本次所有 rg 子进程退出、stdio 关闭，并且输出 spool 关闭成功；校验失败且 rg 尚未启动时确认为未开始。`process.exec` 要等到受管进程 `terminate` 成功且本地捕获关闭成功；`start` 抛错不证明范围已清理，不能确认。LSP 在服务端成功结果或服务端 RPC 错误到达时直接确认；进程退出报告只代表可用性变化，不是范围收尾，已发出请求要等到同一客户端 `stop` 成功、真实子进程与 stdio 收尾后才确认；`stop` 失败或仍在等待时不确认，也不重试。本地写失败、连接 dispose、取消、超时和传输失败都不是请求结束。参数、租约或发送前失败才确认为未开始。Runtime 持有同一次调用对象：executor 及其拥有的上传和终态收尾完成，并且正向证据已经留下，才发送一次空 payload 的 `EXECUTION_EXITED`。证据晚于终态到达仍然发送；收尾抛错不发送。确定未启动 executor 的拒绝直接确认；已经启动但没有正向证据时不补发。取消、超时和普通 error 本身都不是退出证据。重连后 Platform 对同一未确认退出事实重发 `CANCEL` 以拉取 journal 事实或停止仍运行的执行范围；未知 `CANCEL` 不产生确认。
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

检索（[`search.ts`](../../daemon/src/coding/search.ts)）调用 `@vscode/ripgrep` 随包提供的 rg：`fs.find` 解析 NUL 分隔的 `rg --files` 输出，`fs.grep` 解析 `rg --json` 输出。默认超时 15 秒（可由 `timeout_seconds` 覆盖）；超时、取消或达到结果上限时终止 rg，等待进程和 stdio 关闭后返回。超时与取消保留已收到的 partial 匹配并标记 `search.incomplete`；达到结果上限时给出明确提示。正则或 glob 错误作为输入拒绝，无法读取的路径作为 skipped 报告，其他进程错误作为运行失败，均不伪报“无匹配”。

检索包含隐藏文件，不跟随目录中的符号链接，排除 `.git`；支持分层 `.gitignore` 和 `.git/info/exclude`（含 linked worktree 的共享 exclude），不读取 `.ignore`、全局 Git ignore 或宿主的 ripgrep 配置。glob 相对检索根解释，不能重新包含被忽略的文件；显式指定被忽略的目录时仍检索该目录。目录检索静默跳过前 8 KiB 探针判定的二进制文件，直接指定二进制文件则报错；UTF-16 BOM 文本仍可检索。正则使用 ripgrep 的 Rust regex 引擎，`\w` 等简写字符类默认按 ASCII 匹配，不启用 PCRE2。

### 受管 IDE 与复用数据连接

Daemon 受管运行内置的 code-server，响应 `IDE_COMMAND` 的视图创建、恢复、切换目录、聚焦终端、分离与关闭等元数据动作，返回 `IDE_RESULT`；
数据面不再通过控制命令请求 per-exchange 物理流。控制通道下发 `DATA_BIND` 时携带 generation、一次性票据和受控 `streamUrl`，
Daemon 连接 `/api/harness/environment-data/v2/{environmentId}?ticket=...`，在一条物理 WebSocket 内复用多条 HTTP/WS mux 流。
每条 OPEN 必须匹配仍有效的 `viewId` 与 `bindingEpoch`，Daemon 只访问该视图作用域的私有 code-server 路径
`/__kk/ide/view/{viewId}/{bindingEpoch}`；第一次物理写失败即终止连接，不自动重连或重放请求。

视图权威在 Daemon 进程内（[`service.ts`](../../daemon/src/ide/service.ts)）：每个 Environment 最多 64 个视图，
OPEN/CLOSING/创建预留都由本进程裁决；claim 在任何进程或文件系统工作之前占用额度。绑定身份是
`viewId` + `documentId` + `bindingEpoch`，`bindingEpoch` 由 Daemon 在成功绑定时递增。
`mode=create` 未指定目录时使用宿主 HOME，`mode=resume` 不隐式创建视图也不改目录；相同 `claimId` 的完全相同请求返回同一绑定，
参数漂移被拒绝。失败回执只携带安全大写 code 与去敏 message（如 `VIEW_GONE`、`VIEW_IN_USE`、`DIRTY`、`CLEANUP_FAILED`、`BUSY`、`UNAVAILABLE`）。

使用证明只来自 `IDE_LEASE` 批次：45 秒没有任何有效证明即不再视为活跃占用并允许恢复竞争；最后一次有效证明起 600 秒仍无新证明进入到期释放；
创建成功但一直没有浏览器证明的视图最多保留 600 秒；`DETACH_VIEW` 立即冻结该 epoch 的续租，迟到证明不能复活它。释放走 `OPEN → CLOSING` 门闩：
正常关闭先由当前桥接宿主检查未保存内容并接受关闭，随后才中止该视图的 HTTP/WS 流，以 10 秒上限等待流收敛，再调用 code-server 的视图终止接口，最后释放 view map、锁、计时器与受管句柄。dirty 或未知状态的拒绝不释放流或终端。
终止失败保持 CLOSING 并报告 `CLEANUP_FAILED`，不误报已释放。最后一个视图释放且无在途数据流后，闲置 60 秒停止整个 code-server 与数据连接。
普通文本的未保存内容写入私有受管恢复文件，总量上限 16 MiB；备份失败产生有界事件，不伪报完成。

Daemon 通过 code-server 的本机管理接口操作视图（[`codeServer.ts`](../../daemon/src/ide/codeServer.ts)）：`GET /__kk/ide/views`、
`GET /__kk/ide/views/{viewId}?bindingEpoch=...`、`POST /__kk/ide/views/{viewId}` 与 `DELETE /__kk/ide/views/{viewId}`，
请求头携带本次启动生成的 `x-kk-studio-management-token`，只监听私有 loopback。

工作台桥接还区分扩展宿主实例（[`bridge.ts`](../../daemon/src/ide/bridge.ts) 与
[`bridge.cjs`](../../daemon/ide-extension/bridge.cjs)）：每个宿主生成稳定 `hostId`，普通断线沿用它重连；
被替换或退役的宿主不能重新取得控制，即使它报告相同目录。命令及结果绑定具体注册流，旧流的关闭和迟到回执不作用于新流。
替换受管工作区的首目录时，旧宿主在公共 API 接受请求后退役控制通道；只有新宿主实际登记目标目录才确认切换。
目录成功回执包含 Daemon 确认的规范路径，Server 不回填客户端输入。宿主退役不终止保留的终端，终端仍由受管视图负责。
每个 view 最多记录 256 个宿主身份，跨重绑保留、释放视图时清空；达到上限后只允许当前宿主重连，不淘汰旧身份来接纳新宿主。

### IDE 轻量默认值与按需能力

桥接扩展通过原生 `configurationDefaults` 贡献默认值（不写用户 settings，也不禁用扩展）：`task.autoDetect` 与 `npm.autoDetect` 为 `off`，`npm.fetchOnlinePackageInfo` 为 `false`，`typescript.disableAutomaticTypeAcquisition` 为 `true`。这些声明由 IDE 扫描扩展时注册，不依赖桥接代码激活；用户或工作区的显式设置优先。需要自动发现任务、npm 脚本、在线包信息或自动获取 JavaScript 类型包时，可在 IDE 设置中修改对应项。

Git 不设置覆盖值，保留原生自动仓库发现。文件树、编辑、语法高亮、主动搜索、终端、调试、扩展安装与目录切换桥接保留原生能力；实际调试器与高级语言能力仍取决于已安装扩展和宿主工具链。关闭 npm 自动发现避免持续自动扫描 package.json，但不承诺没有 ripgrep：扩展激活探测、主动搜索或用户重新启用发现仍可能执行检索。

上述默认值以桥接扩展被 IDE 启用为前提；原生工作区信任限制仍然生效，受限工作区可能禁用桥接、Git 或语言扩展，不代表这些扩展的配置贡献已经加载。

内置 TS/JS 与 JSON 语言服务按语言文件或相关命令激活；关闭自动类型获取不关闭 TS/JS 补全、诊断，也不删除项目已有类型依赖。关闭 npm 在线包信息并不代表所有 IDE 功能离线（例如 JSON schema、扩展安装仍可能联网）。内置 Java 扩展只有语法与片段，不包含 JDTLS；Java 语义能力需另装 IDE 扩展。下述 Agent LSP 独立配置、启动与回收，不会自动成为 IDE 的语言服务器。

### Agent LSP

LSP 组（[`lsp.ts`](../../daemon/src/coding/lsp.ts)）从配置中的 `lsp.servers` 初始化，按扩展名匹配语言服务器，并在目标文件所属项目根下复用 stdio JSON-RPC 连接。三项能力默认超时 180 秒覆盖启动等待、initialize 与 query 全程，统一消费 `context.signal`；共享池初始化默认 180 秒独立生命周期，单个 waiter 停止不影响其他等待者，最后退出等待 cleanup；闲置 5 分钟自动回收客户端与 1 秒 shutdown 机制保持不变；反编译能力（`lsp.java-decompile`）仅在匹配到 jdtls 服务器时可用。语言服务器的 `ManagedProcess.exit` 可能早于受管范围收尾（命令退出或 helper fallback）；已发出请求收到退出报告后等待同一 `stop` 成功再确认，正常服务端 RPC 响应不经停止路径。

退出确认覆盖本次目标 RPC，不代表共享语言服务器整体退出。参数、租约或发送前失败确认的是目标 RPC 未发出，不能据此推断服务器未启动、初始化未进入或启动资源已收敛；正常 RPC 响应同样不要求停止共享服务器。

退出证据按本次调用拥有的范围判定。尚未加入 boot、未接管 client 且未发出目标 RPC 的调用，在等待旧退休或已取消 boot 收敛期间取消、超时，直接确认本次未启动，旧 `stop` 的后续成败不改写这次确认；因旧退休失败或失败 boot 清理被直接拒绝的新调用也只确认自身，绝不确认原失败启动的参与者或失败清理范围。已加入活跃或共享 boot 的等待者必须等成功交付给其他 owner 或清理成功等正向证据。已发出目标请求后，若未取得服务端响应等正向证据，取消、超时或连接结束本身不能确认退出，仍须等待同一客户端 `stop` 成功。

## 二进制直传与在线更新

二进制文件与媒体结果先向服务端申请预签名上传票据，通过 [`transport/resources.ts`](../../daemon/src/transport/resources.ts) 直接 HTTP PUT 上传对象存储，字节不经过 WebSocket 控制管道。

受管更新由 [`update.ts`](../../daemon/src/update.ts) 驱动：校验官方发布物 URL、SHA-256 摘要与发行清单后，将暂存产物交给操作系统级独立任务
（Linux `systemd-run`、macOS `launchctl`、Windows 计划任务）执行二进制目录原子替换并重启受管服务。
下载 watchdog 只由非空 body chunk 刷新：连续 300 秒没有有效数据才中止，headers、redirect 与空 chunk 不算进展，累计下载时长不设硬上限。
body 完整交付后先停止 watchdog，再关闭 dispatcher；外部取消、无进展超时与网络失败分别报告，不自动重试或续传，也不泄漏 URL 与凭据。

更新是当前状态而不是任务流水：Daemon 只在内存里维护 `runtimeStatus`，没有更新历史表，也没有历史查询接口。
替换由本机更新器完成，它持有安装目录级内核互斥（`updates/install.lock`）贯穿旧进程退出、备份与替换：
Daemon 先把本次交接冻结为私有 `handoff.json`，再写出许可 `permit` 与接管确认 `update-takeover.json`，独立更新器据此停止旧受管范围、
把旧发行改名为备份、再把暂存目录改名为发行位置，每一步写入 `update-steps.json`。
新 Daemon 完成本机版本与配置预检后写入 `startup-ack.json`，并等待更新器写出 `update-result.json` 且释放互斥，之后才进入正常 READY。
崩溃后只能凭明确的 handoff、目录布局与进程事实完成或拒绝恢复；失败路径保持旧归档可用，不承诺任意失败点都能自动回滚。
正式的成功证据来自目标版本 Daemon 的 READY，而不是文件下载完成或连接断开。

## 质量守卫与验证

Daemon 拥有独立的 Node.js 工程与测试套件：
- 类型检查：`npm --prefix daemon run check`
- 编译构建：`npm --prefix daemon run build`
- 单元与集成测试：`npm --prefix daemon run test`
- 覆盖率统计：`npm --prefix daemon run coverage`（基于 Vitest 与 `@vitest/coverage-v8`）

配置贡献的真实 extension-host 集成测试：安装前端测试依赖与 Playwright Chromium 后，运行 `KK_STUDIO_IDE_ACCEPTANCE_DIR=<解压的code-server目录> node --test daemon/test/ide/configuration-native.test.mjs`（Node 24）。测试用独立临时 HOME、user-data、extensions 和动态 loopback 端口启动三个可信工作区，比较无桥接、桥接默认值、用户显式覆盖的 `inspect/get` 结果；不修改真实用户设置，不请求模型。

多平台 CI 由 [.github/workflows/daemon-ci.yml](../../.github/workflows/daemon-ci.yml) 守卫，覆盖 Linux (amd64/arm64)、macOS (arm64/amd64) 与 Windows (amd64) 五大目标环境。发行归档由 [`scripts/daemon/prepare-release.sh`](../../scripts/daemon/prepare-release.sh) 暂存，并由 [`scripts/daemon/smoke-archive.sh`](../../scripts/daemon/smoke-archive.sh) 与 [`scripts/daemon/smoke-archive.ps1`](../../scripts/daemon/smoke-archive.ps1) 验证自足运行能力。

上级：[系统设计](../system-design.md)。相关文档：[Harness Environment](harness-environment.md)、[Environment Daemon 安装与运行](../operations/environment-daemon.md)。
