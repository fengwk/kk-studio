# 内置工具与异步委派

本文说明内置工具的使用与执行契约：环境工具由 Daemon 执行，task 在 Harness 中持久接受委派。工具说明、参数 schema 和实现共同给出一次调用所需的输入。模块分工见[系统设计](../system-design.md)。

## 环境与路径

Environment 提供 OS、用户、HOME 等宿主事实。任务路径由用户、Issue 或委派提示词给出；相对本地路径必须提供绝对 `workdir`。子 Agent 按配置继承 Environment，并从自己的对话历史执行；并行 Thread 共享所选宿主文件系统，文件隔离由任务安排与部署决定。

文件能力使用 Java 实现；`write` 与 `edit` 分别承担完整写入和精确替换。参数使用 snake_case，Schema、提示词、实现和错误说明保持一致。

## 文件读取

`read` 从 `offset` 行、`column_offset` 列开始，两者默认 1；`limit` 默认且最大 2000。正文累计最多 60000 Unicode code point，不计行号、元数据及行分隔符。起始行片段占一行额度，后续行从行首读取。列参数只用于文本，不限制只能读一行。

读取以整体行数和字符数限制，不限制单行长度。流式跳过起点之前的内容，仅收集窗口，不能先丢弃长行尾部。到达预算且恰好 EOF 不算截断。续读坐标指向第一个未返回字符，完整行边界归一到下一行第一列。空文件和越过 EOF 使用明确空范围；有效目标行上的越界列报错。CRLF、CR 和 LF 都作为行边界，Unicode 代理对不得拆开。

```text
path: /srv/project/example.ts
ends_with_newline: yes
range: 1:1-83:1240
truncated: yes
truncation_reason: character_limit
next: 83:1241
lsp: supported (typescript)

 1|...
83|...

[TRUNCATED: More file content remains. Next position: line 83, column 1241.]
```

未截断时省略 truncated、truncation_reason、next 和尾部警告；range 保留。LSP 是 header 最后一行，仅在文件有对应可用服务器时输出。正文只能包含实际文件内容，不夹带截断标记，不生成下一次工具调用教程。ends_with_newline 描述整个文件；不得将片段结束当作文件结尾。元数据所需扫描保持有界内存并支持取消。

本地文本与受管文本通过 `TextReadWindow` 共用分页契约。受管文本在输入流回调内扫描，不先把整份 Blob 读入内存；为准确报告文件级元数据仍需扫描到 EOF，超时或取消直接中止连接。受管资源按当前 Thread 所属 Session 的 Blob 引用鉴权，不能凭另一 Session 的 Blob UUID 跨会话读取。

Platform 终态化器另有内联预算：普通工具为 50 KiB / 2000 行，可信内置 `read` 放宽为 320 KiB / 2020 行，以容纳完整读取窗口及 header/footer，仍受终态 JSON 与资源硬上限约束。目录清单的展示上界为 48 KiB，分页默认和最大 2000；图片与目录维持独立返回语义。

## 文件变更与搜索

现有文件原子替换保持文件系统支持的权限；edit 保持编码、BOM 和换行风格，write 按完整内容写入。无法无损编码则拒绝。提交成功后，通知或展示故障单独报告。diff 展示真实行变化（含末尾换行），超过 30 × 1024 个 Java 字符（UTF-16 code unit）时截断并标注；裁去公共前后缀后，变更区域的原行数乘新行数超过 2,000,000 时改用整段删除/新增。设备、FIFO 等非普通文件在 I/O 前拒绝。

grep 对完整搜索内容匹配，展示缩略与搜索完整性分开。超时、资源限制和读失败各自报告；搜索范围、正则支持与输出上限按能力 schema 和 Daemon 实现校验，取消收尾须等待实际计算终止。

grep/find 共享仓库忽略解析：从搜索位置解析祖先 .gitignore、分层规则、否定规则和 .git/info/exclude，支持 .git 文件形式的 worktree。不因调用 workdir 变化而改变同一目标的忽略行为。不引入 Environment 根目录。

## 命令执行

bash 在显式 workdir 中执行一次命令。成功、非零退出、超时和取消都收尾并保留已捕获输出；大输出保存本地日志，结果区分捕获限制和保存失败。取消或失败后已发生的副作用仍需核查。终止先温和停止再强制收敛，平台差异见 [Daemon 进程执行范围](harness-daemon.md#进程执行范围)。

## LSP 生命周期

根据目标文件自动选择服务器并发现项目根；调用方不提供额外 cwd。项目根用于 rootUri/workspaceFolders 和服务器进程目录，不改变文件工具路径规则。仓库边界内选择构建根或最近标记，没有标记回退文件目录，不能跨 worktree 边界错误复用。

Java 客户端按项目根、server ID 和配置指纹复用，去重并发初始化。查询前同步文件，处理 JSON-RPC、服务能力、服务器请求及位置编码。write/edit 的提交与后续同步分别报告。查询提供 definition、workspace symbols 和 JDTLS Java 反编译，正文按语言服务器返回的源码展示。

客户端归 Daemon 所有，无在途请求且闲置 5 分钟回收；同一复用键的新实例只在旧实例停止之后启动；回收与关闭的清理在派发被拒时同步兜底，关闭同时收尾已登记退休的实例，队列不执行也不会留下进程或悬挂的等待者。Thread 结束不关闭共享实例。Daemon 退出全部关闭；配置失效停止旧实例接收新请求并有界收尾；异常退出使当前请求失败，后续可重建。关闭顺序为 shutdown、exit、有界等待和必要的强制终止；初始化中实例也必须能清理。外部语言服务器需要预先配置，不自动安装。

## 异步 task

task 接受 subagent_type、prompt、可选 max_turns、可选 thread_id。新任务创建子 Thread；thread_id 在原历史上继续，允许切换 Agent，采用目标配置和权限。继续由子 Thread 的持久父发起，以不可变父子关系验证身份，并复验父 head 与停止边界。向仍执行的子追加输入会排队，等下一次 turn 边界。max_turns 是阶段汇报软预算：达到后由 Runtime 以 `TASK_BUDGET` 通知注入提醒。

忙时继续不打断当前模型或工具调用，也不覆盖其冻结请求；目标 settings 与新 prompt 一起入队，在后续 INPUT 边界消费。若同一子 Thread 上有多条尚未结算的委派，它们可以在整体收敛后共用同一个最终回答，各按自己的 invocation 身份和任务原文交付一次；这不是每个 prompt 独立产出报告的保证。

从历史边界 fork 的 Thread 是独立执行根，只共享边界之前的 Entry 路径，不继承源 Thread 的待处理命令、子执行或 Join 订阅。原有子任务之后的回执仍交给原父 Thread；新分支不能通过旧 thread_id 接管原有子执行。已在分叉前进入共享历史的回执仍可见。

持久接受后返回 JSON `{"thread_id":"...","status":"accepted"}`，不等待结果。即时回执不重复 prompt；tool_result 的 details 带 `kind=task.accepted` 供 UI 识别，其 thread_id / status 与回执一致，另附会话与幂等元数据。

完成消息结构包含 thread_id、本次 Agent、状态、本次任务原文及结果。失败时分离 error 和 partial_result。正文正确转义；task 原文是历史引用不是给父的新指令。任务原文绑定本次调用，不使用首次创建时的任务。长文本沿用资源化，保留完整访问入口。

完成结果不是原 `task` 调用返回的第二个结果：`task` 只返回接受回执，子 Thread 在源输入应用后、且没有未完成直接子 Join / 未送达子回执 / 待处理输入时，以最终回答或不可继续失败冻结 `terminalEntryId` / `finalAnswerEntryId` 结算 join；Stop 强制结算不受上述收敛条件限制。结算结果再以一条 `NOTIFICATION` 命令进入父的下一安全轮次。父为 `RUNNABLE` 时请求 THREAD wake；父为 `STOPPED` 时通知直接固化进历史，接受新任务输入后才继续模型。多条结果可以合并处理，父仍负责最终验收。

### 持久义务与交付

Thread 的空闲只描述自身：无待处理命令、无本地适用 Invocation、无 continuation 或到期压缩义务。等待审批或人工输入仍属于适用 Invocation；等待子 Join 不影响父自身的 `IDLE`，也不空转模型或保留等待线程。子任务按各自收敛终态逐层结算并通知父：源输入已应用且没有未完成的直接子 Join、未送达的子回执或待处理输入时，本地终态才作为委派收敛点向上回执，本地 `IDLE` 本身不足以保证整次委派可交付。

内部以工具 invocation ID 标识本次委派，join 记录父子 Thread、源命令、冻结的终态/最终回答 Entry 与投递坐标；固定归属与每次执行身份分开。执行由 Session/Thread/Entry/Command/Work 的统一生命周期推进。

子 Thread 终态、join 冻结与父通知交付在同一数据库事务内完成。接受与结果交付使用工具 invocation ID
作为稳定幂等身份；并发额度按尚未冻结终态的子 Join 计，同一子 Thread 上的多条未完成 join 各占一份，
root ticket 不占子任务名额。忙时继续仍需通过单父与全局额度准入，额度已满则明确拒绝。
Work 通知只降低延迟，周期 claim 扫描兜底进程重启和通知丢失；
父为 `STOPPED` 时通知只固化、不唤醒。

失败、取消有明确结果路径。未知副作用不能自动重跑；停止未确认不能当作已经安全停止。父停止向后代传播，迟到通知不能绕过停止门禁。父的执行恢复与产品收尾只按自身持久事实与 root Join 冻结回执判定，不按子树空闲反查业务门禁；底层 live/historical 分类只描述本地历史适用性。

## 失败与纠正指引

模型可见的失败统一为三段式英文文案：发生了什么、执行事实、下一步。执行事实只有三种，且必须与真实状态一致：

- 派发前校验拒绝（参数/schema、未知工具、权限、未绑定 Environment、路径或 `workdir` 不合法）声明 `The tool was not executed.`；
- 已进入执行后的确定失败声明 `The tool ran but failed; side effects may have occurred.`；
- 超时、取消、提交不明或远程断开声明 `Whether the tool took effect cannot be confirmed.`，不得声称未执行；bash 的取消与超时仍保留真实的进程终态事实（`EXITED` / `TIMED_OUT` / `CANCELLED`）。

文案只陈述事实与恢复动作，不包含自动重试倾向；字段错误不回显含凭据的原始参数，异常路径只暴露安全描述而不是异常栈。未知工具列出当前冻结绑定的允许工具名并要求改用其中之一。路径与 `workdir` 校验在执行前完成并要求绝对路径（bash 的 `workdir` 必须绝对、存在且为目录）。`task` 的参数拒绝在文案中给出续用或新建所需的 `subagent_type`、`prompt` 与 `thread_id`，不打断既有 Thread。

## 测试入口

逐用例的来源、适用性与对应测试维护在 [内置 Read 测试映射](../operations/builtin-read-tests.md)、[内置 Bash 测试映射](../operations/builtin-bash-tests.md)、[内置检索测试映射](../operations/builtin-search-tests.md)、[内置 LSP 测试映射](../operations/builtin-lsp-tests.md)、[内置 Task 测试映射](../operations/builtin-task-tests.md) 与 [内置文件变更测试映射](../operations/builtin-mutation-tests.md)。

测试映射按读取、修改、检索、命令、LSP 与 task 分组，覆盖分页边界、失败后的文件/进程状态、协议生命周期及委派接受与交付。自动化入口与覆盖率门禁见 [开发与测试](../operations/development-and-testing.md)。

技术实现和验证分别参考 [Daemon](harness-daemon.md)、[Builtin](harness-builtin.md)、[Runtime](harness-runtime.md) 和 [开发与测试](../operations/development-and-testing.md)。
