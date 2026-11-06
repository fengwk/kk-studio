# Frontend 模块

[`frontend`](../../frontend) 是 React/Vite/TypeScript 浏览器工作台。
REST Snapshot 提供权威业务事实，WebSocket 提供对账提示和短暂显示增量；
Pane 布局、未发送输入、上传进度与编辑草稿由浏览器保存。
开发代理和发布打包分别见 [`vite.config.ts`](../../frontend/vite.config.ts)、
[`package.json`](../../frontend/package.json) 与 [Web](web.md)。
页面密度、卡片、控件、状态与文案遵循下文[视觉与交互语言](#视觉与交互语言)。

## 组装与路由

[`main.tsx`](../../frontend/src/main.tsx) 在 StrictMode 下用 BrowserRouter 包裹
[`AppProviders`](../../frontend/src/app/providers.tsx)：
QueryClient → ApplicationContributions → BrowserPreferences → ApplicationEvent → IdePanel。
查询默认关闭自动 retry 与窗口聚焦刷新；共享 ApplicationEvent manager 管理订阅。

[`AppRouter`](../../frontend/src/app/router.tsx) 将 `/` 转至 `/chats`，`/interactions` 由应用层
[`InteractionsPage`](../../frontend/src/app/pages/InteractionsPage.tsx) 承载问卷与审批（Chat / Issue 来源可跳转，独立 Agent 任务只标明来源），
其余交给 [`WorkbenchShell`](../../frontend/src/platform/workbench/WorkbenchShell.tsx)。
AppShell 与 WorkbenchShell 的 `navItems` 必传，由应用组合根
[`navigation.ts`](../../frontend/src/app/navigation.ts) 组装；platform 不导入 feature。
PageContribution 的 navGroup/workspace 决定当前导航分组高亮和沉浸布局，
合法 Chat/Thread/Canvas 工作区隐藏 topbar。Escape 按阻塞弹层、菜单、编辑焦点的优先级处理。

一级特性按用户心智划分：[`chat`](../../frontend/src/features/chat)（Chat 工作区、Thread 运行时与编辑区）、
[`catalog`](../../frontend/src/features/catalog)（Agent、模型、Provider、Skill Package 与 MCP Server）、
[`environment`](../../frontend/src/features/environment)（执行环境与 IDE）、
[`extensions`](../../frontend/src/features/extensions)（插件配置）、canvas、projects 与 settings。
四个内置扩展提供页面：应用层的 [`ai-console`](../../frontend/src/app/ai-console) 把 chat、catalog、environment
的页面组装进同一个 AI 导航分组，Projects、Canvas、Settings 各自声明扩展。
i18n 文案按同一划分放在 [`catalogs`](../../frontend/src/shared/i18n/catalogs)，key 按业务领域维护固定前缀。
[`application-contributions.ts`](../../frontend/src/app/application-contributions.ts) 在应用组合边界
把扩展一次性展开为只读的 pages、dialogs、overlays 与按 id 索引的 toolRenderers；
重复 extension/contribution id 在组装时拒绝，运行时不动态注册、覆盖或订阅。
扩展是编译期受信任 React module，安装集合由应用组装确定。
tool renderer id 对应后端冻结 rendererKey，缺失时使用默认展示。

shared 提供通用协议与 UI，feature 拥有自己的 controller、query key、业务 mutation 和样式。
feature 之间只能依赖 [`eslint.config.js`](../../frontend/eslint.config.js) 允许清单中登记的入口；
新增跨 feature 依赖须先登记，否则 lint 失败。
Thread panel 消费 timeline presentation 类型，宿主负责 API、realtime 和控制器。

## API 与 wire 校验

[`client.ts`](../../frontend/src/shared/api/client.ts) 提供类型化 `request` 与 `url`，
统一注入 Accept-Language 并设置请求超时。成功响应直接读取资源，空体返回 undefined；
错误响应按 problem+json 解析，`ApiError` 保留 status、code、reason、detail 和 errors。
恢复逻辑按精确 reason 判断，未知 reason 使用安全提示。过渡期间 `apiClient` 是同一传输核心的外观。

HTTP 路由与传输类型由 Web 控制器签名与生产 HTTP Jackson mapper 生成到
[`generated/contracts.ts`](../../frontend/src/shared/api/generated/contracts.ts)：
字段、可空性与枚举值跟随 Java DTO，Java long 在响应中是十进制字符串，`Instant` 是 epoch 毫秒整数。
生成物不手改；漂移由 [`check.sh`](../../scripts/dev/verify/generated-contracts/check.sh)
（`mvn verify` 内执行同一测试）拦截，更新走同目录
[`write.sh`](../../scripts/dev/verify/generated-contracts/write.sh)。

[`contracts`](../../frontend/src/shared/api/contracts) 只保留生成契约无法表达的封闭取值与前端细化
（Harness 命令、上传、技能与 Canvas 资源/运行状态等）；Interaction、Plugin、Project 与 Canvas 的 HTTP 响应直接消费生成契约，在使用点做 `type` / `status` 分支。
浏览器本地存储与应用事件 WebSocket 不经生成契约约束，仍保留结构校验。
service 按职责归属：Chat、Environment、Extensions、Catalog、Settings 的接口客户端位于各自 feature；
`shared/api` 只保留 HTTP client、生成契约和 Harness/Agent/Storage/Interaction 平台客户端。
[Projects feature](../../frontend/src/features/projects) 使用生成的 HTTP 契约；
[Canvas 接口客户端](../../frontend/src/features/canvas/canvas-api.ts) 由 Canvas feature 持有。
公开 HTTP 路由事实源是生成的 `ApiRoutes`，JSON 字段规则见 [Share](share.md)。
Java long 游标保持字符串，版本比较使用长度与字典序，保留整数精度。

## Snapshot、realtime 与恢复

Thread/Canvas 先读取 Snapshot，再订阅 `/api/events`。
浏览器只有一个应用事件 WebSocket，连接与订阅由应用级 manager 按资源引用计数管理：
同一资源无论多少消费者只有一条 wire 订阅，重连后自动重发全部订阅，恢复在线或回到前台立即重试。
连接建立后服务端先发 `connected` 帧，manager 用它给出本次物理连接的 `eventNodeId` 与 `connectionId`；
每次重连都是新身份，需要绑定到连接的 IDE 视图必须按新身份重新登记，旧身份不续用。
资源只声明自身语义：thread 带 version 与 lossy realtime delta，canvas 带 revision，
projects/tree/interactions/environments 只发 changed 提示、不携带 cursor。
subscribed（含重连）、resync、资源 error 和版本提示触发回读；
Thread checkpoint 可在相同 version 内更新，因此同 version Snapshot 仍参与对账，
Canvas revision 提示严格大于本地值时才触发读取。畸形消息在 codec 层拒绝、不到达 listener；
没有 EventSource，也不用固定轮询兜底。

Thread Snapshot 只有一份 query 缓存，所有读取都经
[`thread-snapshot-cache.ts`](../../frontend/src/features/thread/runtime/thread-snapshot-cache.ts) 的同一 fetcher。
缓存始终是完整的 root-to-head 历史；只有末 Entry 确为当前 head 时才以它为 `afterEntryId` 请求增量。
响应到达时按当时的缓存合并：完整响应替换缓存，增量保留到 `baseEntryId` 的前缀再按 Entry id 幂等接上，
较旧 version 不覆盖较新缓存，空增量仍更新全部当前状态。增量的基线已不在缓存链上时显式再读一次完整历史，
增量永远不会被当作完整缓存写入。resync 与重连只重新执行同一查询，服务端对非祖先游标返回完整历史。
手动压缩可用性不在 Snapshot 内：根控制区只在命令面板显示时按需查询
`/api/threads/{threadId}/compaction-availability`，检查中与检查失败分别显示不可用原因。
它只影响命令提示，压缩提交仍由服务端按 `expectedVersion` 判断。

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

[`thread-events.ts`](../../frontend/src/features/thread/runtime/thread-events.ts) 将 Entry、活动
Invocation 与 attempt failure 投影为时间线记录。Thread 活动只有 `RUNNING`、`WAITING`、`IDLE`：
RUNNING 表示本地流程未结束（包括审批、问卷及环境阻塞），WAITING 仅表示本地已让出执行，
等待直接异步 system-reminder 结果，IDLE 表示本地已收敛。
`executionControl` 是正交的持久执行控制（`RUNNABLE` / `STOPPED`），停止不代表调用已物理退出。
工作条按真实 model/tool invocation 展示执行动画；阻塞、单纯运行中及等待异步结果只显示静态事实。
STOPPED 不遮盖尚在途的 model/tool。委派进度按执行树逐 Thread 展示，不由父 Thread 递归投影。
STOPPED Thread 的 Snapshot 另携带 `unconfirmedExecutions`；只有 `executionControl=STOPPED` 且列表非空时，
Thread 面板逐个列出 kind、attempt 与完整 executionId，RUNNABLE 的未知/活跃执行不混入该列表。
“放弃等待”按单个 executionId 与当前 `expectedVersion` 显式确认，不自动批量；确认文案必须说明它只停止
等待退出确认，不终止本地或远端执行，执行可能继续产生副作用，恢复也可能与其重叠。成功以服务端完整快照
刷新投影与执行树，命令可用性随快照更新；失败保留原事实并显示友好错误，409 只回读最新事实让用户重新选择，
绝不自动重试或自动恢复执行。该维护动作随投影在根和可查看的只读子代理 Thread 上都可用，其余写入口仍只属于根。

### Thread Debug

Debug 是工作区独占的临时单格网格视图：源 pane 全宽展开，保留原 layout，
其他原可见 pane 保持稳定挂载且置为 hidden 与 inert，输入控制区挂起，上传注册表保持挂载；
单顶栏左上角提供“关闭 Debug”，不导航 `/chats`，不渲染会话返回行并隐藏布局选择器；
退出时完整恢复原 layout、可见 pane、阅读滚动与焦点。主区域包含下一次请求预览、事件与详情：
宽 Pane 三列等宽，窄 Pane 页签切换，选择/关闭详情保留焦点返回路径；Escape 优先由内部检查器或
弹层消费，未消费时才触发外层退出 Debug。Tool、Skill、Subagent 和 Cache 检查器展示结构化事实。
诊断 POST 携带当前界面选中的 model 与可空 environmentName，复用服务端只读规划；
模型、环境与调用身份共同隔离查询，迟到响应不能覆盖当前选择，也不写回历史或触发执行。
响应区分 NEXT_REQUEST_PREVIEW 与活动 FROZEN_INVOCATION，包含发送/过滤工具、稳定 Skill 路径
与冻结请求，排除 credential、认证 header 和 Base64 正文。Subagent 详情展示自己的能力声明与
配置 JSON，不递归展开下级；缓存 NONE 表示无显式缓存控制，不排除 Provider 自动缓存。

运行中的 Thread 另给 FROZEN_INVOCATION：只读取当时已存在的活动 Invocation 的冻结请求，不新建、
复制或延长任何持久事实。事件详情可按需重建某个历史模型输出的请求：
它只接受模型输出 Entry，请求前缀严格截断在该输出的 parent，展示的是用当前 catalog 与配置重建的结果，
不是原始网络快照，压缩回合不支持重建。本地新建分支草稿进入 Debug 可只读检查已有事件与历史请求，
绝不为此预创建 Thread。

## Chat 提交与控制

Chat 局部组件树以 `chat.id` 为 key，切换 Chat 时重建局部状态。
Chat 新建/编辑设置默认 YOLO 与 Environment；新建默认为非 YOLO、未选择环境。
仅新空白 pane 继承这些默认值；已有 Thread、分支草稿与 fork 沿各自历史恢复设置，不被改写。
Thread 异步操作按 `(threadId, binding epoch)` 隔离；即使 A → B → A，旧绑定的迟到结果
也不能改写当前 UI 或清除新 pending 记录，原 Thread 的草稿持久化仍按自身身份处理。

Session 的完整 Entry 历史是一棵树，Thread 只是树上一条已命名的分支；pane 自身只保存
view 绑定（PaneTarget）与布局，不复制 Thread、draft 或执行状态。
Entry 列表按 keyset 分页传输；控制层在打开历史树或草稿历史时逐页排空并累积完整历史，
历史树与草稿路径的渲染、搜索与分叉交互仍以完整视图为准。
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

草稿复用按页排空后的完整 Session Entries，只沿 parentEntryId 显示 ROOT 到 startEntryId 的闭合祖先路径；
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
显式选 Agent 同步其 execution 并保留环境、YOLO、输入与附件；Builtin 的模型和默认变体由目录解析，
其他 Driver 的 settings 原样保留；
已有执行根 Thread（含 Issue Agent 根）可调整 Agent，子任务 Thread 只读。
配置差异按 SET_AGENT → SET_EXECUTION → SET_ENVIRONMENT
前缀发送。忙碌 Thread 的选择立即作为 standalone SET-only 批提交并显示“待生效”，空闲/未创建/停止保留本地草稿随下一条输入提交；YOLO 走独立控制入口。
执行根仍有正在运行的子代理时，环境菜单显示“切换对正在运行的子 Agent 同样生效”；
提示只说明生效范围，不改变切换与提交路径。顶栏面包屑显示当前 pane 的 Chat → Session → 分支身份；
根面板与草稿 pane 不渲染自身标题。工作区内只读子代理同样不渲染第二层标题栏或只读 badge，
其真实身份（agent 名称与 `modelName (variant)`；默认 main
不作为独立身份）与只读动作直接汇入工作区顶栏，并提供返回直属父 Agent 入口。
模型与变体组合统一使用括号格式，包括输入区底栏及其提示、设置变更记录、执行树和 Agent 卡片；
变体来自实际选择，不换算为 reasoningEffort。模型引用和单独选择模型的选项都使用全局逻辑
`modelName`，不拼接 `providerName`；名称本身可以包含斜杠。GROUP 选择不带组级 Variant。

对话按持久 ROOT、SETTINGS 与普通 TURN_START 的设置快照计算 Agent、模型及环境变更。
回合间的 SETTINGS 成为最新 head 时立即展示变化；后续相同快照不重复展示。
无效快照明确提示并中断差值基准，Debug 仍保留一条已识别的 SETTINGS 事件及完整原始数据。

`AgentPane` 持有根投影，各子代理查看层由 `ConnectedThreadView` 读取自己的投影。
`ThreadView` 与命令控制 Hook 只消费必填投影；命令批直接从该投影构建。

命令批在发送前冻结幂等键、payload 与顺序；设置 / Goal 批次还冻结 cursor。
`pending-request-store` 保存这些未决请求：容器创建按 owner/pane 隔离，既有 Thread 按 threadId 隔离，
与 IndexedDB 离线草稿分开保存。写入失败中止发送；未知结果保留相同请求供精确重试或显式放弃。
恢复与清理核对 request identity，避免常规路径覆盖其他 Pane 的记录；先读后写并非跨标签页原子操作。
放弃只清理本地记录，不撤销服务端可能已经接受的请求。
单条纯 USER_MESSAGE 不依赖 cursor，过期 cursor 不会被客户端重算或重试；设置 / Goal 批次仍以精确 cursor 提交，明确 409 保留草稿并交给既有控制面处理。

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
执行结果与任务回执始终交给直接派发的父 Agent，不走根订阅。
子线程只读视图在顶部显示“环境：<名称> · 跟随主对话”：环境名取该 Thread Snapshot 冻结的值，
即规划该回合时执行根已生效的环境；执行根此后切换环境不会改写既有快照，子线程下一回合才使用新环境。
执行根没有环境时显示“环境：未选择 · 跟随主对话”，执行根自身不显示该行。
执行根已选择环境时，[`SkillEnvironmentHint`](../../frontend/src/features/thread/runtime/SkillEnvironmentHint.tsx)
在输入区上方列出该环境拿不到当前版本的技能与原因：未安装、版本不一致、安装失败。
两类事实只读既有契约：Agent 的 `config.skills` 与 `GET /skill-packages/{name}/environments` 的
`installedSha256` / `error` / `upToDate`；判定与 system prompt 的技能路径同源，安装失败优先于
版本判断，缺少环境行按未安装处理。任一 Package 摘要尚未就绪或读取失败时不提示，不推断安装状态。
未选择环境时不出现提示，技能走 `vfs:` 路径。提示跟随执行根生效的 Agent 与环境草稿：
切换环境立即重算，环境的 `changed` 推送只回读安装摘要，安装完成后提示随之消失。
子代理观察面板从已有 Snapshot 展示 QUEUED CUSTOM_MESSAGE 的“待处理补充要求”，
保留命令 sequence、稳定身份和真实正文；独立于人工输入队列，不提供编辑、删除或重排。
应用后随 Snapshot 更新移出待处理区，历史只展示持久 Entry，不复制待处理记录。
task 初次执行入口只读取规范 `task.accepted` details 中的 thread_id：
持久 Entry 的 detailsJson 与 live invocation 的 resultJson.details 使用同一投影。
续接参数中已有的 thread_id 链接原样保留；缺失或损坏 details 时完整展示正文，
不从自然语言或正文 JSON 推断链接，错误原文仍保留。

全局与根交互区共用 InteractionCardBody/ApprovalCard。非文件变更工具的审批显示一次完整 `argumentsJson`（含 bash 的 workdir），
默认权限原因本地化，额外原因完整展示；允许与拒绝复用共享 Button，拒绝使用 primary + danger。
归属统一显示为“<类型> · <标题>”（Chat、Issue、Agent 任务）：类型与标题都来自会话归属 SPI 的 `owner`，
标题缺失时只显示类型，不逐卡获取 Snapshot，也不用来源 Thread 的 UUID 或名称冒充归属。有产品页面的归属
点击后携带执行根 `thread` 参数跳转；`AGENT_TASK` 没有产品页面，即使服务端给了 link 也只显示文本。
审批与问卷的写回仍使用原始 Thread 与 invocation 身份，来源导航不改变它。

### 文件变更审查与工具卡片

Chat 工具卡片与 ApprovalCard 共享文件变更审查组件：

- **write 审查**：审批前展示目标路径与完整文件正文；参数中显式携带工作目录时一并展示，不补默认目录。采用有界滚动，不截断首尾，严格保留原始缩进与空白。
- **edit 审查与真实 Diff**：模型流式生成参数阶段，展示传入的 `old_string` 与 `new_string`，明确标注为输入而非真实文件变更；
  参数流结束后进入准备状态（preparing spinner），等待后端预检生成 `approvalJson.editReview`；
  就绪后立即替换为真实文件 diff（包含 canonical 绝对路径、多 hunk 真实行号与变更行）。
  若缺失或无法解析有效 review，禁用“允许”按钮，保留“拒绝”。
- **展开与结果呈现**：write 与 edit 默认保持展开并有界滚动；执行成功后，可见结果仅呈现简洁的本地化提示
  （中文“写入成功”/“编辑成功”，英文“Write succeeded”/“Edit succeeded”），不在成功结果中重复回显文件正文或 diff；
  执行失败时不隐藏错误详情，也不在产生真实终态前虚假展示成功。
- **来源优先级与持久化结构**：优先从持久化的 `approvalJson.editReview` 获取真实 diff（freeze approval 优先）；对于自动放行（无需审批）、YOLO 模式或页面刷新与 GC 后的结果恢复，从持久化结果的 `details.editReview` 读取真实变更（durable entry 显式解码 `detailsJson`，invocation 从 `resultJson.details` 对象读取）；决不从旧/新参数在前端伪造 diff。

根面板自动查询执行树，在 Widget 区以单行节点展示非 IDLE 后代及其必要祖先；
RUNNING 与 WAITING 都计入活跃数，停止控制独立展示；非 IDLE 节点不展示 COMPLETED outcome。
不重复根节点，不绘制无活跃后代的空壳。根本地空闲仍继续查询，整树 Stop 仍然可用。
树行、task ID 与系统回执链接通过 `ThreadLink` 在当前 pane 查看；返回导航基于当前快照的
直属父 `parentThreadId`，通过 `navigation.openThread(parentId)` 定点打开父级，不依赖浏览栈
`goBack`，根层保持挂载但隐藏且 inert，草稿、上传和阅读位置不被重建。已绑定根通过 `/subagent` 命令、
只读顶栏通过“查看 subagent 执行”动作打开轻量执行树面板，展示包含嵌套与 resume 计数且排除根的完整
`historyRows`，未绑定草稿禁用该命令。
修饰键与中键保留独立 Thread 地址的浏览器行为。刷新失败明确提示，不伪造最新树。
输入编排由 `ThreadComposer` 持有，DOM、光标、IME 与 Pill 由 `ComposerEditor` 隔离。
工具卡片按各自 invocation 的结果判定终态：同批其他调用尚未物化 durable 结果时，
已完成调用仍显示其结果，等待审批的调用保持未决。结果配对使用
`assistantEntryId:callIndex`，相同 toolCallId 的历史结果不会占用当前调用。
终态结果优先于尚未结束时的 partial。工具名与参数属于同一 inline 文本流，保留换行、可选中复制，
长路径在剩余宽度自然折行，不横滚；状态与展开图标占不收缩的尾部。
工具状态直接保留 invocation 的 READY（排队或权威环境等待）、WAITING_APPROVAL、WAITING_INPUT、
RUNNING、SUCCEEDED、FAILED、CANCELLED 与 UNKNOWN。只有 RUNNING 使用 spinner；
没有 invocation 的模型调用草稿不表示已运行或成功。task 的成功只表示“委派已受理”，不保证目标验收。
Thread 处于等待审批时活动条显示“等待审批”；实际工具等待环境且没有其他执行中调用时显示真实环境等待，
不与工具行相矛盾，Footer 不重复解释等待原因。通用执行文案为中文“执行中”、英文“Working...”。
`/interactions` 待处理入口、Pane 卡片与角标读取相同 Invocation 事实：`WAITING_INPUT` 呈现冻结问卷卡片（单选/多选/自定义输入项，支持提交回答或明确拒答），`WAITING_APPROVAL` 呈现冻结调用的审批卡片（允许或拒绝）；审批和问卷回答均由用户显式提交。
`/goal` 维护用户目标，进度按当前 goalId 展示；工具契约见
[Harness Runtime](harness-runtime.md#内置工具)。

### 思考、系统通知与压缩卡片

思考框无 Header、标题或内部分隔线，展开/收起按钮位于第一行末尾：收起时合并为一行，按可用宽度保留最新尾部，左侧显示省略标记；展开时由 MarkdownRenderer 渲染；空思考不留空框。
工具静态正文从顶部阅读，只有仍在执行的 bash 流式输出默认跟随底部；用户上滚后暂停，回到底部恢复。工具、思考与压缩卡片通过冒泡的阅读意图通知外层暂停跟随；恢复必须来自外层真实滚动回到底部阈值内。外层只对新增记录与显式流式正文更新调度一次布局后贴底，手动展开、样式切换和图片加载不触发外层强制贴底。

`SUBAGENT_RESULT` 和 `TASK_BUDGET` 使用全宽淡色系统卡片，不留图标列缩进。子代理回执展示来源、Thread 链接和结果/错误/partial，信封分段且正文使用安全 Markdown。回执默认折叠，摘要顺序为 agent、真实状态图标、任务预览、执行链接、展开箭头。
完整成功的压缩在对话时间线留下“上下文已压缩”系统卡片，位于原压缩回合位置（此前对话之后、后续对话之前），默认折叠，展开后完整摘要在有界正文区域滚动。摘要只读取 `COMPACTION.payload.summaryText`，复用 `SystemMessageCard` 外壳与 `SystemMessageBody` 安全 Markdown，不支持的 XML 标签保留为普通文本。

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
attempt。UNKNOWN 展示人工核查入口，执行与 pin 契约见 [Canvas](canvas.md#function-run)。

上传共用 Worker SHA-256 → reserve → PENDING PUT 或 READY 去重 → complete → 命令消费。
每个 await 后校验 batch epoch；切换目标或卸载作废整批。已完成上传由服务端过期回收。
下载/渲染时签发短期 URL，客户端按返回的 URL 和安全 headers 访问。

## 用量与状态 Footer

Footer 的 token 与费用只取 Entry 的读取投影事实：费用只来自 `usageCost` 投影，绝不从 assistant
metadata 自行定价；durable 用量按当前 catalog 价格现算，不写回历史，模型已删除或无法解析时保留 null。
金额先用精确十进制求和，再统一四舍五入到最多 6 位小数并去掉尾随 0；精确值落在 (0, 0.000001)
时显示 `<$0.000001`，精确 0 才是 `$0`。上下文占用只使用最近一次模型调用的输入估计
（input + cacheRead + cacheWrite），绝不用分支累计量替代。
输出 `outputTokens` 已包含推理 token，测速仅用该输出量除以解码耗时；不额外累加推理。
缓存写入使用 `cacheWriteTokens` 总量，不叠加 TTL 分桶。
usage 必须报告四个非负安全整数：inputTokens、outputTokens、cacheReadTokens、cacheWriteTokens。
缺失或非法时不合成读数；reasoningTokens 和 providerTotalTokens 未报告时保留未知，聚合时也不补零。
assistantMetadata 缺失或为 null 的人工 ASSISTANT 消息不计用量；显式空对象、数组、非对象值或损坏 usage 时，
该回合及分支的完整计数、总费用传播为未知，不把后续已知项目伪装为完整总量。
安全整数累加溢出也保留未知；推理计数不得超过输出。合法 usage 仅缺推理计数时，
仍保留后端已提供的费用，不在前端重新定价。
摘要固定保留 `↑input · ↓output · RcacheRead · WcacheWrite · cost · cache N.NN% · X tok/s`
全部字段，R/W 为零时也不省略。未知计数、费用与缓存比例展示 `—`，没有测速样本时展示
`— tok/s`；真实 0 正常保留（如 `$0`、`0 tok/s`）。
缓存比例缺少计数或分母溢出时显示 `—`，只有已知输入总量为零时摘要显示 `0.00%`。
费用未计量时不假定币种；hover 仍明确区分未计量与真实零值，展示默认值不写入统计事实。
单行保留全部事实并由 CSS 省略，hover 给出环境、上下文与用量三组完整数字（含推理与缓存分项），
措辞统一为“未缓存输入 / 输出 / 推理 / 缓存命中率”等全称。组间使用 `∣` (U+2223)，统计项使用 `·` (U+00B7)，环境名无前缀。
累计口径覆盖全部 Entry，包括正常对话里不投影的压缩回合；它与对话内已关闭 TURN_END 的回合 footer 是两件事：回合 footer 在时间线展示本轮真实用量与“从此处分支”入口，无可用 usage 时只以 ASCII `-` 占位，只读子代理不显示分支入口。

## 功能入口

AI feature 维护 Catalog、Chat、Environment、MCP、Skill 和 Thread 工作区。
新库没有任何 Catalog 资源：Chat 列表在没有可用 Agent 时查询 Provider 与 Model，展示
Provider → Model → Agent → Chat 的首次使用引导并链接到对应页面，已有可用 Agent 时不再查询。
新建 Model 时可从已保存的 Provider 拉取上游模型 ID 作为 Model ID 候选，Name 默认跟随 Model ID，
上游失败时就地展示状态与原因。
Agent 目录按读 DTO 的 `type` 展示 `USER` / `BUILTIN` 徽标：内置定义的名称与删除受保护。
表单选择 execution Driver，当前提供 Builtin 的模型与 Variant 配置；内置定义允许未配置
execution，用户创建的 Agent 必须配置。无法编辑的其他 Driver 保留原始 settings，不静默改写。
Model 目录在同一列表中区分 `MODEL` 与 `GROUP`，二者共用全局逻辑名称空间；Group 表单按顺序维护
Fill First 成员，并编辑 `fallbackStatusCodes`（HTTP 400–599，可为空）与 `cooldownSeconds`
（非负，0 表示仅本次降级）。单模型表单编辑 `modelId`、limit、abilities、variants 与结构化
pricing；金额输入按十进制字符串提交，不引入 currency 或 unpriced 开关。pricing 编辑器按基础价格、
规则列表、费率字段与条件编辑器拆分，override 与 multiplier 共用同一规则列表和费率字段渲染。
Catalog 三个目录页共享 `CatalogRuntime` 上下文：页面控制器负责搜索、过滤与查询聚合；
资源控制器持有编辑器草稿、打开计划、校验、保存与删除确认；查询和写入分别由数据 Hook 承担。
全局弹窗贡献只从上下文读取控制器，不复制状态。
MCP Card 消费安全投影，编辑时读取 no-store 配置并用 generation fence 保护迟到响应。
Environment 管理面展示宿主事实与有界运维事件窗口，并提供“安装 / 覆盖”与“卸载”入口：安装弹窗
保存安装设置后签发一次性安装码，生成安装/卸载命令并复制到剪贴板；下载安装脚本时服务端轮换注册凭证，安装码随即失效；
卸载命令不需要安装码或注册凭证。前端不读取长期注册凭据，保存只写设置、不代表已部署。
管理面按 Environment 读取当前运行快照：没有本代 READY 快照时服务端返回 `environment_unavailable`，
前端展示“运行状态不可用”，不伪造空快照，也不据此判定已更新或未更新；更新进度只在快照里出现，
按已下载字节与可选总量展示，总量未知时只显示字节数。

### 技能包页面

`/skill-packages` 由
[`SkillPackagesPage`](../../frontend/src/features/catalog/skills/SkillPackagesPage.tsx)
提供，页面只消费生成契约，不写手写 decoder。

列表卡片展示包版本、来源与技能数量；Git 渠道另显示最近一次检查的结论，任一在线 Environment
安装失败时给出失败环境名。卡片只保留一个主按钮：观察到的分支 HEAD 与当前版本一致时是“检查更新”，
有更新时是“更新到 `<commit>`”，上传渠道则是“详情”。检查更新只刷新观察值，不改变当前版本；
发布以卡片展示的观察值为 `targetCommit`，并携带当前 `version` 做乐观锁。

新建弹窗在同一处选择 Git 仓库或上传压缩包。Git 渠道创建即发布当时解析出的分支 HEAD。
上传分两个阶段：先选择文件、填写包名与版本并“解析预览”，再在预览里确认后发布。校验阶段只呈现
不可量化的状态；直传阶段用 `XMLHttpRequest` 的上传事件展示真实进度；服务端规范化打包阶段同样只显示
阶段状态。预览展示服务端清单中的技能、按清单推导的文件树、被忽略的目录与变化摘要，摘要中的“仍被
Agent 引用的技能”只是提示，最终阻塞仍以服务端校验为准。发布使用暂存句柄与包的当前版本，新包不带
`expectedVersion`；取消预览会释放暂存归档。

详情弹窗展示概览、技能、服务端归档索引还原出的文件树、各 Environment 的安装状态与归档下载，
并承载编辑与删除。Git 渠道可以修改描述、分支与令牌，仓库地址只读；上传渠道只能修改描述，
带分支或令牌的请求由服务端拒绝。删除使用确认弹窗，包仍被 Agent 引用时展示后端 `errors.detail`
并保留包本身。

所有失败文案统一取后端 `errors.detail`；wire 上的 `Instant` 是 epoch 毫秒整数，展示时按本地时间格式化。
文案键集中在 `ai.skillPackages.*`，按钮与标题不使用括号补充说明。

### tab 全局 IDE 面板

IDE 是应用外壳内与页面并列的 tab 全局面板：[`IdePanelProvider`](../../frontend/src/features/environment/ide/IdePanelProvider.tsx)
负责 Context、顶层状态及持久分栏；内部职责明确拆分为纯 Claim 状态机（[`ide-claim-reducer`](../../frontend/src/features/environment/ide/ide-claim-reducer.ts)）、单点存储适配（[`ide-tab-state`](../../frontend/src/features/environment/ide/ide-tab-state.ts)）与连接身份对账（[`use-ide-claim-reconciliation`](../../frontend/src/features/environment/ide/use-ide-claim-reconciliation.ts)）。一个 Environment 在同一 tab 内最多一个视图。面板收起只隐藏，关闭视图才释放资源；
路由及环境切换不重建已打开的 iframe。桌面分隔线支持拖动和键盘调整并记住宽度，空间不足时 IDE 全屏；
Chat 的布局栏可切换已有面板，首次打开使用当前对话环境，未选择环境时禁用。
Chat 入口经 [`ide-panel-context`](../../frontend/src/features/environment/ide/ide-panel-context.ts)
请求面板聚焦已有视图，不预创建 iframe，也不在入口处声明目录。

视图绑定跟随事件通道的连接身份：前端先用 `connected` 帧的 `eventNodeId` 与 `connectionId` 登记
IDE 使用列表，再以同一身份 claim 视图；服务端核对 environment、view 与 claim 全部匹配才建立绑定。
迟到的成功来自旧连接时，视图保留原 `viewId` 并按 resume 重新绑定到当前连接。
入口 URL 必须是同源 bootstrap 路径（`/environment-ide/{environmentId}/{viewId}/__ide/bootstrap?token=...`），
前端逐项校验后用普通 iframe 嵌入；Studio 与受管环境属于同一可信浏览器边界，不要求独立域名。
本地开发环境由 Vite 的 `/environment-ide` 代理通过 `changeOrigin: false, ws: true` 保留 Host 与 Origin
并透传 WebSocket。

恢复身份只保存在 sessionStorage：`viewId`、`daemonInstanceId` 与目录；
epoch、claimId、iframe 与票据不持久化。localStorage 只保存最后展示意图（面板是否展开、当前选定与
已打开环境），供新 tab 继承同一 scope 的最后状态，不提供 `viewId` 或任何运行身份。
固定键前缀为 `kk-studio.tab-state:`（sessionStorage）与 `kk-studio.last-state:`（localStorage），后缀为 `chat:<chatId>` 或 `ide`，单 scope 文档限制 64 KiB 并严格校验。
副作用恢复记录（`PendingAcceptance`、`BoundPendingMessage`、Stop 重试身份）独立持有原始请求身份，不复制进 session/local 默认快照，不因初始化或切换重新提交操作。
页面触发 `pagehide` 时只撤回使用证明，不删除视图；bfcache 恢复后重新登记，视图由 Daemon 按租约回收。仅切换到后台或收起面板不撤回证明。

目录选择器是 [`Dialog`](../../frontend/src/shared/ui/overlays/Dialog.tsx) 内的惰性目录树
（[`OpenDirectoryModal`](../../frontend/src/features/environment/ide/OpenDirectoryModal.tsx)）：
打开时只读取宿主主目录的直接子目录，展开某个目录才请求它自己的下一层，收起即丢弃；
“上级目录”沿服务端返回的 `parentPath` 逐级向上，到根目录后禁用。确认后由 `CHANGE_DIRECTORY` 切换工作区，
只有切换成功才写入该 Environment 的最近目录。最近目录按 Environment 隔离保存在 localStorage
（`kk-studio.recent-directories:ide`，最多 8 条，最新在前），与展示意图共用同一存储适配，不提供视图身份。
换根、切换 Environment 与关闭弹窗都会推进请求代际并中止在途请求，迟到的目录响应不覆盖当前树；
宿主返回的权限或不存在等原因原样展示，超过上限的列表标注截断。移动端弹窗全屏，
行、展开、帮助与上级按钮的可点击区域不小于 44px；目录说明由可访问的 `?` 提示展开，不使用括号补说明。

视图被其它连接占用（`VIEW_IN_USE`）时，前端保留原 `viewId` 与目录，绝不静默替换成新视图：
面板只提供“重试恢复”（以新 claimId 恢复同一视图）与“打开新视图”两个人工动作，由用户明确选择。
其余不确定结果（视图消失、结果未知）同样保留恢复线索，替代视图必须由用户显式打开。

### 会话文件面板

对话顶栏的“文件”入口打开当前焦点 pane 所属 Session 的只读文件面板
（[`SessionFilesDialog`](../../frontend/src/features/chat/session-files/SessionFilesDialog.tsx)）。
没有 Session 的草稿 pane 禁用入口并提示先选择或创建会话；面板不提供上传、重命名或删除，编辑由 Agent 的文件工具完成。
前端只消费生成契约的三个端点：`GET /api/sessions/{sessionId}/files` 列直属条目、
`/files/content` 读取有界文本窗口、`/files/download` 下载完整字节，全部携带当前归属的 `ownerKind` 与 `ownerId`。
列表返回的条目路径是会话内相对路径，面板自行拼成 `vfs:/sessions/<sessionId>/...` 再请求子目录、预览或下载；
进入子目录只加载该层，上一级回到父目录。

文本预览按服务端 `read` 同源的窗口格式展示：解析 `range`、`truncated` 与 `next` 后逐页回看，
不把截断标记混入正文；图片按扩展名直接读取下载字节生成本地预览 URL。下载使用响应中的附件文件名，
不经过 JSON 信封。后端 404 统一提示“文件不存在或无权访问”，422 提示“此文件不能作为文本预览”并保留下载动作，
两种错误都单独展示后端原因，不用括号拼接。面板使用共享 `Dialog` 与 `modal-body`/`modal-footer`；
窄屏全屏覆盖、操作区不小于 44px，并处理安全区域。

Projects 使用权威 ProjectSnapshot/IssueDetail，按 workflow states 分列，
通过 version CAS 提交控制、阶段额度、Activity 与 Evidence。
Issue Agent Pane 复用 Harness 时间线；暂停原因只有 USER/ERROR，结果未知的停止以 ERROR 暂停与阶段状态分别展示。
Issue 详情在暂停时提供“继续”：USER 暂停直接提交，ERROR 暂停先展示暂停详情并提醒核查外部副作用；
确认期间 Issue 更新时须重新检查。继续成功后刷新 Issue；写操作通过侧车保留请求身份，
结果未知时锁定写入，支持用原版本和 requestKey 重试或显式放弃。
ProjectsInvalidationBridge 经 extension overlay 失效 Query，重连全量对账。
Issue 详情与绑定 Thread 以 URL query 为唯一事实源，深链和浏览器前进后退沿同一状态解析。
Project 编辑弹窗在打开时保存同项目的表单快照，后台更新不替换草稿；基础信息、YOLO 与工作流
三个子表单共用单一提交互斥与按子表单隔离的结果提示。版本基线仅在成功写入或显式 reload 后推进，
409 保留草稿与原版本。看板的编辑入口直接打开工作流页签，列表编辑默认打开基础信息。
工作流阶段编辑器独立为列表与属性表单组件，支持重排、转移边及人工/Agent 执行；
缺失 Agent 或非法额度不能保存。Projects 的删除确认复用共享 ConfirmActionModal，
复杂弹窗统一使用 Dialog 的 modal-body/modal-footer，并在窄屏通过 modal-touch-target
提供 44px 触区与安全区留白；Issue 详情的规格编辑与证据上传分别由独立 pane 与 hook 承担。
快照的 `referencedStateCodes` 汇总所有 Issue 的阶段与阻塞恢复点，
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
[`PluginsTab`](../../frontend/src/features/extensions/PluginsTab.tsx)，调用 `/api/plugins`。
卡片展示发行物已安装 StudioPlugin 的安全 descriptor、region、状态和刷新摘要。
认证 contract 当前为 DEEP_LINK：选 region、打开官方链接、粘贴 callback、提交后清空 password 输入、
重新读取安全状态。callback 不写浏览器 storage，同一请求保持单次提交。
generation fence 隔离关闭/重开的弹窗。
REFRESH_UNCERTAIN/REAUTH_REQUIRED 要求重新连接，Disconnect 二次确认。
新增认证形态须同步静态组件、Share/TypeScript contract、i18n 与测试；
后端 authKind:null 的管理形态需要先定义对应前端状态。

## 视觉与交互语言

前端是日常工作的工作台。设计首先让当前任务、真实状态和下一步操作容易找到，再以紧凑、克制的布局承载信息。资源列表以智能 AI 卡片为共同基准；业务不同，不应产生另一套按钮、表单、颜色和弹层。

### 从任务选择页面结构

资源列表采用“标题与搜索 → 新建卡和资源卡”，不添加营销横幅、重复的中英文标题或没有数据来源的预览图。卡片只呈现识别资源和作出下一步决定所需的信息，标识符与版本保留在诊断和并发协议，不占主视觉。

工作区采用单层内部头部：“返回入口 → 当前对象 → 必要操作”。看板、画布和对话不重复应用导航头。查看详情、调试或只读执行时，标题与操作反映当前可见对象，不借用隐藏对象的身份。加载失败仍须能返回入口。

编辑围绕用户能理解的字段和结果组织。普通用户无需编辑协议 JSON；阶段配置用阶段列表与字段表单表达。保存、取消、危险操作和错误恢复的位置稳定。背景更新不能改写输入或让未确认的草稿覆盖远端数据。

### 密度与层级

以 4px 为间距步进：同一控件内部 4–8px，关联字段/动作之间 8–12px，卡片和面板内边距 16px，页面区块之间 16–24px。避免在每页任意选择一套 padding，也不靠负 margin 或 transform 修齐标题。

- 正文沿用 13px、1.5 行高；辅助信息 12px，不能用大量灰色小字替代清晰结构。
- 卡片标题 15px、600 字重；页面标题 20–24px。每个区域只有一个主标题，不叠加空白标题栏。
- 普通控件高度 32px，紧凑控件 28px；图标动作点击区域至少 28px，图标通常 16px。紧凑不能牺牲焦点、点击与阅读。
- 卡片图标框沿用 AI 区域的 40px。资源卡不强制固定高度；同一栅格行自然拉齐，不用空白占位字段撑高。
- 资源栅格根据可用宽度自适应，间距 16px。窄窗口先减少列数，再让内容自然换行，不缩小文字或挤掉操作。
- 长标题可截断并提供完整名称；正文、工具参数、错误和任务报告可自然换行，展开后必须完整。

### Token 分层与门禁

样式 token 只有一个事实源：[`styles.css`](../../frontend/src/styles.css) 的 `:root`，分三层，组件只消费后两层。
功能布局样式跟随各自 feature（如 `features/thread` 的对话/调试、`features/chat` 的工作区、`features/settings`、
`features/thread/runtime/interactions` 的待办），`styles.css` 只保留 reset、通用控件与跨功能共享样式。

1. **Palette**（`--palette-*`）：原始色板，是唯一允许写色值的地方，位于 `@palette:start/end` 标记之间。
2. **语义层**：表面（`--bg` / `--surface` 系列 / `--stage-bg`）、文字（`--fg` / `--fg-muted`）、边框（`--border` / `--border-hover`）、强调与焦点（`--accent` 系列 / `--focus` / `--focus-ring`）、状态（`--success` / `--warning` / `--danger` / `--info`，各带 `-soft` 与 `-border`），以及半透明叠层（`--wash-1..3`、`--scrim`、`--surface-glass`）和阴影。
3. **组件层**：只在多处规则共享同一取值时才有，例如 `--topbar-bg`、`--stage-node`。

标尺同样是 token：间距 `--space-1..8`（4px 步进）、字号 `--font-size-xs/sm/md/lg/xl`（12 / 13 / 15 / 20 / 24px）、圆角 `--radius-*`、动效 `--duration-fast/base`、层级 `--z-raised/sticky/dropdown/modal/popover/toast`（局部堆叠只用 0–10 的整数）。字体 `--font` 与 `--mono`：标识符、id、版本号和数据值使用等宽，正文使用无衬线。

| 用途 | Token |
| --- | --- |
| 页面/图形舞台 | `--bg` / `--stage-bg` |
| 普通卡片/控件 | `--surface` |
| Hover/选中层 | `--surface-hover` / `--surface-active` |
| 弹层/抬升层 | `--surface-raised` / `--surface-overlay` |
| 正文/辅助文字 | `--fg` / `--fg-muted` |
| 边框/hover边框 | `--border` / `--border-hover` |
| 主动作/选择 | `--accent` / `--accent-soft` |
| 键盘焦点 | `--focus`（全局 `:focus-visible` 2px 轮廓）/ `--focus-ring` |
| 成功/等待/危险/信息 | `--success` / `--warning` / `--danger` / `--info` 及 soft、border |

没有同义别名，也没有 `var(--x, fallback)`：缺失的 token 应补进 `:root`，而不是在使用处兜底。
`npm run lint:css`（[`check-css.mjs`](../../frontend/scripts/check-css.mjs)，由 `styles.tokens.test.ts` 随 vitest 执行）扫描 `src` 下的样式与非测试 TS/TSX，拒绝：未声明的自定义属性、fallback、组件中直接使用 `--palette-*`、palette 之外的色值与命名色（TS 中只允许重复 palette 色值，供 Mermaid 这类只能吃具体颜色的 JS 消费者）、偏离字号标尺的 `font-size` 与 `font` 简写，以及大于 10 且不是 `--z-*` 的 `z-index`。

### 颜色与卡片

资源卡共享 AI 区域的低对比表面、细边框、12px 圆角、标题图标、元信息与底部动作。新建卡使用同一尺寸语言的虚线边框；没有图像预览事实时显示类型图标和真实元信息，不展示伪缩略图。
内容卡、设置、审批与回执共享表面和控件规范，但保留必要密度与状态。subagent 回执用轻量信息染色，不沿用黑底思考块。Canvas 节点和播放器保留图形、连接、媒体职责，不强套资源卡的底部动作结构。
绿色用于主动作、选中和真实成功，蓝色用于信息，橙色用于等待/提醒，红色用于危险/失败。状态同时使用文字或图标，不能只靠颜色。`READY`、完成和成功只能来自权威事实；已受理、等待与阶段报告不能伪装成目标验收。

### 控件、动作与反馈

普通页面复用 [`shared/ui`](../../frontend/src/shared/ui) 的 Button/IconButton、输入、Select、Switch、Checkbox、NumberInput、Tabs、Dialog、StateBlock/EmptyState、Toast 和复制控件；数据展示复用 Badge（状态语义）、Mono/Code（标识符与数据值）、KeyValue，区块容器复用 Panel 与 Toolbar。普通按钮不写原生 `<button>`；只有菜单项、选项、tab、下拉触发器和画布停靠按钮这类带专有交互的控件保留专门实现。共享组件负责外观与可访问性，feature 负责请求、业务校验、权限和文案。

Runtime 查看层、执行选择面板、历史树、改名和问卷的普通操作复用 Button，文本字段复用 TextInput/TextArea，纯图标动作复用 IconButton；局部样式只保留领域布局与必要密度。工作区使用唯一顶栏呈现当前查看身份，Debug 独占期间在左上提供“关闭 Debug”；子执行最左是“回到父 agent”，取当前执行的直接父身份，不同时显示“回到对话”。Debug 与 subagent 选择只从根输入区命令进入。

一个操作区只有一个突出的主动作。编辑、查看和取消采用次级或无框动作；删除、拒绝等明确使用 danger 语义，不能因外观统一而取消确认、在途禁用或并发围栏。多动作卡使用非交互外壳和独立操作，不嵌套按钮，不让编辑/删除触发进入或新建。
加载、空态和错误分别说明真实情况。失败保留输入和明确重试；空态给出下一步，不把失败当空数据。成功提示短暂且不重复；需要决定或恢复的错误留在相关操作处。正常数据由单连接推送失效与权威回读更新，不要求用户手动刷新。
弹层沿用同一 portal、焦点陷阱与焦点归还路径。Escape 自内向外只关闭顶层可关闭界面，尊重输入法和在途门禁；关闭不是停止、提交或清除草稿。隐藏 pane 和调试背后的输入不能接收焦点或快捷键。

### 文案与维护

用户操作围绕 thread、项目、Issue、画布、Agent 和环境，不暴露独立 branch 实体或内部绑定路径。名称、配置标识和用户正文原样保留；系统文案通过 i18n，中英文的语义、单位和占位符一致。不重复展示“中文标题 + 英文标题”，不以裸 UUID 代替可读名称。
按钮、标题和菜单只描述操作，不用括号重复英文名称或补充解释；会影响判断的说明使用统一的 `?` 帮助提示（键盘可展开、读屏可获取），链接标签与 aria 名称同样不写括号旁白。单位、范围、计数、快捷键和真实数据对（如 `名称 (状态)`、模型与变体）保留括号，其余解释性括号改为自然语句或帮助提示。
按钮描述结果，例如“创建并进入”“保存工作流”“返回项目”；错误说明当前阻塞与可采取的动作。诊断严格区分当前草稿预览、冻结调用输入与历史重建，不把重建内容称为实际 HTTP 原文。

## 共享 UI 与测试

三层 token（palette、语义、组件）在 [`styles.css`](../../frontend/src/styles.css) 的 `:root`，由 `npm run lint:css` 门禁（见上文[Token 分层与门禁](#token-分层与门禁)）；Canvas 样式由 feature 拥有。
[`shared/ui`](../../frontend/src/shared/ui) 只提供中性组件：controls（Button、IconButton、Checkbox、
Select、Switch、NumberInput、SearchField、TextInput、TextArea、TagInput、FieldLabel、Tabs）、cards（ResourceCard、
ResourceGrid）、overlays（Dialog、ConfirmActionModal）、feedback（Toast、StateBlock、EmptyState、CreateCard）、
data（Badge、Mono/Code、KeyValue）、layout（Panel、Toolbar），
以及 markdown 与 media 内容组件；不引入外部 UI 组件库，
生产代码禁用原生 `<select>`，下拉统一走自研 listbox。
[`Dialog`](../../frontend/src/shared/ui/overlays/Dialog.tsx) portal 到 body，提供焦点陷阱与关闭后
焦点归还、栈顶 Escape（含 IME 与 defaultPrevented 守卫）与遮罩点击关闭，pending 时禁止 Escape、
遮罩与关闭按钮。普通确认使用 `ConfirmActionModal`，复杂表单使用 `Dialog` 的
`modal-body` 与 `modal-footer`；Catalog 资源编辑与删除确认等已迁移的弹窗通过
`modal-touch-target` 在窄屏提供 44px 操作触区、
安全区留白和正文滚动，弹窗内 Select 的 portal 选项也遵循相同触区。
[`NumberInput`](../../frontend/src/shared/ui/controls/NumberInput.tsx) 是整数输入
（字符串值、自绘步进），十进制参数保留原生 `step=any` 输入语义。Escape 优先级由
[`blocking-overlay`](../../frontend/src/shared/ui/blocking-overlay.ts) 统一，弹层与菜单优先于 pane 输入。
i18n 支持 zh-CN/en-US，浏览器偏好保存 locale，切换同步 document.lang。
ConflictPresenter 统一展示 409；modal/lightbox 优先消费快捷键。

Vitest/jsdom 测试与实现就近组织，Canvas 集成用例在 feature 的测试目录。
[`test-setup.ts`](../../frontend/src/test-setup.ts) 清理 localStorage、固定 locale、
提供浏览器几何与媒体 stub。测试覆盖 route/extension、生成契约直接消费、草稿恢复、
命令队列、Snapshot/realtime、上传、控制与可访问交互。
构建、lint、coverage 和 E2E 的命令与分类见 [开发与测试](../operations/development-and-testing.md)。

上级：[系统设计](../system-design.md)。相关文档：[Share](share.md)、[Web](web.md)、
[Canvas](canvas.md)、[Project](project.md)。
