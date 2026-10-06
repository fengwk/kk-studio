# Frontend 模块

[`frontend`](../../frontend) 是 React/Vite/TypeScript 浏览器工作台。
REST Snapshot 提供权威业务事实，WebSocket 提供对账提示和短暂显示增量；
Pane 布局、未发送输入、上传进度与编辑草稿由浏览器保存。
开发代理和发布打包分别见 [`vite.config.ts`](../../frontend/vite.config.ts)、
[`package.json`](../../frontend/package.json) 与 [Web](web.md)。

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
subscribed（含重连）、resync、资源 error 和版本提示触发回读。
Thread checkpoint 可在相同 version 内更新，因此同 version Snapshot 仍参与对账。
Canvas revision 提示严格大于本地值时才触发读取。

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

Debug 主区域包含下一次请求预览、事件与详情：宽 Pane 三列，窄 Pane 页签切换，
选择/关闭详情保留焦点返回路径。Tool、Skill、Subagent 和 Cache 检查器展示结构化事实。
诊断 GET 区分 NEXT_REQUEST_PREVIEW 与活动 FROZEN_INVOCATION，
包含发送/过滤工具、稳定 Skill 路径与冻结请求，排除 credential、认证 header 和 Base64 正文。

已有空闲 Thread 的预览按钮通过 Composer.preparePreview 冻结草稿和附件身份，
先读取最新 Snapshot，复验设置基线、空闲、队列和活动 Invocation，再用精确 cursor 请求
provider-request-preview。成功展示点击时协议 JSON（可能包含内联媒体）。
等待期间草稿或目标变更、组件卸载使迟到结果失效；请求单飞，409 使用 PREVIEW reason
白名单文案。预览保持草稿、上传与命令未消费状态，随后发送仍须独立接受。

## Chat 提交与控制

Chat 局部组件树以 `chat.id` 为 key，切换 Chat 时重建局部状态。
Thread 异步操作按 `(threadId, binding epoch)` 隔离；即使 A → B → A，旧绑定的迟到结果
也不能改写当前 UI 或清除新 pending 记录，原 Thread 的草稿持久化仍按自身身份处理。

Pane target 与布局独立：NEW_SESSION_DRAFT 携 root settings 创建 Session；
NEW_THREAD_DRAFT 从同 Session Entry fork；BOUND_THREAD 使用精确 head/sequence 提交。
显式选 Agent 同步其模型/变体并保留环境、YOLO、输入与附件；
已有执行根 Thread（含 Issue Agent 根）可调整 Agent，子任务 Thread 只读。
配置差异按 SET_AGENT → SET_MODEL → SET_ENVIRONMENT
前缀发送，YOLO 走独立控制入口。

命令批在发送前冻结 idempotency keys、payload、顺序和 cursor，连同本地草稿写入 pending storage；
写入失败中止发送。不确定网络结果保留 exact replay，锁定请求身份并提供相同请求重试或显式放弃。
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
Snapshot、Debug 和 usage，并在顶部提供返回执行根入口；身份未确认时不挂载控制 Hook。
根面板汇聚整棵执行树的审批和问卷，提交仍携带原始调用的 Thread 与 invocation 身份。
执行结果与任务回执则始终交给直接派发的父 Agent，不改为根订阅。

根面板自动查询执行树，在 Widget 区以单行节点展示 processing 后代及其必要祖先；
不重复根节点，不绘制无活跃后代的空壳。根本地空闲仍继续查询，整树 Stop 仍然可用。
树行、task ID 与系统回执链接通过 `ThreadLink` 在当前 pane 查看；
查看栈逐层返回，根层保持挂载但隐藏且 inert，草稿、上传和阅读位置不被重建。
修饰键与中键保留独立 Thread 地址的浏览器行为。刷新失败明确提示，不伪造最新树。
输入编排由 `ThreadComposer` 持有，DOM、光标、IME 与 Pill 由 `ComposerEditor` 隔离。
控制、投影和阅读规则见 [Thread 控制与展示](thread-control-and-presentation.md)。
工具卡片按各自 invocation 的结果判定终态：同批其他调用尚未物化 durable 结果时，
已完成调用仍显示其结果，等待审批的调用保持未决。结果配对使用
`assistantEntryId:callIndex`，相同 toolCallId 的历史结果不会占用当前调用。
终态结果优先于尚未结束时的 partial。Thread 处于等待审批时，
活动条显示“等待审批”，其余非空闲状态仍显示正在工作。
`/goal` 维护用户目标，进度按当前 goalId 展示；工具契约见
[Harness Builtin](harness-builtin.md#goal用户拥有的目标与-agent-进度声明)。

## Canvas 编辑与上传

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

## 功能入口

AI feature 维护 Catalog、Chat、Environment、MCP、Skill 和 Thread 工作区。
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
i18n 支持 zh-CN/en-US，浏览器偏好保存 locale，切换同步 document.lang。
ConflictPresenter 统一展示 409；modal/lightbox 优先消费快捷键。

Vitest/jsdom 测试与实现就近组织，Canvas 集成用例在 feature 的测试目录。
[`test-setup.ts`](../../frontend/src/test-setup.ts) 清理 localStorage、固定 locale、
提供浏览器几何与媒体 stub。测试覆盖 route/extension、严格 codec、草稿恢复、
命令队列、Snapshot/realtime、上传、控制与可访问交互。
构建、lint、coverage 和 E2E 的命令与分类见 [开发与测试](../operations/development-and-testing.md)。

上级：[系统设计](../system-design.md)。相关文档：[Share](share.md)、[Web](web.md)、
[Canvas Core](canvas-core.md)、[Project](project.md)。
