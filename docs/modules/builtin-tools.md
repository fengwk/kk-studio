# 内置工具与异步委派

本文说明内置工具的使用与执行契约：环境工具由 Daemon 执行，内部 `load_skill` 从技能归档取出正文，task 在 Harness 中持久接受委派。工具说明、参数 schema 和实现共同给出一次调用所需的输入。模块分工见[系统设计](../system-design.md)。

## 环境与路径

Environment 提供 OS、用户、HOME 等宿主事实。任务路径由用户或委派提示词给出；`read` 的 `path` 可以是 `resource://files/<uuid>`、`resource://skills/<package>/<version>/<skill>/<relativePath>`，也可以是 Environment 上的绝对本地路径；两个 URI 命名空间都由平台读取，本地路径需要 Environment。`write`、`edit`、`grep`、`find` 只接受 Environment 绝对路径。LSP 的 `path` 必须是绝对本地路径，相对路径直接拒绝；bash 要求显式绝对 `workdir`。子 Agent 使用执行根当前 Environment，并从自己的对话历史执行；并行 Thread 共享所选宿主文件系统，文件隔离由任务安排与部署决定。

宿主文件能力由 Environment Daemon 实现；`write` 与 `edit` 分别承担完整写入和精确替换。参数使用 snake_case，Schema、提示词、实现和错误说明保持一致。

## 技能加载

Agent 配置了技能时，工具面自动加入 `read` 与内部 `load_skill`：`load_skill` 只接受当时冻结在 binding 里的技能名，先校验包仍是该版本且包含该技能，再从归档中只读取 `<skill>/SKILL.md`。所在回合绑定了 Environment 时，它随 `skill.install` 请求附上归档 Blob 与 transferId，Daemon 换取预签名下载地址、校验大小与摘要后解压发布，返回的 `skill_dir` 是解析相对路径的根；未绑定 Environment 时直接返回正文，并给 `resource://skills/<package>/<version>/<skill>/<relativePath>` 基准 URI，由 `read` 取其余文件。技能不在 binding 内、包版本已变或技能已不再发布都以明确错误结束。

## 文件读取

`read` 从 `offset` 行、`column_offset` 列开始，两者默认 1；`limit` 默认且最大 2000。正文累计最多 60000 Unicode code point，不计行号、元数据及行分隔符。起始行片段占一行额度，后续行从行首读取。列参数只用于文本，不限制只能读一行。

读取以整体行数和字符数限制，不限制单行长度。流式跳过起点之前的内容，仅收集窗口，不能先丢弃长行尾部。到达预算且恰好 EOF 不算截断。续读坐标指向第一个未返回字符，完整行边界归一到下一行第一列。空文件和越过 EOF 使用明确空范围；有效目标行上的越界列报错。CRLF、CR 和 LF 都作为行边界，Unicode 代理对不得拆开。

Daemon 读取本地文件时，文本编码由统一的 codec 决定，顺序为 BOM、标准声明、默认 UTF-8：识别 UTF-8/UTF-16/UTF-32 BOM，以及 XML、CSS、HTML `<meta charset>`、Python/Ruby 编码注释与 JSP page 指令中的声明。流式和整文件解码都只在文件前 8000 字节内探测声明，不做统计猜测，也不推测无 BOM 的 UTF-16。非法输入不会拒绝文件，而是替换为 U+FFFD 并报告替换次数：UTF-8 按最长非法子序列各替换一个（与 Node、浏览器和 `rg --encoding utf-8` 一致），声明为 US-ASCII 时每个高位字节替换一个，BOM 标记的 UTF-16/32 把未配对代理项、越界或位于代理区的 UTF-32 值和截断的末单元各替换一个，声明为传统编码时采用解码器的替换输出。非 UTF-8 的文件在 header 输出 `encoding: <名称>`（UTF-8 BOM 不输出）。仍被拒绝的只有无法使用的声明（二进制另按本段末尾的规则判定）：不支持的标签、无法以 ASCII 写出的标签（否则声明本身不可定位）、缺少 BOM 的 UTF-16/UTF-32 声明，以及超过 64 MiB 的声明传统编码（x/text）文件。前 8000 字节内出现 NUL 视为二进制（UTF-16/32 BOM 文件按解码后的字符判断），其余控制字符按文本处理。声明为传统编码的文件按整文件解码，上限 64 MiB；read 的窗口读取对 UTF-8 与 BOM 标记的 UTF-16/32 按窗口流式解码，两条路径的替换结果一致。

```text
path: /srv/project/example.ts
range: 1:1-83:1240
truncated: yes
truncation_reason: character_limit
next: 83:1241
lsp: supported (typescript)

 1|...
83|...

[TRUNCATED: More file content remains. Next position: line 83, column 1241.]
```

未截断时省略 truncated、truncation_reason、next 和尾部警告；range 保留。LSP 是 header 最后一行，仅在文件有对应可用服务器时输出。正文只能包含实际文件内容，不夹带截断标记，不生成下一次工具调用教程。ends_with_newline 仅在读取到文件 EOF 时输出，描述整个文件；窗口已填满且确认还有内容时省略该字段，并停止读取。窗口之后的非法编码不影响当前窗口读取。元数据所需扫描保持有界内存并支持取消。

本地文本与受管文本通过 `TextReadWindow` 共用分页契约；Daemon 侧的本地实现是 [`coding/text.go`](../../daemon/internal/coding/text.go)，按同一 offset/column_offset、行数与码点预算和截断判据读取。窗口填满并确认还有后续内容后，本地读取立即停止，不扫描到 EOF。受管文本在输入流回调内扫描，不先把整份 Blob 读入内存；超时或取消直接中止连接。`resource://files/<uuid>` 指向全局 Blob，仅可读取处于 ACTIVE 状态的 Blob；读取时将 Blob 登记为本次调用所属 Session 的持有，无需该 Session 预先持有。`resource://skills/...` 只接受包的当前版本与已发布技能：平台按归档索引只下载目标条目的压缩范围并解压，校验大小与 CRC32 后按同一窗口返回文本，图片后缀以二进制内容返回。权威 MIME 为 `image/*`/`audio/*`/`video/*`/`application/pdf` 时走媒体路径：在读取任何内容前用该 Turn 在 `TURN_START` 冻结的消费者媒体能力、MIME 及内联预算校验，不支持则明确失败，通过后返回真实媒体字节（不是上传成功文本）。

Platform 终态化器另有内联预算：普通工具为 50 KiB / 2000 行，可信内置 `read` 放宽为 320 KiB / 2020 行，以容纳完整读取窗口及 header/footer，仍受终态 JSON 与资源硬上限约束。目录清单的展示上界为 48 KiB，分页默认和最大 2000；图片与目录维持独立返回语义。

内置 `read` 新读出的图片以 1080P 档位进入模型：按显示方向等比缩小到横图 1920×1080、竖图 1080×1920 或方图 1080×1080 的范围内，已在范围内的图片不重新编码。Environment 路径由 Daemon 缩放，`resource://` 路径在模型请求中物化；两者的新工具历史都冻结该档位，不修改原件、已有历史、普通聊天附件或缩略图。

## 文件变更与搜索

现有文件原地提交：打开后截断覆盖，保留 inode、所有者、权限与硬链接，只读目录中的可写文件也能写入，只读文件写入失败并保持原样；新文件以 0644 排他创建（受 umask 影响）并同步新文件及其父目录。提交不是崩溃原子的，失败时可能留下截断或部分写入的文件。edit 保持编码、BOM 和换行风格，会改变有效编码的编辑（结果声明解析为另一种编码，或删掉已有非 UTF-8 文件的声明）被拒绝并提示改用 write 转换。write 的完整内容以 U+FEFF 开头表示要求写入 BOM，该字符本身不写入内容；已有 BOM 的文件保持其编码与 BOM，不会叠加第二个。没有 BOM 的文件由内容中的标准声明决定：UTF-16/UTF-32 声明必须带 U+FEFF 请求（通用标签写 LE，显式标签写对应字节序），传统编码声明不能带 U+FEFF，UTF-8 声明或无声明按请求决定是否写 UTF-8 BOM，没有请求时写无 BOM 的 UTF-8。声明与 BOM 冲突、删除已有非 UTF-8 文件的声明、目标编码无法表示的文本都会被拒绝，拒绝发生在任何写入之前。提交成功后，通知或展示故障单独报告。设备、FIFO 等非普通文件在 I/O 前拒绝。

`write` 拒绝目标路径最后一段是符号链接的请求（包括悬空链接）。`edit` 跟随符号链接并修改链接指向的真实文件，链接本身不变；审批预览和提交结果中的 `editReview.path` 都显示解析后的真实路径。

`edit` 的参数为 `path` 与 `edits`（至少一项，单处修改也用一项），每项包含必填的 `old_string`、`new_string` 与可选 `replace_all`；顶层与条目内的其他字段都因 schema 的 `additionalProperties: false` 在发送前被拒绝。所有条目都在同一份原始文件的换行统一视图上精确匹配，不匹配前一项的输出；`old_string` 非空且恰好出现一次（`replace_all` 时为全部出现且彼此不重叠）。`new_string` 与 `old_string` 归一化后相同的条目允许与实际变更同批使用，但仍须匹配、计入重叠校验与替换数；应用时跳过此类条目，以保留命中位置的原始字节。

全部条目展开后的区间两两不得重叠，首尾相接可以。任一项不合法则整次调用不写入，结果按 1 起的条目序号一次列出全部出错条目：未找到时给出一条定位提示（误带 read 行号前缀时指出，否则给出忽略空白后能匹配的原始行号范围，最多 3 段并给出总数）；多处匹配时列出最多 10 处行号与总数；`replace_all` 自身重叠时列出重叠行号；跨条目重叠时为每对重叠条目各报告一次与所在行号。提示只供模型重试，从不自动套用模糊匹配。

校验通过后把实际变化的命中位置映射回原始字节，只按文件当前编码写入替换片段，未编辑及跳过条目的字节保持不变；新文本中的换行沿用被替换片段内第一个换行的写法，片段内无换行时用其所在行的行尾，该行无行尾时用文件第一个换行，文件无换行时用 LF。完整解码文本与原文相同时，在编码前报告“无变化”，不写文件也不发送文件变更通知；拼接后仍会检查输出字节是否与原文件相同。若无法安全确定字节边界，或拼接结果不能按原编码解码为预期的完整文本（例如有状态编码的移位状态不兼容），则不写入，并提示改用 write 重写整个文件。整次调用只做一次原地写入，结果与审批共享同一份合并 diff。

同一 Assistant 调用批次中的 Environment `edit` / `write` 按冻结的 Environment 与绝对 `path` 词法身份串行：同一目标文件只允许最早的未结束调用进入预检与执行；前项成功、失败、拒绝或取消后，再派发下一项。不同文件仍可并行。路径只按目标文件系统词法折叠分隔符、`.` 与 `..`，Windows 路径另忽略大小写；不解析符号链接。非 Environment 路径和无法解析的参数不参与此排序。审批逐项请求，因此后续 edit 的预览在前项写入之后生成。

需要人工批准的 `edit` 和 `write` 分别通过内部 `fs.edit.preview`、`fs.write.preview` 只读计算真实文件 diff，并冻结 canonical 路径与全部变更行。`edit` 与覆盖已有文件的 `write` 同时冻结原始字节 SHA-256；创建新文件的 `write` 将 SHA 设为 null，审批后的执行必须确认该路径仍不存在。覆盖文件时则必须与预览时的字节一致，否则拒绝写入。
Daemon 使用 Go diff 计算变更，保留变更行、上下文和 EOF 缺失换行标记。write 比较精确的解码文本（包括行尾）：只有文本完全相同才报告无变化；只有行尾变化也会提交，并在 diff 中列出受影响的行。增删行中的每个 CR 显示为 `␍`（U+240D）；上下文行省略紧贴 LF 的 CR，其他 CR 同样显示为 `␍`。新文件的所有行都作为新增行，遵守相同标记规则和统一的 review 传输预算。
原始文件解码出现替换时，审批 diff 只反映解码后的文本，无法区分原始非法字节和字面 U+FFFD。编码成功后，若原文件有解码替换，且 write 的完整新文本或 edit 实际写入的替换片段含 U+FFFD，预览与提交的 review（`editReview`/`writeReview` details）会携带 `lossyDecode`：`encoding` 为原文件的解码名称，`invalidSequences` 为原文件的解码替换次数。新建文件从不携带；edit 未触及的非法字节原样保留，不单独触发标记。带标记时，模型可见成功文本追加一行 `Note:`，审批卡片在 diff 上方显示警告；格式错误的 `lossyDecode` 使 review 无效。

完整 review 连同结果超过 1 MiB UTF-8 预算时明确拒绝，不截断变更行：预览直接拒绝，提交在写入目标文件之前拒绝，文件保持不变。协议和验证入口见 [Harness Environment](harness-environment.md) 与[开发与测试](../operations/development-and-testing.md)。

Daemon 在进程内实现 `find` 与 `grep`，不调用随包或宿主安装的 ripgrep。两者都要求显式的绝对 `path`；`find` 只接受目录，`grep` 也接受单个文件。`find.glob` 必填，用 `*` 查找所有文件；`grep.glob` 可选。两者使用同一套文件筛选规则：`*.ts` 匹配任意深度的文件名，`src/*.ts` 只匹配搜索根下 `src` 的直属文件，`**/src/*.ts` 也匹配嵌套 `src` 的直属文件。前导 `./` 不改变范围，单独 `./` 是无效筛选。`grep` 直接搜索单个文件时不应用 `glob`。

目录搜索的结果路径相对于 `path`；用 `read` 查看时，先拼回该绝对目录。`grep` 的单文件结果路径是绝对路径。`grep.pattern` 搜索文件内容，可包含换行；跨行匹配不另设 `multiline` 开关。`ignore_case` 只作用于内容，不改变文件 glob。检索默认超时 15 秒，可由正数 `timeout_seconds` 覆盖；超时保留已收集的 partial 匹配并显式标记 `search.incomplete`，取消则只返回 `[Search cancelled; results are incomplete.]` 与 `CANCELLED` 事实，不携带已收集的结果。grep 的 `limit` 按匹配数计算；多行匹配返回该匹配展开的行，结果仍受有界输出保护。正则或 glob 错误作为输入拒绝，无法读取的路径作为 skipped 报告。grep 匹配解码后的文本（非法字节按 U+FFFD 匹配），行号与 read 一致；二进制文件静默跳过，声明无法使用的文件在结果末尾按绝对路径列出，最多 10 个，形如 `[Search incomplete; not searched because the declared text encoding cannot be used: /abs/a, … and N more]`；直接以这样的文件为 `path` 时返回错误。

grep/find 按 Git 仓库边界应用忽略规则：仓库内只使用仓库根到目标文件之间的 `.gitignore` 与该仓库 `.git/info/exclude`；嵌套仓库只使用自身规则；仓库根以上的 `.gitignore` 和全局 Git excludes 不生效。仓库外仅使用搜索根及其以下的 `.gitignore`。支持 `.git` 文件形式的 worktree；隐藏文件会搜索，`.git` 会跳过，符号链接不跟随。忽略路径在结果中被略过；若没有匹配，会提示最多 10 个因忽略规则未搜索的路径。要搜索被忽略目录，可将其本身作为 `path`；`grep` 也可显式搜索被忽略的单个文件，`.git` 除外。

## 命令执行

bash 在显式 workdir 中执行一次命令，默认超时为 300 秒，由唯一的 invoke 信号驱动，无独立起表；成功、非零退出、超时和取消都收尾并保留已捕获输出，并在 `details.process.outcome` 中收敛进程终态（`EXITED`、`TIMED_OUT`、`CANCELLED`）。大输出保存本地日志，结果区分捕获限制和保存失败。取消或失败后已发生的副作用仍需核查。终止先温和停止再强制收敛，平台差异见 [Daemon 原生进程监管](daemon.md#原生进程监管)。

命令通过非交互的登录 shell（`bash -lc`）执行：Daemon 只用自身 PATH 解析配置中的 Bash 可执行文件，命令每次调用都由该 Bash 重新加载登录配置，因此 PATH 等环境变量取自本次登录 shell，而不是 Daemon 进程。要让命令找到工具，请在该用户实际加载的登录配置（如 `~/.bash_profile` 或 `~/.profile`）中设置 PATH；只改 `~/.bashrc` 不会生效，除非登录配置主动加载它。Daemon 在启动和每次应用配置时也采样这份登录环境，用于 LSP 可执行文件发现、`read` 头部的 `lsp:` 支持判定与语言服务器进程。修改登录配置后，下一次命令会重新加载它；重启 Daemon 可刷新 LSP 使用的快照。

## LSP 生命周期

根据目标文件自动选择服务器并发现项目根；调用方不提供额外 cwd。项目根用于 rootUri/workspaceFolders 和服务器进程目录，不改变文件工具路径规则。仓库边界内选择构建根或最近标记，没有标记回退文件目录，不能跨 worktree 边界错误复用。

LSP 客户端按项目根、server ID 和配置指纹复用，去重并发初始化。发送定义或工作区符号查询前，客户端从磁盘同步该会话已打开的文档，不扫描整个工作区。外部保存的内容会发送给语言服务器，已删除或移走的文档会关闭。定义查询还会同步定位所需的源文件；若该文件已不存在，或同步遇到读取、解码或协议错误，则不发送查询。客户端处理 JSON-RPC、服务能力、服务器请求及位置编码。write/edit 的提交与后续同步分别报告。查询提供 definition、workspace symbols 和 JDTLS Java 反编译，正文按语言服务器返回的源码展示。LSP 三项工具（`lsp_goto_definition`、`lsp_workspace_symbols`、`lsp_java_decompile`）默认超时为 180 秒，覆盖等待启动、initialize 与 query 全程，全程消费同一 `context.signal`。

客户端归 Daemon 所有，无在途请求且闲置 5 分钟回收；共享池初始化生命周期默认 180 秒（独立资源生命周期，不重起查询 timer），一个 waiter 停止不影响其他，最后退出等待 cleanup；同一复用键的新实例只在旧实例停止之后启动；回收与关闭的清理在派发被拒时同步兜底，关闭同时收尾已登记退休的实例，队列不执行也不会留下进程或悬挂的等待者。Thread 结束不关闭共享实例。Daemon 退出全部关闭；配置失效停止旧实例接收新请求并有界收尾；异常退出使当前请求失败，后续可重建。关闭顺序为 shutdown（1 秒）、exit、有界等待和必要的强制终止；初始化中实例也必须能清理。外部语言服务器需要预先配置，不自动安装。

## 同步 task 与异步通知

task 接受 subagent_type、prompt、可选 max_turns、可选 thread_id。模型调用固定使用内部 `async=false`：持久接受对应 Join 后，工具执行立即退出并将原 ToolInvocation 写为 `WAITING_CHILD`，释放 Worker 与 Work 租约；模型仍在原工具批次等待该调用的最终 tool_result，不会收到提前受理回执。Join 冻结后父 TOOL Work 从同一持久 receipt 重建 ToolResult，父 Thread 再按普通批次流程继续。并行工具批次中的 task 可同时运行。

子代理运行中状态通过工具进度显示，结果卡可进入对应子 Thread。并发额度已满时任务不会启动，tool_result 明确返回 `not started: the subagent concurrency limit is reached; retry in a later batch`。父 Thread 停止会按执行树规则停止子代理，并将父 task 工具结果持久化为取消。

`NOTIFY_PARENT` 与 `SUBAGENT_RESULT` 通知链属于 `AgentTaskService` 对内部 `async` 请求的交付方式；模型可见的 task 保持同步 tool_result 契约，结果经 `task.result` 返回，不走该通知路径。

任务参数、Thread 续接、`max_turns` 软预算与 Join 幂等行为如下：新任务创建子 Thread；thread_id 在原历史上继续，允许切换 Agent，采用目标配置和权限。继续由子 Thread 的持久父发起，以不可变父子关系验证身份，并复验父 head 与停止边界。向仍执行的子追加输入会排队，等下一次 turn 边界。max_turns 是阶段汇报软预算：达到后由 Runtime 以 `TASK_BUDGET` 通知注入提醒。

子 Thread 存在未完成的 `SETTLE_OWNER_TURN` 时拒绝新续接，须完成 owner 结算后才可继续；精确重放返回原票据。通过准入的忙时续接不打断当前模型或工具调用，也不覆盖其冻结请求；目标 settings 与新 prompt 一起入队，在后续 INPUT 边界消费。

同一父/子对至多有一个有效未完成 join：获准的新续接原子上位并 supersede 旧等待，旧 join 保留其 invocation identity 与排队源命令，不占额度、不产生独立交付。因此忙时追加多次 prompt 只会为最新一次 prompt 汇总一份结果，而不是每次委派各交付一次。

从历史边界 fork 的 Thread 是独立执行根：分支 fork 的 head 是一个只记录 `mode`/切点的新 `FORK` Entry，会话 fork 则把切点处的有效上下文复制到新 Session，两者都追加固定英文 FORK 通知（只陈述事实）。它不继承源 Thread 的待处理命令、子执行或 Join 订阅；原有子任务之后的回执仍交给原父 Thread，新分支不能通过旧 thread_id 接管原有子执行，已在分叉前进入共享历史的回执仍可见。

task 的进度与终态都经 `task.result` details 表达：运行中为 running，终态为 completed、failed 或 cancelled；details 固定为 `{kind, thread_id, status, session_id, subagent_type, replayed}`，正文是 Join 冻结的报告，失败与取消时为 `Subagent failed: ...` / `Subagent cancelled: ...`，有 partial result 时追加在正文末尾。

`NOTIFY_PARENT` 交付的 `SUBAGENT_RESULT` 通知是 `<subagent_result>` 信封（属性为 thread_id、agent 与 state），依次包含一段历史说明、本次任务原文、结果或分离的 error 与 partial_result；失败与取消时末尾还有继续既有子 Thread 的恢复提示。正文正确转义；模型的 `<error>` 详情最多 1000 个 Unicode 码点（含英文 ` [TRUNCATED]` 标记、转义前计数、不拆代理对），任务原文、成功报告与普通 partial_result 不截断；task 原文是历史引用不是给父的新指令。任务原文绑定本次调用，不使用首次创建时的任务。长文本沿用资源化，保留完整访问入口。

task 的最终 ToolResult 直接包含 Join 冻结的报告或错误，不另向父 Thread 注入 `SUBAGENT_RESULT`。Join 只有在子输入已应用、子本地流程结束、没有未完成直接子 Join、待处理输入或未确认退出时才冻结；停止路径也遵循同一收敛条件。终态冻结与父 TOOL Work 唤醒在同一事务提交。若 Join 在 ToolInvocation 写入 `WAITING_CHILD` 前已匹配，RUNNING 恢复会重读 Join 并直接结算；否则持久等待行由父 TOOL Work 重新接回，周期 Work 扫描兜底通知丢失和平台重启。父停止会将等待中的 task Invocation 收敛为 CANCELLED。

`AgentTaskService` 对内部 `async` 请求以 `NOTIFY_PARENT` 交付：冻结结果与父 `NOTIFICATION` 命令在同一事务提交，父为 `RUNNABLE` 时请求 THREAD wake，父为 `STOPPED` 时通知直接固化进历史。

### 持久义务与交付

Thread 的活动状态与执行控制正交：本地流程未结束、续作或 owner 压缩结算义务时为 `RUNNING`；没有上述活跃流程、但仍有已登记且未确认退出的执行阻塞收敛或待启动动作时为 `EXIT_PENDING`；本地已让出执行且等待直接 `NOTIFY_PARENT` 子 Join 异步结果或同步 task Join时为 `WAITING`，不空转模型或保留等待线程；无本地义务及直接异步结果为 `IDLE`。`EXIT_PENDING` 不等同空闲，不放宽 Join 或输入门禁。等待审批或人工输入仍属于本地流程，排队的设置维护命令不构成模型输入需求。状态与 Join 结算使用同一收敛判据，子任务自底向上逐层结算并通知父；单轮 `TURN_END` 本身不足以保证整次委派可交付。

内部以工具 invocation ID 标识本次委派，join 记录父子 Thread、源命令、冻结的终态/最终回答 Entry 与投递坐标；固定归属与每次执行身份分开。执行由 Session/Thread/Entry/Command/Work 的统一生命周期推进。

join 冻结与父通知交付在同一数据库事务内完成；子 Thread 终态已先物化时，最后一次退出确认可在其事务中补结算。接受与结果交付使用工具 invocation ID
作为稳定幂等身份；并发额度按尚未冻结终态且未被 supersede 的 `NOTIFY_PARENT` 与 `SETTLE_TOOL_RESULT` 子 Join 计，同一父/子对至多一个有效未完成 join，
root ticket 不占子任务名额。忙时继续仍需通过单父与全局额度准入，额度已满则明确拒绝。
Work 通知只降低延迟，周期 claim 扫描兜底进程重启和通知丢失；
父为 `STOPPED` 时通知只固化、不唤醒。

失败、取消有明确结果路径。未知副作用不能自动重跑；停止未确认不能当作已经安全停止。父停止向后代传播，迟到通知不能绕过停止门禁。父的执行恢复与产品收尾只按自身持久事实与 root Join 冻结回执判定，不按子树空闲反查业务门禁；底层 live/historical 分类只描述本地历史适用性。

## 失败与纠正指引

模型可见的失败统一为三段式英文文案：发生了什么、执行事实、下一步。执行事实只有三种，且必须与真实状态一致：

- 派发前校验拒绝（参数/schema、未知工具、权限、未绑定 Environment、路径或 `workdir` 不合法），以及命令启动前 Daemon 的进程监管或输出存储不可用，声明 `The tool was not executed.`；
- 已进入执行后的确定失败声明 `The tool ran but failed; side effects may have occurred.`；
- 超时、取消、提交不明或远程断开声明 `Whether the tool took effect cannot be confirmed.`，不得声称未执行；bash 的取消与超时仍保留真实的进程终态事实（`EXITED` / `TIMED_OUT` / `CANCELLED`）。

远端执行预算与传输恢复窗口分离：`request.timeout` 是 capability 的执行预算，只有显式指定 `0` 才表示无界；
`terminalDeliveryGrace` 只是 invoke 时冻结心跳超时得到的终态传输恢复窗，不是 terminate 后再等的时间。Platform 从发送前起
只等待一次 `request.timeout + terminalDeliveryGrace`，partial 与重连均不续期；timeout 为 `0` 时无界。恢复窗口内保留真实正常或
预算耗尽后的输出。缺终态到期返回 UNKNOWN，并移除在途记录、阻止重放、取消远端和释放 transfer，
不能解释为未执行或安全重试。真实远端 FAILED 在窗口内交付时仍按确定失败呈现，不降级为 UNKNOWN。

文案只陈述事实与恢复动作，不包含自动重试倾向；字段错误不回显含凭据的原始参数，异常路径只暴露安全描述而不是异常栈。未知工具列出当前冻结绑定的允许工具名并要求改用其中之一。路径与 `workdir` 校验在执行前完成并要求绝对路径（bash 的 `workdir` 必须绝对、存在且为目录）。`task` 的参数拒绝在文案中给出续用或新建所需的 `subagent_type`、`prompt` 与 `thread_id`，不打断既有 Thread。

## 实现与验证

技术实现和验证分别参考 [Daemon](daemon.md)、[Harness Runtime](harness-runtime.md#内置工具) 和 [开发与测试](../operations/development-and-testing.md)。
