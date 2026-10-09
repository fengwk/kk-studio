# Frontend 模块

[`frontend`](../../frontend) 是 React/Vite/TypeScript 浏览器工作台。
REST Snapshot 提供权威业务事实，WebSocket 提供对账提示和短暂显示增量；
Pane 布局、未发送输入、上传进度与编辑草稿由浏览器保存。
开发代理和发布打包分别见 [`vite.config.ts`](../../frontend/vite.config.ts)、
[`package.json`](../../frontend/package.json) 与 [Web](web.md)。
页面密度、卡片、控件、状态与文案遵循[前端视觉与交互语言](frontend-design-language.md)。

## 组装与路由

[`main.tsx`](../../frontend/src/main.tsx) 在 StrictMode 下挂载
[`AppProviders`](../../frontend/src/app/providers.tsx)：
QueryClient → ExtensionHost → BrowserPreferences → ApplicationEvent → BrowserRouter。
查询默认关闭自动 retry 与窗口聚焦刷新；共享 ApplicationEvent manager 管理订阅。

[`AppRouter`](../../frontend/src/app/router.tsx) 将 `/` 转至 `/chats`，`/interactions` 由应用层
[`InteractionsPage`](../../frontend/src/app/pages/InteractionsPage.tsx) 承载问卷与审批，
其余交给 [`WorkbenchShell`](../../frontend/src/platform/workbench/WorkbenchShell.tsx)。
AppShell 与 WorkbenchShell 的 `navItems` 必传，由应用组合根
[`navigation.ts`](../../frontend/src/app/navigation.ts) 组装；platform 不导入 feature。
PageContribution 的 navGroup/workspace 决定当前导航分组高亮和沉浸布局，
合法 Chat/Thread/Canvas 工作区隐藏 topbar。Escape 按阻塞弹层、菜单、编辑焦点的优先级处理。

四个内置扩展提供 AI、Projects、Canvas、Settings 页面。
[`ExtensionHost`](../../frontend/src/platform/extensions/ExtensionHost.ts) 管理 pages、dialogs、
overlays、toolRenderers；同 contribution 按 priority 降序、注册顺序升序选择。
重复 extension id、非法 contribution id/path 在注册时拒绝。
扩展是编译期受信任 React module，安装集合由应用组装确定。
tool renderer id 对应后端冻结 rendererKey，缺失时使用默认展示。

shared 提供通用协议与 UI，feature 拥有自己的 controller、query key、业务 mutation 和样式。
Thread panel 消费 timeline presentation 类型，宿主负责 API、realtime 和控制器。

## API 与 wire 校验

[`client.ts`](../../frontend/src/shared/api/client.ts) 使用 `/api`、60 秒 timeout，
注入 Accept-Language 并解包 ResultEnvelope。ApiError 保留 status、code、errors；
恢复逻辑按精确 reason 判断，未知 reason 使用安全提示。

[`contracts`](../../frontend/src/shared/api/contracts) 与 service 按 Catalog、Chat、Harness、
Environment、MCP、Interaction、Plugin、Canvas、Storage、Settings 分组；
Project 类型和 codec 在 [Projects feature](../../frontend/src/features/projects) 就近维护。
HTTP 路由事实源见 [Web](web.md)，JSON 字段规则见 [Share](share.md)。
codec 校验 canonical UUID、十进字符串、枚举、可空性和嵌套结构；
Java long 游标保持字符串，版本比较使用长度与字典序，保留整数精度。

## Snapshot、realtime 与恢复

Thread/Canvas 先读取 Snapshot，再订阅 `/api/events/v1`。
浏览器只有一个应用事件 WebSocket，连接与订阅由应用级 manager 按资源引用计数管理：
同一资源无论多少消费者只有一条 wire 订阅，重连后自动重发全部订阅，恢复在线或回到前台立即重试。
资源只声明自身语义：thread 带 version 与 lossy realtime delta，canvas 带 revision，
projects/tree/interactions/environments 只发 changed 提示、不携带 cursor。
subscribed（含重连）、resync、资源 error 和版本提示触发回读；
Thread checkpoint 可在相同 version 内更新，因此同 version Snapshot 仍参与对账，
Canvas revision 提示严格大于本地值时才触发读取。畸形消息在 codec 层拒绝、不到达 listener；
没有 EventSource，也不用固定轮询兜底。

执行树按执行根订阅，服务端聚合后代写入后推送 changed，只回读该根，根本地空闲也保持订阅。
交互待办变化订阅全局 interactions；导航角标读服务端返回的真实 total，不用首页长度伪装计数。
环境注册表与连接租约订阅 environments：changed、subscribed 与 resync 都回读权威列表；
连接失效是时间事实而非写入事实，客户端只按最早的 `statusExpiresAt` 排一次回读（越过截止点一个短容差），
不轮询、也不维护本地过期集合，租约续期或任何 changed 更新数据后自动重排。

有 READY 环境工具的 Thread（包括只读后代）复用 interactions 订阅，按 parent/YOLO 投影确定真实执行根：
本根 changed、subscribed 与 resync 使当前 Snapshot 失效，同 version 的等待事实也会更新；
普通 lossy delta 仍只更新 overlay，不逐帧 GET。工具的 `requiredEnvironmentId`、`requiredEnvironmentName`、
`waitingForEnvironment` 与 `environmentWaitFreshnessAt` 来自 Work 冻结路由和服务端时间投影，
不从 composer 选择或环境聚合数推断单调用。Snapshot 最早边界与 interactions 的 `freshnessAt`
由 [`useReadModelFreshnessRecheck`](../../frontend/src/shared/lib/useReadModelFreshnessRecheck.ts) 安排单次回读：
同 QueryClient、查询 scope、截止点共用消费记录；续租、换绑与卸载注销旧 timer，
重复返回已消费的旧边界不会热循环，不同 scope 互不抑制。

MODEL_DELTA 按连续 sequence 累积；gap 启动单飞 Snapshot recovery，使用有界退避。
TOOL_PARTIAL 按 thread/invocation/attempt 内 eventId 精确去重，process.output 按 offset。
增量先归约进 refs，再按 animation frame 合并发布。
Invocation 终态和 attempt failure 建立围栏，迟到信号丢弃；
durable 结果到达后退出 transient overlay。

[`thread-events.ts`](../../frontend/src/features/ai/runtime/thread-events.ts) 将 Entry、活动
Invocation 与 attempt failure 投影为时间线记录。`processing` 等价于阶段仍在处理中
（`IDLE` 与 `STOPPED` 为 false），`executionControl` 是持久执行控制（`RUNNABLE` / `STOPPED`）；
两者只描述该 Thread 自身，委派进度按执行树逐 Thread 展示，不由父 Thread 递归投影。

### Thread Debug

Debug 是工作区独占的临时单格网格视图：源 pane 全宽展开，原 layout 保持不变，
其他原可见 pane 保持稳定挂载且置为 hidden 与 inert，输入控制区挂起，上传注册表保持挂载；
单顶栏左上角提供“关闭 Debug”，不导航 `/chats`，不渲染会话返回行并隐藏布局选择器；
退出时完整恢复原 layout、可见 pane、阅读滚动与焦点。主区域包含下一次请求预览、事件与详情：
宽 Pane 三列，窄 Pane 页签切换，选择/关闭详情保留焦点返回路径；Escape 优先由内部检查器或
弹层消费，未消费时才触发外层退出 Debug。Tool、Skill、Subagent 和 Cache 检查器展示结构化事实。
诊断 GET 区分 NEXT_REQUEST_PREVIEW 与活动 FROZEN_INVOCATION，
包含发送/过滤工具、稳定 Skill 路径与冻结请求，排除 credential、认证 header 和 Base64 正文。

下一次请求预览冻结草稿与附件身份，先读取最新 Snapshot，复验设置基线、空闲、队列与活动 Invocation，
再用精确 cursor 请求 provider-request-preview。等待期间草稿或目标变更、组件卸载使迟到结果失效；
请求单飞，409 使用 PREVIEW reason 白名单文案，预览不消费草稿、上传与命令，随后发送仍须独立接受。
运行中的 Thread 另给 FROZEN_INVOCATION：只读取当时已存在的活动 Invocation 的冻结请求，不新建、
复制或延长任何持久事实，现算预览被拒时冻结事实仍然成立。事件详情可按需重建某个历史模型输出的请求：
它只接受模型输出 Entry，请求前缀严格截断在该输出的 parent，展示的是用当前 catalog 与配置重建的结果，
不是原始网络快照，压缩回合不支持重建。
本地新建分支草稿的预览走 session 级 POST provider-request-preview，body 只带 `startEntryId` 与
有序命令（起点必须是 ROOT 或 TURN_END），只为预览检查协议 JSON，绝不为此预创建 Thread。

## Chat 提交与控制

Chat 局部组件树以 `chat.id` 为 key，切换 Chat 时重建局部状态。
Thread 异步操作按 `(threadId, binding epoch)` 隔离；即使 A → B → A，旧绑定的迟到结果
也不能改写当前 UI 或清除新 pending 记录，原 Thread 的草稿持久化仍按自身身份处理。

Session 的完整 Entry 历史是一棵树，Thread 只是树上一条已命名的分支；pane 自身只保存
view 绑定（PaneTarget）与布局，不复制 Thread、draft 或执行状态。
`/history` 打开当前 Session 的历史树：每个真实 Entry 恰好一行，按 Git commit lanes 画节点与连线，
线性链始终留在同一列，只有真实 sibling 分叉才开新列；只有 ROOT 与已关闭 TURN_END 是可手工
分叉的边界。搜索只调暗未命中行并高亮命中片段，不隐藏行、不改图形，也不写任何 Thread 状态。

命名分支有两个等价入口并汇入同一次命名与目标选择：`/history` 面板底部对选中行的“从此处分支”，
以及对话中已关闭 TURN_END 回合 footer 的分支按钮。弹窗要求一个按后端规则规范化的名称，
并选择目标位置 1..9；确认只把 `NEW_THREAD_DRAFT` 路由到目标 pane（隐藏位置先扩展布局显露、
焦点随之移动），名称是创建 target 的必需事实，此处不预创建 Thread。目标有在途操作时拒绝路由，
已有未发送草稿时要求二次确认覆盖，用户输入的本地草稿按 pane 作用域持久化。
面板底部另提供“从此处新建会话”（`FORK_SESSION_DRAFT`）：把选中 ROOT 或已关闭 TURN_END 处的有效上下文
复制到新 Session 并创建独立执行根，不走命名弹窗。
目标列表明确区分 thread 草稿与已持久化 Thread。隐藏位置的在途接受、重命名或队列
保持控制层挂载且惰性，直到结算才卸载；隐藏布局不解除 busy 门禁。
打开前按需读取 Session 的 Thread 摘要，只对执行根名称查重，同时检查同 Session 的本地草稿
（包含隐藏位置和待消费目标，排除当前目标自身）。名称按 Unicode White_Space 折叠为单空格、
去除首尾 ASCII 空格，最多 256 码点；大小写与兼容字符保持原值。读取失败留在弹窗中供重试，
关闭、修改名称/目标或来源/目标变化会作废旧检查，等待期间的新输入不能被旧返回覆盖。

草稿复用完整 Session Entries，只沿 parentEntryId 显示 ROOT 到 startEntryId 的闭合祖先路径；
设置回放、Conversation 与 Debug events 消费同一路径，不混入兄弟、后续消息或源 Thread 的
queued/live invocation。缺节点、循环、跨 Session 和非 ROOT 终点明确失败，提供加载重试，
并禁止首次发送与预览；从 ROOT 打开的草稿有明确空态。
首次发送才在一次原子命令批里创建该命名 Thread（`NEW_THREAD` = settings diff + `USER_MESSAGE`），
随后 pane 绑定到返回的 Thread，在权威 Snapshot 就绪前保留同一历史前缀，不重写历史。
`THREAD_NAME_CONFLICT` 409 保留起点、输入、附件和设置，打开既有名称面板供本地改名；
`/rename-thread` 也可修改此草稿名称，不预创建 Thread、不自动添加后缀。服务端仍裁决并发竞争。
草稿改名复用 workspace 的根名称与本地/pending 草稿检查，失败保留旧名和编辑输入；
关闭宿主或更换目标使迟到校验失效。
选择 Thread 或路由草稿记录一次目标身份的输入焦点意图，等可编辑且 pane 仍 focused、
未被只读层或 Debug 覆盖时复用 Composer 聚焦并把光标放在既有文字末尾；选择其他 pane
会作废迟到意图，后台 Snapshot 更新不会再次抢焦点。
已计划但尚未执行的 Composer 聚焦也在 pane 失去 workspace 焦点时取消。
pane 与布局的存储键分别以 owner 身份和 pane 隔离，
ISSUE_AGENT 与独立地址不落 Chat target 存储。

PaneTarget 有四种状态：`NEW_SESSION_DRAFT`（尚无 Session/Thread）、
`NEW_THREAD_DRAFT`（携带 `sessionId`、`startEntryId` 与已规范化 `threadName`）、
`FORK_SESSION_DRAFT`（携带 `sourceThreadId`、`startEntryId`，会话 fork 为新建的 `sessionId`/`threadId`）、
`BOUND_THREAD`（既有 Thread 用精确 head/sequence 提交）。
显式选 Agent 同步其模型/变体并保留环境、YOLO、输入与附件；
已有执行根 Thread（含 Issue Agent 根）可调整 Agent，子任务 Thread 只读。
配置差异按 SET_AGENT → SET_MODEL → SET_ENVIRONMENT
前缀发送。忙碌 Thread 的选择立即作为 standalone SET-only 批提交并显示“待生效”，空闲/未创建/停止保留本地草稿随下一条输入提交；YOLO 走独立控制入口。顶栏面包屑显示当前 pane 的 Chat → Session → 分支身份；
根面板与草稿 pane 不再渲染自身标题。工作区内只读子代理同样不渲染第二层标题栏或只读 badge，
其真实身份（agent 名称、provider/model，以及变体定义的真实 reasoningEffort；默认 main
不作为独立身份）与只读动作直接汇入工作区顶栏，并提供返回直属父 Agent 入口。

命令批在发送前冻结 idempotency keys、payload、顺序和 cursor，连同本地草稿写入 pending storage；
写入失败中止发送。容器创建用带 owner/target 的 `kk-studio.agent-pane-acceptance` 记录，
既有 Thread 的发送用只按 threadId 隔离的 `kk-studio.agent-thread-pending` 记录，互不伪造 owner。
不确定网络结果保留 exact replay，锁定请求身份并提供相同请求重试或显式放弃。
恢复时按 request identity 保护多 Pane 并发；放弃只清理本地记录，服务端可能已接受。
明确 409 保留草稿，只有同分支纯消息的 STALE_COMMAND_CURSOR 才有限更新 cursor 重试。

Stop 复用 stopRequestId 处理未知结果，覆盖当前 Thread 与完整后代。
`stoppedThreads` 保留逐 Thread 取消事实，根 Composer 与目标编辑区只恢复该根的
人工 `USER_MESSAGE` / `GOAL`，子代理不建立人工草稿；
`CUSTOM_MESSAGE`、`NOTIFICATION` 与配置命令不恢复草稿。
IndexedDB 在同一事务保存草稿与已合并的回执身份；响应和 snapshot 共用幂等恢复通道。
恢复 generation 拒绝跨标签页的陈旧覆盖写，失败明确提示并支持手动重试。
NOTIFICATION 使用系统样式展示，不进入人类消息队列、上下键历史或草稿。
审批复用 decisionId，操作者由服务端解析；切换 ALLOW/DENY 生成新身份。
`/threads/:threadId` 按执行身份分流：根 Thread 提供人工输入与控制，子代理只读取
Snapshot、Debug、用量并展示真实身份，并在顶部提供返回直属父 Agent 入口（直接导航到
`/threads/{parentId}`）；身份未确认时不挂载控制 Hook。
根面板汇聚整棵执行树的审批和问卷，提交仍携带原始调用的 Thread 与 invocation 身份。
执行结果与任务回执则始终交给直接派发的父 Agent，不改为根订阅。

全局与根交互区共用 InteractionCardBody/ApprovalCard。审批保留一次完整 `argumentsJson`（含 workdir），
默认权限原因本地化，额外原因完整展示；允许与拒绝复用共享 Button，拒绝使用 primary + danger。
来源名称来自 owner 或既有根执行树，不逐卡获取 Snapshot；无名称用“查看来源”，
后代入口用“查看 subagent 执行”，名称与跳转目标一致，不显示裸 UUID，也不改变审批/问卷的原始来源身份。

根面板自动查询执行树，在 Widget 区以单行节点展示 processing 后代及其必要祖先；
不重复根节点，不绘制无活跃后代的空壳。根本地空闲仍继续查询，整树 Stop 仍然可用。
树行、task ID 与系统回执链接通过 `ThreadLink` 在当前 pane 查看；返回导航基于当前快照的
直属父 `parentThreadId`，通过 `navigation.openThread(parentId)` 定点打开父级，不依赖浏览栈
`goBack`，根层保持挂载但隐藏且 inert，草稿、上传和阅读位置不被重建。已绑定根通过 `/subagent` 命令、
只读顶栏通过“查看 subagent 执行”动作打开轻量执行树面板，展示包含嵌套与 resume 计数且排除根的完整
`historyRows`，未绑定草稿禁用该命令。
修饰键与中键保留独立 Thread 地址的浏览器行为。刷新失败明确提示，不伪造最新树。
输入编排由 `ThreadComposer` 持有，DOM、光标、IME 与 Pill 由 `ComposerEditor` 隔离。
控制、投影和阅读规则见 [Thread 控制与展示](thread-control-and-presentation.md)。
工具卡片按各自 invocation 的结果判定终态：同批其他调用尚未物化 durable 结果时，
已完成调用仍显示其结果，等待审批的调用保持未决。结果配对使用
`assistantEntryId:callIndex`，相同 toolCallId 的历史结果不会占用当前调用。
终态结果优先于尚未结束时的 partial。工具名与参数属于同一 inline 文本流，保留换行、可选中复制，
长路径在剩余宽度自然折行，不横滚；状态与展开图标占不收缩的尾部。
工具状态直接保留 invocation 的 READY（排队或权威环境等待）、WAITING_APPROVAL、WAITING_INPUT、
DISPATCHING、RUNNING、SUCCEEDED、FAILED、CANCELLED 与 UNKNOWN。只有 RUNNING 使用 spinner；
没有 invocation 的模型调用草稿不表示已运行或成功。task 的成功只表示“委派已受理”，不保证目标验收。
Thread 处于等待审批时活动条显示“等待审批”；实际工具等待环境且没有其他执行中调用时显示真实环境等待，
不与工具行相矛盾，Footer 不重复解释等待原因。通用执行文案为中文“执行中”、英文“Working...”。
`/goal` 维护用户目标，进度按当前 goalId 展示；工具契约见
[Harness Builtin](harness-builtin.md#goal用户拥有的目标与-agent-进度声明)。

## Canvas 编辑与上传

画布库与其他资源列表共用紧凑资源卡。点击“创建新画布”后填写名称并确认才创建；
取消不会写入。创建期间不能重复提交，失败保留表单供修改重试。
已有画布的“进入”仅打开该画布，不创建新对象。

[`CanvasCommandQueue`](../../frontend/src/features/canvas/command-queue.ts) 串行提交冻结命令批，
冲突由每条命令的语义前置条件裁决。patch 连续且较新时应用，
空回执或 revision 缺口回读 Snapshot；语义 409 结算操作并保留草稿供人工恢复，
网络未知结果保留原 key/body 阻塞队列。
重命名的 `expectedName`/`expectedTitle` 基线在打开菜单时冻结，保存时不以最新远端值替换。

编辑 scope 按 userId/canvasId/editingSessionId 隔离，当前 userId 为固定 anonymous。
IndexedDB 草稿 ACK 物理完成后才删除持久 operation，草稿保存与 ACK 共用序列化链；
generation 防止旧响应清除新输入，卸载围栏保护底层存储。
“已保存”要求 command、draft 均无 pending 且无 storageError。
bfcache 恢复时复验 scope 所有权，活标签冲突触发安全 reload。

Function 配置使用扁平 args，资源引用为 `{type:'resource',nodeId,index}`。
复杂 prompt/references 使用完整 JSON 编辑模式；连线前 flush 目标 Function config。
Run 提交前冻结 requestId 与请求：4xx 确定拒绝清理记录，408/429、5xx 或网络断开保留原
attempt。UNKNOWN 展示人工核查入口，执行与 pin 契约见 [Canvas Infra](canvas-infra.md)。

上传共用 Worker SHA-256 → reserve → PENDING PUT 或 READY 去重 → complete → 命令消费。
每个 await 后校验 batch epoch；切换目标或卸载作废整批。已完成上传由服务端过期回收。
下载/渲染时签发短期 URL，客户端按返回的 URL 和安全 headers 访问。

## 用量与状态 Footer

Footer 的 token 与费用只取 Entry 的读取投影事实：费用只来自 `usageCost` 投影，绝不从 assistant
metadata 自行定价；durable 用量按当前 catalog 价格现算，不写回历史，无法定价时保留 null。
金额先用精确十进制求和，再统一四舍五入到最多 6 位小数并去掉尾随 0；精确值落在 (0, 0.000001)
时显示 `<$0.000001`，精确 0 才是 `$0`。上下文占用只使用最近一次模型调用的输入估计
（input + cacheRead + cacheWrite），绝不用分支累计量替代。
摘要固定保留 `↑input · ↓output · RcacheRead · WcacheWrite · cost · cache N% · X tok/s`
全部字段，R/W 为零时也不省略。缺失的上下文估计、费用、缓存比例与速率默认显示 `0`，
费用未计量时不假定币种；hover 仍明确区分未计量与真实零值，展示默认值不写入统计事实。
单行保留全部事实并由 CSS 省略，hover 给出环境、上下文与用量三组完整数字（含推理与缓存分项），
措辞统一为“未缓存输入 / 输出 / 推理 / 缓存命中率”等全称。累计口径覆盖全部 Entry，
包括正常对话里不投影的压缩回合；它与对话内已关闭 TURN_END 的回合 footer 是两件事。
展示规则见 [Thread 控制与展示](thread-control-and-presentation.md#状态-footer)。

## 功能入口

AI feature 维护 Catalog、Chat、Environment、MCP、Skill 和 Thread 工作区。
Agent 目录按读 DTO 的 `type` 展示 `USER` / `BUILTIN` 徽标：内置定义的名称与删除受保护，编辑表单允许其模型留空（未配置），用户创建的 Agent 仍要求模型。
MCP Card 消费安全投影，编辑时读取 no-store 配置并用 generation fence 保护迟到响应。
Skill Check 只更新候选，用户确认 exact commit 后 Update 发布。
Environment 管理面展示宿主事实与有界运维事件窗口，并提供“安装 / 覆盖”与“卸载”入口：安装弹窗
保存安装设置后读取当前 token，用服务端返回的配置生成含凭据的安装/卸载命令并复制到剪贴板，
保存只写设置、不代表已部署。

Projects 使用权威 ProjectSnapshot/IssueDetail，按 workflow states 分列，
通过 version CAS 提交控制、阶段额度、Activity 与 Evidence。
Issue Agent Pane 复用 Harness 时间线；UNKNOWN 核查与阶段状态分别展示。
ProjectsInvalidationBridge 经 ExtensionHost overlay 失效 Query，重连全量对账。
Issue 详情与绑定 Thread 以 URL query 为唯一事实源，深链和浏览器前进后退沿同一状态解析。
Project 编辑弹窗在打开时保存同项目的表单快照，后台更新不替换草稿；保存与显式 reload
共用单一提交互斥。版本基线仅在成功写入或显式 reload 后推进，409 保留草稿与原版本。
看板的编辑入口直接打开工作流页签，列表编辑默认打开基础信息。工作流用阶段列表与表单配置，
支持重排、转移边及人工/Agent 执行；缺失 Agent 或非法额度不能保存。
快照的 `referencedStateCodes` 汇总所有 Issue（含归档）的阶段与阻塞恢复点，
因此可见列表为空也不能删除或改码这些阶段。项目级推送同时失效该项目下的 Issue 与 Evidence，
订阅与重连共用既有 WebSocket。

Settings 的 General 保存本地偏好；server tabs 由 settings schema 驱动，
完整聚合携 expectedVersion 提交。权限与 apply timing 按封闭类型渲染。

### 配置同步

“高级”之后的“同步”页签提供导入和导出，不使用系统设置的保存/重置按钮。
导出默认全选，支持逐项选择并展示自动包含的依赖；请求只提交用户直接选择的条目，
最终依赖由服务端按当前配置补齐。下载固定为 `kk-studio-config.yaml`，始终包含所需凭据。
导入读取 YAML 后先检查，成功才打开确认弹窗，分开展示将新增、将覆盖及将跳过的配置。
硬错误用错误提示拒绝；部分可导入时由用户明确选择“仅导入可用配置”，没有可用项则不提供执行按钮。
确认后提交原文件和部分导入授权，实际结果展示已导入及跳过原因，不渲染文件内容。
成功后刷新配置目录；已有未保存设置草稿时保留原样，由用户明确重新加载。
完整范围与恢复操作见[配置导入与导出](../operations/development-and-testing.md#配置导入与导出)。

### Plugin 设置

`/settings` 的静态 Plugins 页签挂载
[`PluginsTab`](../../frontend/src/features/ai/plugins/PluginsTab.tsx)，调用 `/api/plugins`。
卡片展示发行物已安装 StudioPlugin 的安全 descriptor、region、状态和刷新摘要。
认证 contract 当前为 DEEP_LINK：选 region、打开官方链接、粘贴 callback、提交后清空 password 输入、
重新读取安全状态。callback 不写浏览器 storage，同一请求保持单次提交。
generation fence 隔离关闭/重开的弹窗。
REFRESH_UNCERTAIN/REAUTH_REQUIRED 要求重新连接，Disconnect 二次确认。
新增认证形态须同步静态组件、Share/TypeScript contract、i18n 与测试；
后端 authKind:null 的管理形态需要先定义对应前端状态。

## 共享 UI 与测试

全局 token 在 [`styles.css`](../../frontend/src/styles.css)，Canvas 样式由 feature 拥有。
[`shared/ui`](../../frontend/src/shared/ui) 只提供中性组件：controls（Button、IconButton、Checkbox、
Select、NumberInput、SearchField、TextInput、TextArea、TagInput、FieldLabel、Tabs）、cards（ResourceCard、
ResourceGrid）、overlays（Dialog、ConfirmActionModal）、feedback（Toast、StateBlock、CreateCard），
以及 markdown 与 media 内容组件；不引入外部 UI 组件库，
生产代码禁用原生 `<select>`，下拉统一走自研 listbox。
[`Dialog`](../../frontend/src/shared/ui/overlays/Dialog.tsx) portal 到 body，提供焦点陷阱与关闭后
焦点归还、栈顶 Escape（含 IME 与 defaultPrevented 守卫）与遮罩点击关闭，pending 时禁止 Escape、
遮罩与关闭按钮。[`NumberInput`](../../frontend/src/shared/ui/controls/NumberInput.tsx) 是整数输入
（字符串值、自绘步进），十进制参数保留原生 `step=any` 输入语义。Escape 优先级由
[`blocking-overlay`](../../frontend/src/shared/ui/blocking-overlay.ts) 统一，弹层与菜单优先于 pane 输入。
i18n 支持 zh-CN/en-US，浏览器偏好保存 locale，切换同步 document.lang。
ConflictPresenter 统一展示 409；modal/lightbox 优先消费快捷键。

Vitest/jsdom 测试与实现就近组织，Canvas 集成用例在 feature 的测试目录。
[`test-setup.ts`](../../frontend/src/test-setup.ts) 清理 localStorage、固定 locale、
提供浏览器几何与媒体 stub。测试覆盖 route/extension、严格 codec、草稿恢复、
命令队列、Snapshot/realtime、上传、控制与可访问交互。
构建、lint、coverage 和 E2E 的命令与分类见 [开发与测试](../operations/development-and-testing.md)。

上级：[系统设计](../system-design.md)。相关文档：[Share](share.md)、[Web](web.md)、
[Canvas Core](canvas-core.md)、[Project](project.md)。
