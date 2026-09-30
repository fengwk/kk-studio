# 内置工具与异步委派

本文定义内置工具的当前契约，供实现、代码审查和自动化验收使用。环境执行由 Daemon 承担，委派执行复用 Harness；工具描述必须足以让没有历史对话的 Agent 正确使用。先了解[系统设计](../system-design.md)中的 Harness 与 Environment 边界。

## 环境与路径

Environment 提供宿主事实，不提供默认 cwd。`home` 仅用于展示，不作为项目目录。任务路径来自用户、Issue 或委派提示词；相对本地路径必须提供绝对 `workdir`。子 Agent 按配置继承 Environment，不继承父对话。并行 Thread 不是隔离文件系统。

文件能力使用 Java，不依赖 rg/fd。保留 `write/edit`，不增加编辑协议。参数使用 snake_case，Schema、提示词、实现和错误说明保持一致。

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

本地文本与受管文本保持同一分页契约，下游没有独立的固定字节上限；48 KiB 只是目录清单的展示上界。图片、目录维持独立返回语义，目录分页默认和最大 2000。

## 文件变更与搜索

write 用于新建或完整覆盖，edit 用于精确替换。现有文件原子替换须保持文件系统支持的权限；edit 保持编码、BOM 和换行风格，write 的完整内容按其明确写入契约处理，不做意外内容变换。无法无损编码则拒绝。成功提交后的通知或展示失败不得误报为未修改。diff 只展示真实行变化（含文件末尾换行有无），内联 diff 超过 30 KiB 字符即截断并显式标注；行级对齐超过 2,000,000 个单元格时退化为整段删除加整段新增，仍是真实行。设备、FIFO 等非普通文件必须在 I/O 前拒绝。

grep 对完整搜索内容匹配，展示缩略与搜索完整性分开。超时、资源限制和读失败不可报告为无匹配；任意正则不承诺无限输入和固定内存同时成立。Java 实现需明确限制并中断，不能以线程取消假装已经终止不可中断的计算。

grep/find 共享仓库忽略解析：从搜索位置解析祖先 .gitignore、分层规则、否定规则和 .git/info/exclude，支持 .git 文件形式的 worktree。不因调用 workdir 变化而改变同一目标的忽略行为。不引入 Environment 根目录。

## 命令执行

bash 是显式 workdir 的单次命令，不管理常驻终端。成功、非零退出、超时和取消都收尾并保留已捕获输出。大输出沿用本地日志存储，清晰报告捕获限制和保存失败。取消或失败不代表副作用回滚。终止先温和停止再强制收敛，验证平台差异。提示词不得残留 shell 模板变量或声称错误的实际执行 shell。

## LSP 生命周期

根据目标文件自动选择服务器并发现项目根；调用方不提供额外 cwd。项目根用于 rootUri/workspaceFolders 和服务器进程目录，不改变文件工具路径规则。项目标记选择参考 pi-base：仓库边界内选择构建根或最近标记，没有标记回退文件目录，不能跨 worktree 边界错误复用。

Java 客户端按项目根、server ID 和配置指纹复用，去重并发初始化。查询前同步文件，处理 JSON-RPC、服务能力、服务器请求及位置编码。write/edit 后的同步不能使已提交修改失败。保留 definition、workspace symbols 和 JDTLS Java 反编译三种工具；源码正文不得被路径替换污染，不用 javap 字节码冒充源码。

客户端归 Daemon 所有，无在途请求且闲置 5 分钟回收；同一复用键的新实例只在旧实例停止之后启动；回收与关闭的清理在派发被拒时同步兜底，关闭同时收尾已登记退休的实例，队列不执行也不会留下进程或悬挂的等待者。Thread 结束不关闭共享实例。Daemon 退出全部关闭；配置失效停止旧实例接收新请求并有界收尾；异常退出使当前请求失败，后续可重建。关闭顺序为 shutdown、exit、有界等待和必要的强制终止；初始化中实例也必须能清理。外部语言服务器需要预先配置，不自动安装。

## 异步 task

task 接受 subagent_type、prompt、可选 max_turns、可选 thread_id。新任务创建子 Thread；thread_id 表示在原历史上继续，允许切换 Agent，使用目标配置和权限。继续只允许子 Thread 的持久父发起：父身份取自不可变的父子关系而不是调用参数，且父必须仍然接受本次调用（head 未被推进、未停留在停止边界）。子是否已结清不影响继续——向仍在执行的子追加输入即排队，等它真正的下一次空闲。max_turns 是阶段汇报软预算，不伪装成强制执行上限。

持久接受后返回唯一 JSON `{"thread_id":"...","status":"accepted"}`，不等待结果。不保留 session_id/maxTurns 别名，不提供同步开关或查询等待工具，也不提供 XML 或其它别名形状。即时回执不重复 prompt；tool_result 的 details 带 `kind=task.accepted` 供 UI 识别，其 thread_id / status 与回执一致，另附会话与幂等元数据。

完成消息结构包含 thread_id、本次 Agent、状态、本次任务原文及结果。失败时分离 error 和 partial_result。正文正确转义；task 原文是历史引用不是给父的新指令。任务原文绑定本次调用，不使用首次创建时的任务。长文本沿用资源化，保留完整访问入口，不固定裁掉 8000 字符。

完成结果通过持久命令进入父下一安全轮次，不复用已完成工具调用的第二个结果。父等待子任务时自动唤醒；父明确停止时只保存不唤醒。多条结果可以合并处理，父仍负责最终验收。

### 持久义务与空闲

Thread 整体空闲要求本地执行、待处理命令、continuation 和未结清子任务都为空。未结清覆盖排队、运行、审批、恢复与停止确认，但不含「结果尚未投递给父」：是否存在未交付的 join 回执不影响判定，等待子结果不是本地工作。父当前模型轮次结束不等于整体任务完成；等待时不轮询、不 sleep、不空转模型。嵌套任务按直接父子逐层结清。

内部用工具 invocation ID 唯一标识本次委派，记录父子 Thread、本次执行边界、结果和投递状态；固定归属与每次执行身份分开。复用 Session/Thread/Entry/Command/Work，不建立第二执行引擎。

父结果入队与子任务结清必须无空闲空档；在同一数据库事务交接。派发和通知按稳定幂等键重试。并发额度、同 Thread 重叠拒绝和未结清状态基于持久事实，不以内存 registry 为权威。重启恢复观察和投递；事件降低延迟，恢复扫描兜底。不保留每任务长期阻塞等待线程。

失败、取消有明确结果路径。未知副作用不能自动重跑；停止未确认不能当作已经安全停止。父停止向后代传播，迟到通知不能绕过停止门禁。整体空闲语义用于业务完成门禁和恢复校验，底层 live/historical 分类继续只描述本地历史适用性。

## 验收

行为由自动化测试承接，逐用例记录源用例、适用性判断与 kk 对应用例，映射维护在 [内置 Read 测试映射](../operations/builtin-read-tests.md)、[内置 Bash 测试映射](../operations/builtin-bash-tests.md)、[内置检索测试映射](../operations/builtin-search-tests.md)、[内置 LSP 测试映射](../operations/builtin-lsp-tests.md)、[内置 Task 测试映射](../operations/builtin-task-tests.md) 与 [内置文件变更测试映射](../operations/builtin-mutation-tests.md)。与当前契约冲突的源行为改写而不是照搬；Node 私有 API、TUI 展示与 apply_patch 协议不适用，但搜索、转义、编码、特殊文件与生命周期行为必须逐项落点，不能按文件名排除。

回归覆盖 Unicode/换行/EOF/长行无损续读、权限与提交边界、忽略规则、完整搜索、失败日志、LSP 协议和回收、异步接受/换 Agent/权限/幂等/重启/停止/嵌套/无空闲交接窗口。新测试注明意图；结构化数据放测试资源。核心路径 JaCoCo 行覆盖率目标至少 90%，分支作为参考，平台专属检查不得用跳过宣称通过。

技术实现和验证分别参考 [Daemon](harness-daemon.md)、[Builtin](harness-builtin.md)、[Runtime](harness-runtime.md) 和 [开发与测试](../operations/development-and-testing.md)。修改仅在隔离 Workspace；数据库变更离线验证，不操作已部署数据库和服务。
