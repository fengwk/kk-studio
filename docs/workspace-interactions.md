# KK Studio 1.3.0 · 交互与底层链路

每个用户动作只走一条路径。本文按动作说明：前端状态怎样变，调用哪个接口，服务端在哪个事务里改哪些数据，提交后发什么事件，失败时用户看到什么。表结构、字段和错误码定义见[资源工作区重构方案](workspace-design.md)；表中接口的相对路径以 `/api/chat-spaces/{spaceId}` 开头。

## 三类前端状态

| 状态 | 内容 | 保存在 |
| --- | --- | --- |
| 布局 | 窗格树、窗格绑定、聚焦窗格、目录展开、侧栏宽度 | 浏览器本地，按用户和空间 ID 分开；不经过服务端 |
| 草稿 | 新会话和 Thread 输入框里尚未发送的内容 | 窗格内存 |
| 服务端快照 | 当前用户、空间列表、含 Thread 状态的目录树、定时任务、执行记录页、Thread 快照、画布文档 | 查询缓存；事件只触发重新读取 |

所有请求都凭登录 cookie 认证，访问不属于本人的空间或资源一律返回 404。写操作另外遵守同一组规则：

- 创建类请求带 `Idempotency-Key`。新会话用草稿 ID，其它请求每次操作生成一个；网络中断后用同一个键重试，不会重复创建。资源存在时同一键重放原结果，物理删除后同一键视为新请求，可以用派生的 ID 再次创建。
- 目录结构写入带 `expectedTreeVersion`，配置写入带 `expectedVersion`。冲突时重新读取，保留用户正在输入的内容。
- 成功后以响应和随后的事件为准，客户端不推测其它浏览器的结果。

## 登录与账户

**登录与退出**

- 前端：未登录时整页显示登录，只有用户名、密码和「登录」。成功后读取空间列表，进入原地址或最近打开的空间；该用户还没有空间时按当前语言创建一个。
- 接口：`POST /api/auth/login`；`POST /api/auth/logout`。两者都校验 Studio Origin；登录成功写入 HttpOnly Cookie。
- 失败：用户不存在、密码错误和账号禁用都返回 `INVALID_CREDENTIALS`，提示「用户名或密码错误」。
- 退出：头像菜单的「退出登录」。有未发送的内容时先确认；成功后清空查询缓存、关闭本页的实时连接并显示登录。布局按用户保存在本机，下次登录恢复。

**登录失效**

- 请求返回 `NOT_AUTHENTICATED`，或实时连接因登录失效被关闭时，在当前页面上方显示登录框。用同一用户登录后留在原处，未发送的内容保留，失败的写操作不自动重发；换成其他用户登录时重新载入页面，不保留上一个用户的任何状态。

**修改密码**

- 前端：头像菜单的「修改密码」，填写当前密码和两次新密码；错误标在对应字段。
- 接口：`PUT /api/auth/password`。
- 事务：锁用户行 → 校验当前密码 → 写入新哈希 → 删除该用户的其它登录。
- 失败：当前密码错误返回 `WRONG_PASSWORD`。

**管理用户（管理员）**

- 前端：设置 → 系统 → 用户。卡片显示首字母头像和用户名，管理员带「管理员」标记；「新建用户」填写用户名、密码和角色；卡片上的「编辑」修改用户名、角色、账号状态和密码，密码留空表示不变。改自己的用户名时头像随之更新。禁用前确认会撤销登录、暂停定时任务；禁用自己的账号成功后返回登录页。
- 接口：`GET/POST /api/users`、`PATCH /api/users/{id}`。
- 事务：改名校验用户名唯一；改角色或账号状态时用同一把用户管理事务锁，确认至少一名管理员仍启用。禁用时删除全部登录并暂停该账号各空间的定时任务，不改历史记录和已提交回合。重新启用不自动恢复任务。只重置密码时删除全部登录，不暂停任务。
- 失败：`USERNAME_EXISTS` 标在用户名字段，`LAST_ADMIN` 标在角色或账号状态字段。管理员把自己降为普通用户后，设置只剩「界面」。

## 只改浏览器的动作

这些动作不调用接口、不发事件：

| 动作 | 结果 |
| --- | --- |
| 拆分（标题聚焦时按 `v`/`s`，或点拆分按钮） | 当前窗格变成拆分节点，新窗格获得焦点并显示「新会话」；放不下两个最小窗格时该方向不可用并说明原因。 |
| 关闭窗格 | 兄弟子树替换父节点；窗格里有未发送内容时先确认。资源和 Thread 不受影响。 |
| 拖动窗格字母交换 | 两个窗格交换绑定，比例不变。 |
| 拖动分隔线、方向键、双击 | 比例在允许范围内调整；双击回到最接近 1/2 的位置。 |
| 全屏和退出全屏 | 只隐藏侧栏和其它窗格，不保存。 |
| 窄屏切换窗格 | 用标题菜单选择要显示的窗格；保存的布局不变。 |
| 展开或折叠目录、Session | 按空间保存。 |
| 切换「目录 / 状态 / 定时」、筛选目录 | 临时状态；筛选期间不能拖拽。 |

## 打开资源

侧栏发起的打开只有一条流程，来源包括目录行、目录菜单里的「新建会话」和「新建画布」、状态行、执行记录、环境图标、Composer 的 `/files` 和深链：

1. **选窗格。** 当前窗格是空的「新会话」时直接打开。否则冻结窗格并显示 A–H：按字母或点击字母选择，`Enter` 选当前窗格，`Esc` 取消；窄屏显示缩略布局。
2. **替换。** 目标窗格有未发送内容时先确认。同一 Thread，或同一环境的同一功能，已在别的窗格打开时移到目标窗格，原窗格回到「新会话」。画布可以在多个窗格各开一个视角。
3. **加载。** Thread 读取 Harness 快照并订阅 `thread`；画布读取文档并订阅 `canvas`；VS Code 先复验环境已就绪且有该功能，再通过现有 IDE 视图绑定打开。
4. **目录不动。** 打开不改变目录的选中行和展开状态。

深链 `?thread=` 先读取 `GET /threads/{id}` 得到所属 Session 和标题；`?canvas=` 在当前目录快照中确认属于该空间。资源不存在时提示不可用，布局不变。资源被删除后，绑定它的窗格在下一次目录对账时回到「新会话」。

窗格里的动作在原窗格继续：发送首条消息、分支和从回合末尾新建会话都不走选择流程。

## 会话与 Thread

**发送首条消息**

- 前端：窗格目标是 `DRAFT{id, folderId}`。发送期间锁定输入并保留文字；成功后目标改为 `THREAD`，草稿清空。空间没有可用 Agent 时发送不可用：有 Agent 可选时，入口打开空间的默认配置；一个都没有时，管理员的入口打开 Agent 设置，普通用户只看到原因。
- 接口：`POST /sessions`，`Idempotency-Key` 为草稿 ID。
- 事务：锁空间行 → 校验目录属于该空间、默认 Agent 存在 → Harness 创建 Session、ROOT（根设置含 Agent 名称）和 `main` Thread，并接受首条命令 → 写目录边（含 Agent 名称，排在所在目录最上面）→ `tree_version` 加一。Session 标题取首条消息的前 40 个字符。
- 事件：`tree`；之后正文经 `thread` 订阅推送。
- 失败：目录已被删除时返回 `INVALID_FOLDER`，保留输入并要求改选目录。

**在 Thread 里发送、排队和停止**

- 前端：`ThreadPanel` 的 Composer。运行中发送的消息进入服务端队列；停止会撤回队列。
- 接口：`POST /api/harness/threads/{id}/command-batches`；停止为 `POST /api/harness/threads/{id}/stop`，带 `expectedVersion`。
- 事务：发送取执行树锁 → Session 共享锁 → 祖先链上的 Thread 行锁，写入命令、推进 Thread 版本并请求执行。停止取同一棵执行树的锁，锁住目标及其全部后代，撤回排队命令后按 Driver → 工具 → 执行的顺序结束执行。
- 事件：正文走 `thread`；状态变化发 `threads`，目录和状态视图重新读取。
- 失败：命令要求更换 Agent 时拒绝；版本冲突时重新读取快照。

**审批与问卷**

- 发现：侧栏顶部的「待处理」复用 `GET /api/harness/interactions`。服务端按登录用户可读的全部 Chat Space（含归档）授权，按执行根合并记录，每行显示所属空间。「状态」的「需要你」只列当前空间，来自目录树的状态投影。
- 打开：点击待处理条目时，先复验空间和 Thread 归属，进入该空间并恢复其布局，再走统一的窗格打开流程；不恢复已归档空间。子 Agent 发起的交互显示在所属执行根的卡片里，提交仍使用实际发起的 Thread 和工具调用 ID。
- 处理：只在 Thread 输入框上方的交互卡片提交。发送后暂时禁用卡片；收到冲突时回读实际待办，移除已处理的项，仍待处理时解锁。
- 接口：审批为 `PUT /api/harness/threads/{threadId}/tool-invocations/{toolInvocationId}/approval`，回答为同一路径下的 `POST …/input`。
- 事务：只锁目标 Thread 行，再锁同一批工具调用，写入决定并请求后续执行；不锁执行根。
- 事件：当前 Thread 走 `thread`，空间状态走 `threads`；「待处理」用 `interactions` 刷新同一登录用户的待办，重连后重新读取。处理完根下全部人工交互后移出待办。
- 插件后台任务：只在管理员的「待处理」末尾显示，打开后在窗格里处理。它们不属于任何空间，没有深链。

**从回合末尾分支或新建会话**

- 前端：回合末尾的两个按钮各打开名称框，建议名分别是「分支 N」和「原标题 · 分叉」。若输入框有未发送内容，须先在名称框中确认不带入这些内容才能创建；取消不发请求。成功后在当前窗格打开新的 Thread，展开所在目录并聚焦输入框。
- 接口：`POST /sessions/{id}/branches` 或 `POST /sessions/{id}/forks`，请求体为 `{sourceThreadId, turnEndEntryId, title}`，带 `Idempotency-Key`。
- 分支事务：锁 Session 行 → 校验切点是源 Thread 可见路径上的 `TURN_END`，分支名在 Session 内唯一 → 创建以该切点为 head 的公开根 Thread，复制切点处的设置；不调用模型。
- 新会话事务：锁空间行 → 把有效上下文复制到新 Session（新 ID、重写内部引用、为引用的 Blob 登记持有）→ 在源 Session 所在目录的最上面写目录边 → `tree_version` 加一。
- 事件：分支发 `threads`，新会话发 `tree`。
- 失败：切点无效返回 `INVALID_TURN_END`；分支重名返回 `THREAD_NAME_EXISTS`，名称框保持打开。

**改名**

- 前端：在目录行内改名（`r` 或 `F2`），`Enter` 提交、`Esc` 取消；冲突时保留输入并重新读取。
- 接口：Thread 为 `PATCH /threads/{id}`（`{title, expectedVersion}`），Session 为 `PATCH /resources/SESSION/{id}`（`{title}`）。
- 事务：Thread 改名在 Session 行锁内校验重名；Session 标题写入 Harness Session。不改变 `tree_version`。
- 事件：`tree`。

**删除 Thread 或 Session**

- 前端：`d`、`Delete` 或菜单，确认后提交。服务端拒绝时显示原因和可打开的相关对象；成功后从目录移除，绑定它的窗格回到「新会话」。删除最后一个公开 Thread 改为删除 Session。
- 接口：`DELETE /threads/{id}?expectedTreeVersion=…&expectedVersion=…`；`DELETE /sessions/{id}?expectedTreeVersion=…`。
- 事务：锁空间行 → 取执行闭包的 Harness 锁（执行树、Session、全部 Thread、命令、Driver、工具、执行）→ 复验已结算、没有待处理交互、没有定时任务引用 → 删除闭包，回收不可达的历史和独占附件引用 → 删除 Session 时一并删除目录边 → `tree_version` 加一。
- 事件：`tree`。
- 失败：`EXECUTION_NOT_SETTLED`、`RESOURCE_IN_USE`（列出引用它的定时任务）、`VERSION_CONFLICT`。

## 目录

**新建顶层目录或子目录**

- 前端：筛选框右侧的 `+`，或目录「…」里的「新建子目录」。筛选被清空，新行以改名输入框出现，`Enter` 提交、`Esc` 取消。
- 接口：`POST /folders`，请求体为 `{parentId, name, expectedTreeVersion}`。
- 事务：锁空间行 → 校验父目录属于该空间、同级名称唯一 → 追加到同级目录末尾 → `tree_version` 加一。
- 事件：`tree`。
- 失败：`FOLDER_NAME_EXISTS`；`VERSION_CONFLICT` 时重新读取并保留输入。

**新建会话、新建画布**

- 新建会话：打开 `DRAFT{folderId}`，走打开流程，不调用接口；首条消息发送后才创建。
- 新建画布：名称框确认后调用 `POST /canvases`（`{folderId, title, expectedTreeVersion}`）。事务锁空间行，由 Canvas 创建文档，再在所在目录最上面写目录边，`tree_version` 加一，发 `tree`。创建成功后展开所在目录，然后走打开流程；取消选择窗格时画布仍在目录里。

**移动和排序**

- 前端：桌面直接拖拽，触屏按住约 380ms 后拖拽。放在目录中间是移入，放在行的边缘是插到该行前后。以服务端返回的目录树为准，冲突时重新读取并提示。
- 接口：目录为 `PATCH /folders/{id}`（`{parentId?, beforeId?, expectedTreeVersion}`），资源为 `PATCH /resources/{kind}/{id}`（`{folderId?, beforeId?, expectedTreeVersion}`）。
- 事务：锁空间行 → 校验同一空间、不成环 → 在可延迟唯一约束下重排两侧顺序 → `tree_version` 加一。
- 事件：`tree`。

**改名和删除目录**

- 改名：`PATCH /folders/{id}`（`{name}`），校验同级唯一，不改变 `tree_version`，发 `tree`。
- 删除：只允许空目录。确认后调用 `DELETE /folders/{id}?expectedTreeVersion=…`；不是最后一个顶层目录、也没有定时任务以它为新建位置时才删除。失败为 `FOLDER_NOT_EMPTY`、`LAST_ROOT_FOLDER` 或 `RESOURCE_IN_USE`。

**删除画布**

- 接口：`DELETE /canvases/{id}?expectedTreeVersion=…&expectedRevision=…`。
- 事务：锁空间行 → 按文档、节点、运行、执行的顺序加锁 → 有 `READY`、`RUNNING` 或 `UNKNOWN` 的运行时拒绝 → 释放运行固定的资源，删除节点、分组、运行状态和资源关联 → 删除目录边和文档 → `tree_version` 加一。文件对象异步回收。
- 事件：`tree`；绑定该画布的窗格回到「新会话」。

## Chat Space

| 动作 | 接口 | 服务端 | 前端结果 |
| --- | --- | --- | --- |
| 用户首次登录且没有空间 | `GET` 后 `POST /api/chat-spaces` | 同一事务创建空间和默认目录，名称用当前语言 | 遇到同名冲突（另一个标签页刚创建）时重新读取列表。 |
| 创建 | `POST /api/chat-spaces` | 同上；名称在同一用户内忽略大小写唯一 | 进入新空间，只有一个「新会话」窗格。 |
| 切换 | 无写入 | 订阅新空间、退订旧空间 | 按空间 ID 恢复本机布局。 |
| 改名 | `PATCH /api/chat-spaces/{spaceId}`，带 `expectedVersion` | 校验名称在该用户内忽略大小写唯一，含归档空间；只改标题 | 当前行原地编辑。保存成功更新菜单行和侧栏标题，发 `space`；重名或版本冲突保留输入。ID、资源和布局不变。 |
| 默认配置 | 同一接口，带 `expectedVersion` | 只改默认 Agent、环境或权限 | 发 `space`；ID、资源和布局不变。 |
| 归档 | 同一接口，`archived: true` | 锁所有者的用户行，确认该用户还有其它活跃空间后写 `archived_at` | 切到另一个活跃空间；只剩一个活跃空间时返回 `LAST_ACTIVE_CHAT_SPACE`。 |
| 恢复 | 同一接口，`archived: false` | 清空 `archived_at` | 进入该空间，目录、资源和布局都在。 |

## 定时任务

**新建与编辑**

- 前端：编辑弹窗。触发规则变化时调用 `POST /schedules/preview` 显示最近三次运行时间；保存时字段错误标在对应字段，保存以打开时的任务版本为准。冲突时刷新列表并保留表单，禁用保存，直到用户主动放弃修改并重新读取最新配置。
- 接口：`GET /schedules` 查询当前空间任务；`POST /schedules` 创建；`PATCH /schedules/{id}` 更新（带 `expectedVersion`）。
- 事务：锁空间行和任务行 → 校验目标（新建位置的目录、Agent，或分支切点、续作 Thread）→ 计算 `next_due_at` → 写入任务和对应的唤醒 Work。
- 事件：任务配置提交后发送 `schedules`，数据为 `{scheduleId}`。
- 失败：`INVALID_SCHEDULE`、`VERSION_CONFLICT`。

**启停、立即运行和删除**

- 启停：开关调用 `PATCH /schedules/{id}`（`{enabled, expectedVersion}`）。暂停只取消之后的自动触发，不停止已提交的回合。
- 立即运行：`POST /schedules/{id}/runs`，每次操作一个独立的 `Idempotency-Key`。与自动触发走同一个业务事务，但不推进 `next_due_at`，暂停的任务也能运行。返回本次执行记录，前端展开该任务的最新一页，不打开窗格。
- 删除：编辑弹窗里的「删除任务」，确认后用编辑弹窗打开时的版本调用 `DELETE /schedules/{id}?expectedVersion=…`。冲突时刷新列表，须重新打开任务核对后才能再次删除。同一事务删除任务、唤醒 Work 和执行记录，已产生的会话和 Thread 保留。

**执行记录**

- 展开时调用 `GET /schedules/{id}/runs` 取最新五条；「较早」带上 `before` 游标，「较新」由前端保存的本次游标返回。游标按 `(scheduledAt,id)` 倒序分页，不查总数、不使用偏移量。一次只展开一个任务，收起后丢弃游标。
- 收到 `schedules` 实时帧（数据 `{scheduleId}`）时，仍在首页则重新读取首页；已经翻页则保持当前页，避免页边界跳动。
- 点击带 Thread 的记录走打开流程；Thread 已被删除时记录保留并显示「Thread 已删除」。

**自动触发（服务端）**

1. Worker 用独立短事务领取到期的唤醒 Work。
2. 业务事务按所有者 → 空间 → 任务加锁；所有者仍启用时，用数据库时间确认 `enabled`、版本和 `next_due_at`，再复验目标并锁住目标执行闭包。
3. 可以提交时，用确定性的幂等键接受 Harness 命令，写入 `SUBMITTED` 记录和产出 Thread；不能提交时写入 `SKIPPED` 或 `NOT_SUBMITTED` 记录和原因。
4. 提交前核验租约，并原子推进 `next_due_at` 和唤醒 Work；租约已失效则整体回滚。
5. 提交后发 `schedule`；产生新会话时另发 `tree`。

## 环境

- 列表：沿用现有的环境接口和 `environments` 订阅，圆点按就绪、连接中、离线或不可用显示。只有就绪且具备 VS Code 功能的环境显示打开按钮。
- 打开 VS Code：目标为 `{kind: 'vscode', environmentId, viewId}`。先复验环境可用性，常规打开优先复用已在窗格中的工作台，其次复用本浏览器会话已有的视图。窗格实际放置后才发起认领；原生 New Window 始终用独立 `viewId`，优先放入空的「新会话」窗格，否则拆分当前窗格。最多八窗格，放置失败不创建服务端视图。隐藏或全屏时工作台保持挂载；关闭或替换窗格前按现有退出确认释放视图。
- 失败：环境不再就绪时提示不可用，布局不变；视图绑定失败的状态显示在窗格内。

## 设置

进入设置跳转到 `/settings/:section`，工作区布局保留；左箭头回到原空间。普通用户只看到「界面」，直接打开其它分类时回到偏好设置。Agent、模型、提供商、技能包、MCP 和环境编辑沿用现有接口和未保存确认；系统设置由现有 schema 和聚合版本驱动；同步沿用导出、导入预检和确认流程。设置不订阅空间事件。
