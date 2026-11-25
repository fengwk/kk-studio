# KK Studio 1.3.0 · 重构范围与交付

**重建工作区、资源归属和浏览器登录；保留 Thread、Canvas、IDE 和 Harness 的成熟实现。** 1.3.0 从空库初始化，以新契约替换旧入口。下文的旧实现和测试链接用于核对迁移范围；目标规则见[产品设计与模型映射](workspace-design.md)、[交互与底层链路](workspace-interactions.md)和[视觉语言与组件体系](workspace-ui.md)。

## 彻底重建 {#rebuild}

| 范围 | 替换交付 |
| --- | --- |
| **工作区外壳** | 新路由、Chat Space 切换、目录／状态／定时、统一资源打开、二叉窗格树、目标选择、草稿绑定、深链与布局持久化。Thread、Canvas、VS Code 以各自的 renderer 接入同一套窗格。 |
| **Chat 业务与所有权** | 新工作区应用接管空间、目录、Session/Canvas 归属和资源增删改，替代旧 Chat 协议和所有权派发。资源创建与归属登记同事务提交。Workspace 负责产品归属；Harness、Canvas 和 IDE 保有各自运行职责。 |
| **用户与浏览器会话** | 用户、登录记录、管理员用户管理、Cookie 会话、Origin 校验与资源授权。HTTP 与 WebSocket 复用资源访问规则；IDE 视图归属同时绑定用户与浏览器会话。密码哈希使用 `spring-security-crypto`。 |
| **定时任务** | 新建任务配置、触发、执行记录和前端交互，复用 Durable Work 的领取与租约。任务通过工作区入口创建、分支或继续 Thread；实际执行交给 Harness。 |
| **数据库初始化与接口入口** | 重建单一 [V1](../schema/src/main/resources/db/migration/V1__schema.sql)，折叠 V2–V4 的生效结构，纳入用户、登录记录、五张工作区表和上传者。新增 `/api/chat-spaces`、`/api/auth`、`/api/users`；已有 Thread 执行、Canvas 图操作和 IDE 继续使用各自的接口，按新归属授权。 |

## 保留并调整 {#retain}

| 模块 | 保留 | 调整范围 |
| --- | --- | --- |
| **Thread 面板** | [ThreadPanel](../frontend/src/features/thread/runtime/thread-panel/ThreadPanel.tsx)、[根输入控件](../frontend/src/features/thread/runtime/panes/RootThreadControlArea.tsx)、Composer、Timeline、状态栏、消息块、审批与问卷卡片。 | 由 [Thread 与新会话 renderer](../frontend/src/features/thread/workspace/index.ts) 承接目标、创建、分支和改名接线。保留请求幂等、队列、输入挂起、草稿、上传及只读子代理。模型、变体、执行环境和权限继续按现有执行边界调整。 |
| **Thread 读取与实时** | [快照缓存](../frontend/src/features/thread/runtime/state/thread-snapshot-cache.ts)、增量合并、流式对账、断线恢复，以及[执行树的 HTTP 引导与 WebSocket 回读](../frontend/src/features/thread/runtime/queries/useActiveThreadTree.ts)。 | 窗格绑定提供 Thread ID，缓存与订阅随登录上下文隔离。HTTP 与 WebSocket 共用公开执行树查询；Harness 批量读取各执行根的事实，工作区计算目录和状态视图的分组。 |
| **IDE 前端** | [视图对账](../frontend/src/features/environment/ide/useIdeClaimReconciliation.ts)、浏览器标签页会话和恢复、使用凭据、终端、附件打开与[关闭命令](../frontend/src/features/environment/ide/useIdeCloseCommands.ts)。 | [VS Code 窗格](../frontend/src/features/environment/ide/VsCodePaneRenderer.tsx)处理原生动作、绑定校验、状态和强制关闭确认。[侧栏环境列表](../frontend/src/features/environment/ide/EnvironmentList.tsx)打开工作台。`/files` 和 `/shell` 用 Thread 生效的环境走统一打开流程；`/shell` 等视图就绪后执行原终端动作。 |
| **IDE 服务端与 Daemon** | [IDE 控制面、数据面、跨节点路由和回收](modules/ide.md)、Daemon 连接与 code-server 桥接。 | 补用户绑定及访问校验，保留 Studio 到 Daemon 的视图控制协议。托管运行时升级单独验收发行件；技能安装锁单独修复。 |
| **Canvas** | [CanvasStage](../frontend/src/features/canvas/CanvasStage.tsx)、节点编辑、Function 运行、revision 与 patch。 | 把独立页面（原 `CanvasPage`）改为[窗格入口](../frontend/src/features/canvas/CanvasWorkbench.tsx)。按 Canvas ID 共用文档、命令与回读；把[按文档保存的视口](../frontend/src/features/canvas/useCanvasViewportPersistence.ts)改为按窗格保存，选中项、缩放和平移互不影响。补归属、整份删除及附件授权。 |
| **Harness** | Session／Entry 历史、Command、Work、模型与工具调用、审批、Join、停止及退出确认；保留现有模块边界。 | 增加按回合切点创建空闲分支、单 Thread 深删除和可达历史回收、执行事实批量读取；修复物理事务锁序。移除 `SET_AGENT` 和 `CONTROLLER_TASK`，保留 `PLUGIN_TASK`。 |
| **模型与工具** | [Provider 协议](modules/harness-model.md)、SSE、用量计价、缓存身份、回合内冻结请求、真实重试，以及[内置工具、技能与异步委派](modules/builtin-tools.md)的执行行为。 | Anthropic 的协议能力改由适配器决定；先验证实际 wire 行为与回退，再移除可编辑字段。托管文件的工具访问接入资源授权，Daemon 文件、LSP、MCP 和插件执行机制保持。 |
| **Catalog、设置与共享 UI** | Agent、模型、提供商、技能包、MCP、Environment、插件、系统设置 schema、配置同步与共享控件。 | 更新设置导航和权限、Agent／模型表单分段、按钮和焦点规则。新增头像及日期、时间控件。保留校验、未保存确认、同步预检与配置冲突处理。 |
| **Web 与存储** | Spring Boot 装配、`/ws/events` 传输、上传和 Blob 生命周期。 | 更新 SPA 路由、登录及订阅授权，加入 `chat-space`；开发代理保留浏览器 Host 和 Origin。登录上传记录 userId；Session/Canvas 按持有边签发取址，保留受控 Session 代理流；Canvas BLOB 输入用上传者校验的 `uploadId`。 |

**VS Code 视图按 `viewId` 固定 iframe 宿主。** 窗格切换、交换、全屏和隐藏只改占位与可见性，保持 iframe 在同一父节点。刷新和重连按同一个视图 ID 对账。关闭或替换窗格里的工作台时，沿用保存／不保存／取消及确认后的强制关闭；取消时不改变绑定或布局。原生事件核对来源 iframe、Origin 和视图绑定，目录、终端和附件动作不取其它窗格的环境。恢复描述继续按浏览器标签页保存；旧浮层宽度和标签选择随旧壳退出。

**原生新窗口新增独立工作台。** 侧栏等常规入口重复打开同一环境时复用已有工作台；托管 VS Code 的 `new-window` 显式创建第二个独立 `viewId`，原工作台和未保存内容不变。新视图放进第一个空的「新会话」窗格；没有空窗格时拆分当前窗格放置；已有 8 个窗格时提示先关闭一个窗格，不创建视图。

## 原样保留 {#unchanged}

- **执行机制**：Harness 回合推进、模型和工具调用、Work 唤醒／领取／续租、异步 Join、Stop 和退出确认继续按各自的现有状态机工作。新增切点和删除操作调用这些事实，不另建一套执行状态。
- **协议与工具**：Provider SSE 解析、用量与定价、重试分类、Daemon 通信、LSP、MCP 和 Plugin 载入不因工作区重建而改写。协议能力判定、文件授权和依赖升级的调整范围见上文。

## 删除 {#remove}

| 范围 | 退出内容 |
| --- | --- |
| **Project / Issue** | 后端、前端、7 张表、`CONTROLLER_TASK`、独有的 API、文案、E2E 和模块文档。 |
| **旧 Chat** | 旧 Chat 后端、`chat_session`、`/api/chat/chats` 与旧模块文档已退役。当前空间和资源归属由 Workspace 的 `chat_space`、`workspace_folder`、`workspace_resource` 与 `/api/chat-spaces` 承担；平台授权使用 `ResourceOwnership` 与 `ResourceAccess`。 |
| **被替代的 UI** | 独立 Entry 历史树、旧会话／Thread 选择器、`/agent` 换 Agent 入口、全局待处理页（原 `InteractionsPage`）、画布库、Studio IDE 浮层及外层标签。保留的行为迁入新宿主后，再删除专用组件、样式、存储和测试。 |
| **被替代的接口** | 通用的 `POST /api/harness/command-batches`、Session 查询/改名和 Thread 改名入口、无归属的画布创建／列表接口及 `projects` 实时资源已移除；资源创建和管理改由 `/api/chat-spaces/{spaceId}` 下的入口承担。 |
| **专用辅助件** | 旧 SelectionPanel 与 AgentSelectionPanel、AgentSelect、选择项预览、Markdown 预览压平与命中高亮随旧入口退出；新目录只筛选名称。 |

- **Agent 选择迁到空间默认配置。** 保留可用 Agent 列表及加载、失败和失效时的校验，控件统一用共享 [Select](../frontend/src/shared/ui/controls/Select.tsx)。已有 Session 固定 Agent；草稿不另设换 Agent 面板。
- **保留执行树和历史事实。** 删除的是独立 Entry 历史树及旧分支草稿的全树预览。正文 Timeline、切点校验、历史复制和父子 Thread 执行树继续使用实际数据；新分支由服务端按 `TURN_END` 创建。全局待处理页删除后，Thread 内审批和问卷仍走原交互查询与执行入口。

## 保留行为与验收 {#regression}

| 行为 | 守护测试 |
| --- | --- |
| **Thread 面板**：草稿、上传和输入焦点随绑定保持；排队、模型失败及重试取自真实状态。 | 保留 [Composer 测试](../frontend/src/features/thread/runtime/thread-panel/ThreadComposer.test.tsx)和[交互验收测试](../frontend/src/features/thread/runtime/interactions/interactions-acceptance.test.tsx)。用新窗格验证首条消息、运行中排队、草稿保留和断线恢复。 |
| **回合切点**：只在真实 `TURN_END` 显示分支操作；取消名称确认没有写入。 | 延续[回合页脚测试](../frontend/src/features/thread/runtime/thread-panel/messages/MetaMessageBlock.test.tsx)的切点断言，验证两个操作各自的结果在原窗格打开。 |
| **执行树**：重开时经 HTTP 引导，后续由 WebSocket 更新；两者可见节点一致。 | 保留[传输测试](../frontend/src/features/thread/runtime/queries/useActiveThreadTree.transport.test.tsx)。服务端补内部压缩节点过滤和订阅准入测试，在 `subscribed` 前拒绝不可公开目标。 |
| **IDE 生命周期**：切换、全屏、拖拽交换和移入另一个窗格都不重载 iframe；刷新、重连恢复原 `viewId`。 | [窗格浏览器测试](../frontend/browser-tests/ide-pane.pw.ts)覆盖布局切换的 DOM 身份、原生 New Window、普通打开复用、连接变化与刷新对账。 |
| **IDE 操作**：目录、终端、附件、关闭和失败提示归属实际视图。 | [关闭命令测试](../frontend/src/features/environment/ide/useIdeCloseCommands.test.tsx)验证视图绑定和错误分类；[窗格浏览器测试](../frontend/browser-tests/ide-pane.pw.ts)验证退出取消、丢弃和强制关闭。 |
| **Canvas**：两个窗格共享更新，视角和选中状态各自独立。 | 保留[实体 patch](../frontend/src/features/canvas/__tests__/entity-patch.test.ts)和[实时版本测试](../frontend/src/features/canvas/__tests__/canvas-version-events.test.tsx)，补同文档双窗格编辑与视角恢复。 |
| **Harness**：交互写入只锁目标 Thread；压缩后重试仍有上限；历史复制保留完整压缩事实。 | 保留 [InteractionServiceTest](../platform/runtime/src/test/java/fun/fengwk/kkstudio/platform/interaction/InteractionServiceTest.java)和压缩重试测试，补审批与 Stop 并发、压缩后复制、清理后可达历史回归。 |
| **设置、SPA 与测试环境**：未保存确认、缓存策略和隔离目录继续有效。 | 保留 Catalog／MCP 编辑器退出测试、`SpaFallbackTest#shouldRevalidateTheSpaShell` 和 E2E 启动清理测试。空间默认 Agent 的选择验证键盘操作、焦点返回及目录失败和失效值。 |

## Bugfix 与工程调整 {#bugfix}

| 问题 | 处理与验收 |
| --- | --- |
| [锁序记账](../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/store/PostgresqlHarnessStore.java)随 Store 回调重新创建（JPH-3）。 | [绑定到物理事务](workspace-design.md#回合切点与删除)。用 PostgreSQL 验证同一物理事务跨回调逆序时，在第二次取锁的 SQL 发出前失败；正序和已持有锁复用正常。 |
| [HTTP](../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/http/HarnessThreadController.java) 与 [WebSocket](../web/src/main/java/fun/fengwk/kkstudio/web/events/ApplicationEventConfiguration.java)分别过滤公开执行树。 | 在 Platform 共用目标解析、授权和过滤。不可公开目标在 HTTP 返回 404、在 WebSocket 的订阅准入拒绝；建立订阅后的实际读取错误仍报告快照失败。Harness 保留供执行操作使用的完整树。 |
| [技能安装](../daemon/internal/skill/installer.go)的锁表只增加。 | 复用[引用计数锁](../daemon/internal/coding/keylock/keylock.go)。同一包版本仍串行，持有者和等待者都离开后回收锁项。 |
| 保留的界面存在窄屏、样式和文案问题。 | 390px 核对 Composer 的环境与变体选择器；名称保留全称、以 CSS 省略；环境事件按代码本地化；空画布只保留一个添加入口。按[样式检查](workspace-ui.md#样式检查)收敛间距、描边和焦点。 |
| 版本信息、构建输入和测试门禁。 | 构建时注入 Studio 版本；[本地栈](../deploy/local/compose.yaml)、[分布式栈](../deploy/distributed/compose.yaml)、[测试栈](../deploy/test/compose.yaml)按 digest 固定镜像；选定真实模型用例进入受控 CI。 |

**附件隔离必须覆盖上传、持有与取址的每个入口。** `/api/platform/storage/uploads/**` 供已登录用户使用；reserve 记录 principal 的 userId，complete/delete 和引用转换只允许该上传者。服务端的 ownerless staging 仅由可信内部调用路径消费，不是用户上传授权的替代方式。Canvas BLOB 写入接收 READY `uploadId`，服务端按当前 userId 锁定上传并登记 Blob 持有边；不能以客户端 Blob ID 直接建立引用。Session 与 Canvas 的下载/预览 URL 分别走其资源坐标下的 POST `download-url` / `preview-url`，同时验证资源归属和持有边；通用 Blob ID 取址删除。旧的 Session 资源代理流保留并按 Session 归属、持有边及 ACTIVE Blob 校验。工具读取、工具结果登记和消息引用也先校验来源权限，再消费或新增持有；已授权的复制和受信上传通过各自事务登记。[资源 URI 校验器](../common/src/main/java/fun/fengwk/kkstudio/common/resource/ResourceUriValidator.java)只描述格式，不授予资源权限。双用户用例用于验证直接引用和经工具路径都不能跨用户获得新的持有边；此处描述目标契约，不代表验证已通过。

视觉验收复查工具摘要、思考块、已答问卷、附件名称、表格和 Mermaid 复制、窄屏费用与速率、空状态和 Agent 卡片。未确认的候选以当前实现为准，确认后再修。

## 依赖升级

- **kchart**：将 [`@fengwk/kchart`](../frontend/package.json) 从 `0.2.0` 更新到 `0.2.3` 并同步[锁文件](../frontend/package-lock.json)。验收实际的 Mermaid 渲染和错误提示。
- **code-server**：托管 fork 合入包含 `v4.146.0` 的上游，验证补丁、原生动作、视图和终端。通过完整打包、发行件 smoke 与五平台无发布 CI 后发布；核对发行件来源、提交与 SHA-256，再更新 [Daemon 运行时清单](../daemon/runtime-manifest.json)。升级和技能安装锁修复均随匹配版本的 Daemon 发布。

## 实施顺序

1. **存储与授权。** 先完成空库 V1、账号与登录、资源归属和授权。用两个普通用户及管理员验收按资源隔离、Origin、撤销登录、最后一名管理员与工作区归属约束。账号换名保留资源归属；改密时按操作类型保留本次或撤销全部登录。首条消息与 Session、`main`、目录边原子提交。
2. **执行与资源。** 在同一授权边界内补物理事务锁序、统一树查询、批量执行事实、切点分支、深删除和回收。验收 Stop 并发、压缩切点、共享历史、复制附件及旧 Worker 失权；在工作区投影前确认运行中与等待并存时的显示优先级。
3. **接入窗格。** 将 Thread、Canvas、IDE 接入新壳。验证 1440、1024、390、320px 下的布局、统一打开、草稿和焦点，目检关键截图。新入口通过验收后，删除旧路由、旧壳、对应契约及专用组件。
4. **定时与回归。** 在隔离 PostgreSQL 验证双 Worker、过期跳过、夏令时、幂等、账号禁用竞态及执行记录分页。启用账号后任务保持暂停；删 Thread 不删执行记录。Project 用例退役；其余用例按新工作区契约更新，[HTTP 助手](../scripts/dev/verify/e2e/lib/http.mjs)与 WebSocket 带相应 Cookie 和 Origin。
5. **交付。** 更新[生成契约](../scripts/dev/verify/generated-contracts/write.sh)、[导航](README.md)及受影响的模块和[部署文档](operations/deployment.md)。从隔离新卷初始化，保留旧库备份并复核配置导入；首次部署在修改初始密码后再开放。正式开发使用独占 worktree，按授权集成。Daemon 核对与项目版本匹配的 CI、发行件和校验文件。
