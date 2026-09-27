# Canvas、Project 与交互技术方案

本方案定义目标职责、数据模型、并发与执行协议，供 Review、实现和验收使用。
目标结构只有一份权威 DDL：生产 Flyway 基线
[`V1__schema.sql`](../schema/src/main/resources/db/migration/V1__schema.sql)，把它加载进隔离
PostgreSQL 即可验证下面的数据库约束；它不是第二份 schema，也不表示运行代码已经完成重构。
当前实现见[系统设计](system-design.md)；实现切换时同步模块文档与唯一 Flyway 基线，不维护
双写或兼容业务模型。

核心原则：配置整体保存，执行事实复用 Harness，内容只有一个事实源；可靠性机制服务于
明确故障场景，不扩展成通用平台。减少维护成本，而不是单纯追求最少表数。

先把三个产品概念分清：

```text
Canvas：资源节点 + 可选 Function -> 显式生产并替换节点输出
Project：工作流配置 + Issue     -> 明确阶段、分工、额度和交付
Harness：Thread + Invocation    -> 执行 Agent，持久等待回答或审批

Issue + Agent -> 稳定 Thread    # 工作上下文
Issue + state -> 阶段预算        # 执行次数授权
Run           -> 一次执行记录    # 冻结身份与历史区间
```

Project 的界面与工具面向 Thread；Session 是 Harness 的历史和资源容器，Branch 只是
Entry Tree 中的历史路径，不是 Project 实体。下文先说明产品行为，再给出数据与事务边界。

## 1. 职责与模块

| 边界 | 拥有的事实 | 对外能力 |
| --- | --- | --- |
| Canvas | 资源节点、视觉布局、引用、函数执行 | 查询、原子编辑、显式生产资源 |
| Project | 工作流配置、Issue、阶段执行与交付物 | 分配职责、控制执行、显式交接、跟踪工作 |
| Harness | Session、Entry Tree、Thread、Invocation、Work | Agent Loop、工具审批、持久输入等待 |
| Chat | 对话入口、Agent 设置、Session 关联、归档 | 通用 Pane 与会话组织 |
| Storage | 不可变 Blob、上传暂存、引用计数 | 媒体存储、授权访问、回收 |
| 构建期插件 | 具体函数实现及其私有模板 | 实现 Canvas Function SPI |

保持模块化单体：同进程、同 PostgreSQL，不增加 RPC、服务发现或消息队列。

```text
canvas/core       领域值、typed commands、Function SPI、宿主端口
canvas/infra      Canvas 持久化、应用服务、Function Runtime
project           独立 Maven 模块，内部按领域/服务/持久化分包
plugins           构建期可选的具体能力
platform          Chat、Storage、Catalog、宿主集成适配
web               HTTP/WebSocket 与应用装配
```

Canvas 与 Project 不互相依赖，也不直接写对方的表。Agent 通过工具组合两个产品。
Project 使用 Harness 公共能力；宿主配置、Blob 和权限经明确端口适配。具体插件依赖
Canvas SPI，Canvas 不依赖具体插件。先复用已有自动装配和插件宿主接口，不增加一套
插件管理框架或为每层建立 Maven 子模块。

UI 与 Agent 调用同一应用服务。读取返回摘要、当前事实和合法动作；写入使用严格参数、
请求身份及必要的前置条件。UUID、资源 URI 和事件中的跳转地址都不是授权。
当前沿用实际单用户认证边界，不假造用户/组织表；操作者身份由服务端认证上下文取得。

Canvas 是资源工作台，不是自动执行 DAG。Project 是工作管理，不做机器强制的 Issue
依赖图、知识库或通用 BPM。依赖说明写在 Issue 正文，是否阻塞和恢复由明确业务操作决定。
Chat 中创建的 Canvas/Project 独立存在，删除 Chat 不删除这些产品。

## 2. Canvas：内容、引用与协作编辑

### 2.1 一个节点模型

```text
ResourceNode
  id / name
  resources: Resource[]
  function?: { name, args }
  position / size / group

Resource: TEXT | IMAGE | VIDEO | AUDIO
```

Function 整体存入节点的可空 `function` JSON，例：

```json
{
  "name": "image.crop",
  "args": {
    "source": {"type": "resource", "nodeId": "...", "index": 0},
    "x": 100,
    "y": 50,
    "width": 640,
    "height": 480
  }
}
```

模型只是生成函数的参数。裁剪、拼接等函数没有模型参数。函数目录声明参数 Schema、
输入资源限制与有界输出计划（1..8 个槽位，可同时产出内联文本与媒体）；不要求全部函数
具备 prompt、model 或 provider。

Resource 内容不可变：TEXT 内联 `text_content`，媒体引用 `storage_blob`，两者恰好一个
非空。类型由文本分支或 Blob 权威 MIME 推导。编辑文字、替换媒体创建新 Resource；
仅当前挂接关系可以变化。Resource 不等于永久作品历史：无节点持有且无 pin 时可以回收。
复制资源到另一节点创建另一条 Resource，媒体字节继续共享 Blob。

节点名先 NFKC，再去首尾空格并转小写，数据库生成
`name_key = lower(btrim(normalize(name, NFKC)))`，要求非空且画布内唯一。
应用额外拒绝控制字符；显示名可以保留大小写。
引用身份始终是 UUID，不靠名称绑定，不维护别名表。分组只做不嵌套的视觉组织。

### 2.2 引用与连线

`{"type":"resource","nodeId":"...","index":0}` 是核心保留的资源引用值，允许出现在
`function.args` 的任意嵌套位置。核心按该严格形状遍历，插件 Schema 声明这些位置的类型
和数量约束。即使插件未安装，仍能识别已有配置的引用并展示连线。

引用只存一份；连线是读取投影。普通文字中的 `@` 不靠正则解释成关系。
`index` 从零开始，指向源节点当前输出位置；启动时解析成实际 Resource ID。

- 配置引用限于同画布；保存时检查目标存在与访问范围。
- 尚无输出的位置可以保存为显式失效引用，但执行前必须完整解析并校验类型。
- 重命名不影响引用；位置失效不自动换位或重绑同名节点。
- 删除被引用节点需显式解除引用，可与删除放在同一原子编辑批。
- 核心限制配置大小、引用数量与遍历深度；不建立第二份可写边索引。
- 上游输出改变不自动触发下游执行。

### 2.3 并发不是整图 CAS

`canvas_document.revision` 只用于同步排序、补漏和确认接受位置，不是普通编辑的整图
前置版本。服务端接受 typed commands，不接受整张旧快照覆盖。

| 修改 | 前置条件/冲突粒度 |
| --- | --- |
| 名称 | 编辑起点的旧名称 |
| 文字或资源列表 | 编辑起点的有序 Resource ID 列表 |
| Function | 编辑起点的完整 `{name,args}`，按 JSON 语义比较 |
| 删除节点 | 当前内容、配置及引用关系满足删除前提 |
| 布局 | 在线操作按服务端接受顺序收敛；重连积压先比较布局基线 |

不同节点、同节点不同语义组互不覆盖。Function 参数作为一个整体，不盲目合并可能互相
约束的 JSON 字段。真正冲突返回受影响对象和服务端值，不用新基线偷偷重试旧内容。
多命令批全部验证、全部提交或全部回滚。数据库可用 Document 短事务锁排序提交，
锁内不执行上传、模型调用或其他外部 I/O。无需每字段版本列、CRDT 或 OT。

布局的最后接受值不代表所有人的位置意图都被保留；它只适用于可撤销的低风险几何信息，
不用于文字、参数、资源或删除。断网积压布局不得作为新的在线操作无条件重放。

### 2.4 客户端草稿是明确的状态层

```text
服务端已确认快照 + 待确认操作 + 编辑草稿 = 当前展示
```

草稿保存编辑基线和本地 generation，位于节点组件之外。远端更新只更新确认层，
不能重置 dirty 输入、关闭编辑器或改变用户选择。ACK 只清除对应 operation/generation，
旧响应不能清掉后来输入。

IndexedDB 按登录身份、画布和编辑会话保存已落盘草稿及待确认操作。刷新、路由切换、
重连后先恢复草稿，再对照权威快照；多标签页不相互覆盖。界面区分未保存、已保存到本机、
正在同步、已同步和冲突。落盘失败要显示告警；清除站点数据、存储回收或设备损坏不在
本机保存保证内，未完成落盘的最后输入也不能宣称已持久化。

同一内容冲突时保留我的草稿与远端内容，支持合并保存、另存新节点或明确放弃草稿。
远端删除目标后，保留可恢复草稿，用新 ID 另建节点，不能偷偷复活旧 ID。
撤销也是新的细粒度操作，只撤销自己的修改，不还原整个画布旧快照。

每个已发送请求的 ID、参数和前置条件冻结。网络重试沿用原请求；解决内容冲突是新请求。
去重记录与业务写入同事务提交，并先于编辑前置条件校验。已接受的旧请求只返回接受
回执，不重新执行，也不把新运行状态冒充旧请求结果。

正常响应/事件可携带实体 patch；重复消息忽略，revision 有缺口时读取快照。
WebSocket/NOTIFY 是提示，重连和低频版本校验兜底，不保存永久协作操作日志。
读取新快照永远不等于丢弃未保存草稿。

## 3. Function 插件与资源发布

### 3.1 最小扩展面

构建期插件提供函数定义、参数校验、执行实现和必要的取消能力。执行拿到冻结参数、
冻结输入、上次 checkpoint 及受控的输入读取/输出物化接口；同一执行入口可以根据
checkpoint 恢复。同步裁剪不必实现远端轮询，异步生成必须识别自己的外部任务身份。

Runtime 统一负责请求接受、pin、租约、恢复调度、输出发布和清理；插件不直接改节点，
也不再各建任务表、队列和资源计数。checkpoint 是有界、可序列化状态，不存进程句柄、
临时下载 URL 或连接凭据。

具体能力按实际依赖组织，例如 `canvas-media`、`canvas-comfyui`、`canvas-opencli`。
JAR 通过现有 Spring 自动装配注册，函数名全局唯一，启动检查重复。配置/凭据沿用宿主
配置和凭据能力，节点参数只表达业务输入。常规输入复用基础编辑控件；专用编辑器也是
构建期组件，不做表单脚本或热加载。

ComfyUI 插件持有客户端、包内工作流模板、参数绑定、上传、提交、查询、取消和下载。
外部 ComfyUI 引擎继续执行任务；产品不提供独立 workflow CRUD 或另一套运行 API。
流程模板随插件发布。插件缺失时保留节点、配置、引用和输出，显示不可用；配置只读，
可显式清除或替换为已安装函数。移除插件前先收尾该插件的活跃任务。

### 3.2 持久执行协议

```text
事务：校验请求/配置/输入 -> 冻结执行状态 -> pin 输入 -> READY
  -> 事务外执行/上传 -> checkpoint 外部任务身份和输出计划
  -> 事务：物化 Resource + OUTPUT pin
  -> 成功事务：校验 requestId/lease/输出完整性
               替换 Resource[] + Run=SUCCEEDED + 清租约
               释放本次 pin + 清理无引用资源 + 推进 revision
```

一个节点只有当前/最后一次 Run。READY、RUNNING 或未核查 UNKNOWN 时不得用新请求替换。
配置可继续编辑，下一次执行才使用新值。本次运行期间禁止手工换输出和删除节点；
需先停止并确认安全收尾。失败或取消不清空上次成功结果，不发布部分数组。

输入冻结的是实际 Resource，不是待执行时再次解析的节点位置。输出数量有界，计划
Resource ID 先放在 Run checkpoint；真正物化时才原子插入 Resource 与 OUTPUT pin。
因此所有 pin 都能使用真实 Resource 外键，不保留指向尚不存在资源的占位 pin。

Resource 行持有 Blob 引用，pin 不重复 retain Blob。媒体暂存复用 `storage_upload`；
物化时检查当前请求和租约，转移/建立正式引用。成功、确定失败或取消时释放本次 pin，
清理无节点持有且无其他 pin 的 Resource。Blob 引用变更与关系写入同事务；对象字节由
Storage GC 事务外删除。输入源重跑或节点解除挂接，不影响已冻结输入。

READY 表示下一次可调度步骤，RUNNING 表示 Worker 正持有租约，并非外部任务状态。
已持久化可查询任务身份时，checkpoint 决定后续只查询；轮询间隔退回 READY 并设置
available_at，崩溃后经租约回收恢复。外部提交前先持久化提交意图；提交结果不明且没有
安全查询/幂等依据时进入 UNKNOWN，退出自动调度，要求人工核查并保留必要 pin。
核查事实持久化后，才能继续查询原任务或确认失败/取消，不能把 UNKNOWN 当成新请求重提。
租约围栏防止旧 Worker 发布结果，但不能证明外部未执行；取消请求不等于取消成功。

## 4. Project：配置、身份与执行

### 4.1 工作流是一份配置

`project.workflow` 保存严格 JSON：

```json
{
  "states": [
    {"state": "INIT", "name": "待开始", "next": ["DESIGN"]},
    {
      "state": "DESIGN",
      "name": "设计",
      "agent": "designer",
      "instructions": "完成可交付方案",
      "maxRuns": 3,
      "next": ["REVIEW"]
    },
    {
      "state": "REVIEW",
      "name": "检查",
      "agent": "reviewer",
      "instructions": "按 Issue 验收要求检查",
      "maxRuns": 3,
      "next": ["DESIGN", "DONE"]
    },
    {"state": "BLOCKED", "name": "业务阻塞"},
    {"state": "DONE", "name": "完成"}
  ]
}
```

数组顺序即展示顺序；`next` 是正常转移白名单。状态编码为项目内自然字符串，匹配
`[A-Z][A-Z0-9_]{0,63}`。INIT/BLOCKED/DONE 固定保留，其他状态为工作阶段。
工作阶段配置 `agent`、`environment`、`instructions`、`maxRuns` 和 `enabled`。
`enabled` 默认 true；有 Agent 必须有正数 maxRuns；无 Agent 为人工阶段，不配置
Environment 和 Run 额度，也不自动跳过或产生 Run。

创建项目提供 INIT→WORK→DONE 的默认流程。BLOCKED 通过专用阻塞/恢复操作处理，不配置
普通边；DONE 通过显式重开操作回 INIT。允许返工环，不允许无意义的自身边；启用阶段从
INIT 可达且存在到 DONE 的正常路径。历史用过的编码只可停用，不删除或改码。

配置整体保存，在 Project UPDATE 锁和版本检查下校验保留值、状态唯一、边合法、
可达性、Agent/Environment 引用和大小上限。影响执行的结构、指令或职责绑定调整要求
项目无活动主 Run；所有当前 Issue 和阻塞恢复点仍须有效，不能停用仍在使用的阶段。
显示名可修改。Agent/Environment 在执行接受时再次解析；缺失则拒绝执行，不静默替换。
JSON 内的状态和外部名称没有关系型 FK，其正确性由统一服务负责，不编写庞大 SQL 触发器。

### 4.2 Agent 使用稳定 Thread

```text
Issue A
  +-- designer -> Thread D -> Run 1(DESIGN), Run 3(REWORK)
  +-- reviewer -> Thread R -> Run 2(REVIEW), Run 4(REVIEW)
```

`project_issue_agent_thread` 只保存 `(issue_id,agent_name) -> thread_id`，首次接受执行时
惰性创建，以后不可重绑。同一 Agent 跨阶段、返工和重开复用同一 Thread；不同 Agent、
不同 Issue 不共享 Thread。Agent 自然名称是稳定身份，修改模型或提示词不改变身份。

每次 Run 都明确注入最新 Issue、当前阶段要求和必要交接材料，并按当前阶段显式设置
Environment，未配置时清除上次选择。历史提供上下文，不替代当前阶段职责。换 Agent
选择其已有或新建 Thread；换回原 Agent 则继续原 Thread，不改旧 Run，不重置预算。
Project Thread 的输入、Stop、配置与派发必须经过 Issue 编排；通用 Chat 接口不能切换
其 Agent、另开执行分支或绕过 Run 门禁。

底层为每个 Agent Thread 新建独立 Session，保留现有历史与附件授权边界；Session ID
由 Thread 解析，不在绑定表重复保存。Run 内的 session_id 仅用于冻结历史坐标和
同域复合 FK，不是第二套可变归属。Run 的 Thread 和 Entry 都必须属于该 Session。
内部委派 Session 沿 Harness 自身生命周期管理，不加入产品 Agent 绑定。

### 4.3 阶段预算不随 Agent 变化

`project_issue_stage_budget` 按 `(issue_id,state)` 保存 `max_runs` 和
`budget_after_ordinal`，不保存 Agent、Thread 或 Session：

```text
used = COUNT(project_issue_run
             WHERE issue_id = ? AND state = ?
               AND ordinal > budget_after_ordinal)
remaining = max(0, max_runs - used)
```

Run ordinal 在整个 Issue 内单调分配，接受事务锁 Issue、分配序号并插入 Run。
查询不按 Agent/Session/Thread 过滤，因此更换 Agent、返工或重开都不重置额度。
新 Run 的失败、取消、UNKNOWN 仍消耗一次；工具循环、协议续生成、问答等待与恢复不消耗
新次数。工作流默认额度只用于首次授权；修改默认值不悄悄改变既有授权。

重置只能由有权限的人在无活动 Run 时进行：高水位由服务端取 `next_run_ordinal - 1`，
记录当前及前后授权事实到 Activity。初次授权也记录同类事件，历史审计不依赖当前值。
不另存 used/remaining，不增加预算账本或 Epoch。Agent 不能提升自己的额度。

额度限制新建 Run，不限制已接受 Run 的恢复。运行仍受持久化活动时长余额及已有模型、
工具安全上限约束；安全等待不耗活动时长。到达安全点后才置 WAITING，存在在途执行时
不能仅因出现问卷就宣称已暂停。

### 4.4 业务状态、控制门禁与交互分开

| 事实 | 保存位置 | 行为 |
| --- | --- | --- |
| 当前工作阶段 | `Issue.state` | 明确流转，不从模型回复猜测 |
| 业务无法继续 | BLOCKED + `blocked_from_state/block_reason` | 明确恢复到原状态，无依赖自动唤醒 |
| 人工暂停或执行失败/不确定 | `pause_reason/pause_detail` | 阻止新派发，显式恢复 |
| 工具等待回答/授权 | ToolInvocation | 不自动改变 Issue.state |
| 阶段新 Run 额度耗尽 | 从阶段预算与 Run 历史计算 | 允许当前 Run 恢复，禁止新建 |
| 已安全暂停的执行 | `Run.status=WAITING` | 等待原因从真实门禁/Invocation 读取 |

同一 Issue 只有一个 RUNNING/WAITING 主 Run，以部分唯一索引保证。
`project.yolo_enabled` 保留实际执行策略；接受和派发按当前项目策略对齐 Thread 设置，
不能只更新展示值。YOLO 不跳过权限拒绝、Issue/Run 身份或资源访问检查，也不替用户答题。

业务阻塞操作先原子记录 BLOCKED、原阶段和原因，并关掉新派发门禁；在途调用继续按原
Run 身份收尾，到安全点后置 WAITING。恢复时明确还原原阶段并唤醒同一 Run，问卷回答
不会自动解除业务 BLOCKED。没有活动 Run 时只改变 Issue，不制造空执行。

停止/取消不等于业务完成。人工 Stop 收尾为 CANCELLED，并保留 USER 暂停门禁，不能被
轮询立即启动新 Run；在途副作用不明则进入 UNKNOWN。直接换阶段和 Project/Issue 归档
要求没有活动 Run，否则明确要求先停止/核查再重试，不在 HTTP 事务内等待外部执行。
归档后禁止执行；Chat 的归档只做列表收纳，不隐式停止 Agent，运行回调也不取消 Chat 归档。

### 4.5 Run 接受与交接

接受一个 Run 时，在同一物理事务完成权限与当前状态检查、阶段预算检查、Agent Thread
创建或选择、起点冻结、Run 与唯一 RUN 活动插入、Harness 初始命令接受及 Work 登记。
这些是原子提交内容，首次创建的写入依赖顺序见 §7.1；模型/工具外部调用发生在事务外。

INIT 与人工阶段通过显式合法转移进入下一阶段。进入有 Agent 的工作阶段时登记 Work；
正常结束但没有交接的 Run 仍终结并保持当前阶段，控制器的下一次催促必须创建新 Run
并重新检查额度。用尽额度只展示待人工授权，不继续调用模型，也不把阶段标成 DONE。
Run 接受前要求该 Thread 没有遗留调用或未归属命令，不能把上次输入带进新历史区间。

当前 Agent 使用 `issue.transition(toState)` 请求交接，先将目标保存到 Run.next_state，
返回“已接受，收尾后生效”。此后不接受新的业务写调用，已派发调用收尾，模型输出报告。
用户干预仍进入受控队列；新输入未处理完不能提交交接。

```text
next_state 已接受
  -> 安全收尾，没有未处理命令、问答或调用
  -> 事务：复验 Run 身份/版本/合法边/门禁
           冻结历史区间与报告，Run=COMPLETED
           更新 Issue.state，记录活动，发布证据，请求后继 Work
```

暂停时保留同一 Run 的交接目标，恢复后继续收尾。失败/取消不能提交目标；
失败/UNKNOWN 终态与 Issue 暂停门禁同事务写入。UNKNOWN 解除必须记录人工核查依据。
旧 Worker、旧 Run 和同名阶段的迟到回调都必须拒绝，不能仅比较 state 字符串。
进程崩溃后继续收尾原 Run，不新扣额度。

Run 区间为 `(start_entry_id,end_entry_id]`，沿 Entry 父链解释，不按时间排序。
`final_answer_entry_id` 可空，必须是区间内真实可见回答；异常没有报告时展示原因。
不复用上次报告，不另调付费模型补写报告。Thread 历史中的 Goal 不能修改
Issue 的需求事实或代替显式交接。

### 4.6 Activity、证据与 Agent 协作

Activity 是唯一业务时间线：COMMENT、RUN、INSTRUCTION、SPEC_CHANGE、STATE_CHANGE、CONTROL。
普通评论由人或 Agent 发表，不自动唤醒 Agent；RUN 只引用 Run，实时状态与最终报告按引用
读取，不复制正文。问卷和工具审批只出现在 Pane/待处理列表，不增加 QUESTION 活动。

INSTRUCTION 只投递给明确的当前 Run；没有活动 Run 时，先明确启动/恢复，不构造隐藏的
未来阶段消息队列。命令持久接受与 `observed_activity_sequence` 推进同事务；服务端游标
不能越过未处理的目标指令，也必须跳过已分类的普通评论和系统事件，避免永久卡住。
自动系统回声不再次喂给 Agent。

成功的 Issue 写操作与对应 Activity 同事务保存，以请求键和规范化请求指纹去重，
精确重试先于版本校验；重试不重复接受 Run 或 Harness 指令。一个动作产生多条活动时
使用确定的子键，原请求回执仍唯一。初次/重置额度的 CONTROL 数据含阶段、前后额度和
高水位，不在 Run 再复制一份授权快照。

Evidence 独立保留 Blob，`(issue_id,blob_id)` 唯一，首次发布才 retain。
发布者从执行身份取得，来源 Run 可空；最终报告中的合法受管资源在收尾时发布，
也支持显式发布和人工上传。文本文件交付需显式物化，不增加第二种证据存储。

不同 Agent 获得最新 Issue、必要指令、前序报告和已发布证据，不复制全部对话。
`read_memory(threadId,startEntryId,endEntryId)` 从 Thread 解析 Session，在授权并校验
目标区间属于该 Thread 历史路径后读取；本 Issue 的 Agent 可按需读取其他 Agent
历史，不允许任意枚举别的 Chat/Issue。
读取文本不自动授予其中全部附件；使用证据时显式建立当前 Session 的 Blob 引用。
默认内联有界摘要，大输出复用 `kkstudio:/resources/<blobId>` 和现有窗口读取协议。
不导出 Provider 私有 replay/凭据，不为跨 Session 协作另建记忆库。

## 5. 通用问答、审批与待处理入口

### 5.1 ask_user 的冻结问卷

```json
{
  "questions": [
    {
      "question": "最终视频使用什么分辨率？",
      "options": [
        {"label": "1080P", "description": "清晰度与体积平衡", "recommended": true},
        {"label": "720P", "description": "用于预览"}
      ]
    },
    {
      "question": "需要哪些交付物？",
      "multiple": true,
      "options": [{"label": "成片"}, {"label": "字幕"}]
    }
  ]
}
```

模型不生成 questionId/optionId。每份问卷接受后冻结，答案按问题位置对应：

```json
{"answers":[["1080P"],["成片","字幕","另提供工程源文件"]]}
```

单选为默认；每题 UI 固定提供自定义输入，不由 Agent 增加“其他”选项。推荐只做标记，
不默认代替用户选择。单选最多一个推荐，多选可多个；同题标签唯一。答案必须覆盖所有题，
单选恰好一个回答，多选去重，可额外有一项自定义文本；限制题数、选项数和字节长度。
没有选项时可填写自定义回答。没有条件显隐、脚本、表单 ID 或跨问卷引用。

普通消息不猜测成问卷答案；用户在 Pane 卡片中选择/输入后明确提交。草稿遵守不覆盖
本地输入原则。用户拒答是明确工具结果，不能虚构默认答案；Stop 则取消等待，迟到回复
不得恢复执行。当前仅面向用户的 Chat Thread 与 Issue Agent Thread 可直接 ask_user；
内部委派先把缺失信息返回父级，不将进程内委派等待链挂在人类输入上。

### 5.2 复用 Invocation，不复制待办

```text
WAITING_INPUT     -> 问卷卡片
WAITING_APPROVAL  -> 权限审批卡片

ToolInvocation -> ModelInvocation -> Thread
                                      +-> Issue + Agent
                                      +-> Session -> Chat
```

问卷来自 Invocation.call，审批来自 Invocation.approval。待处理服务按两种等待状态查询，
权限过滤后分页，组装来源和 Pane 跳转。不能要求 ModelInvocation.status=RUNNING：
模型已完成输出后，工具仍可能等待。全局列表、Pane 和角标使用相同事实源。

持久化等待不占 Worker、外部 Gateway Handle 或长连接。内部工具接受路径原子把
Invocation 置 WAITING_INPUT、完成 TOOL Work、推进 Thread 版本，然后退出。
回答按 Invocation ID 锁定当前真实调用，校验身份和冻结问卷，写 ToolResult 与提交回执，
终态化调用并登记后续 Work。权限审批允许后回 READY，拒绝后返回失败工具结果；
审批通过不等于工具执行成功。工具失败也不必然等于整个 Project Run 失败。

回答回执保存在 Invocation 的可空 `input_receipt` JSON，包含 `submissionId`、`actor`、
`acceptedAt`，与结果同事务写入。该字段不保存问卷或答案，仅解决结果尚未进入 Entry 时
的重试与崩溃恢复；它不能放在内存或冒用业务 result.details。明确拒答返回
`{"declined":true}` 的成功工具结果并记录同样回执；Stop 使用 CANCELLED，不冒充拒答。

问卷不能授权工具，审批内容必须由系统按真实冻结调用构造。当前权限审批逻辑继续复用；
WAITING_INPUT 必须贯通状态转换、非终态查询、Stop、恢复、快照、前端展示和索引，
不能只增加一个 enum 值。

### 5.3 当前状态与历史无缝交接

运行时 Invocation 是当前事实，终态结果追加到 `harness_entry` 后才在同一事务中删除。
问卷原文在 Assistant ToolCall，答案在 ToolResult，不再复制到专用 Question 表。

Runtime 的真实 ToolResult 元数据保存原 `invocationId`，与现有 `assistantEntryId`、
`callIndex` 一起提供来源定位；交互回执保存原 Thread、提交 ID、操作者和接受时间。
问卷答案只存结果内容；权限决定的完整审计事实从 Invocation.approval 迁入运行时历史
元数据，不占用具体工具的业务 result/details 命名空间。生成这些字段的是 Runtime，
不是 Agent 或工具插件。合成的 history-cut 结果不携带真实调用接受回执。

待处理时按 Invocation 判断，清理后按原调用 ID 及稳定坐标查历史并核验原 Thread/父链。
相同提交重试返回原接受事实；同键异内容、另一个相互冲突的提交或已取消目标拒绝。
问卷内容不变，因此不需要另存答案 hash；答案按规范化结构比较。多选顺序归一化，
避免仅选择顺序不同被判断为不同请求。重复提交不能重复追加 Entry 或重复请求执行。

### 5.4 门禁和事件

回答/审批可在人工暂停时被记录，但不能绕过暂停、归档、当前 Run 身份和授权继续派发。
产品集成入口及 Harness 的模型/工具派发统一执行产品门禁，不能只在 Pane 按钮上检查。
安全的结果物化可以完成，新的外部调用必须等门禁解除。恢复操作登记持久 Work。
输入等待恢复仍属于原 Run，不因刚好用尽最后一次额度而死锁。

事务提交后发布小型 `InteractionChanged` 通知，包含类型、变化、原调用和 Thread 坐标。
通知只提示 Pane/待处理查询更新，漏消息由重连及列表查询恢复。原始参数和答案不广播给
所有订阅者，读取仍校验权限。执行恢复由同事务写入的 Work 保证，不依赖事件监听器。
当前只提供站内待处理；外部消息适配与可靠投递策略属于独立能力，不预建通知流水和 Outbox。

## 6. 数据关系与字段字典

目标领域/关联表共 16 张：Canvas 7、Project 8、Chat 关联 1。Chat 本身修改，
Harness 七表、Catalog、Environment、Storage 继续复用。PK/UK/FK 用于关系完整性，
JSON Schema、权限与状态转换由应用实现。所有时间使用 `timestamptz(3)`，UUID 由受控
创建路径产生，所有业务删除使用 RESTRICT 与显式清理。

```text
canvas_document
  +--< canvas_group
  +--< canvas_node --? canvas_group
  |       +--< canvas_resource >--? storage_blob
  |       +--0..1 canvas_function_run
  |                   +--< canvas_function_resource_pin >-- canvas_resource
  +--< canvas_command_dedup

project
  +--< project_issue
          +--< project_issue_agent_thread >-- harness_thread
          +--< project_issue_stage_budget
          +--< project_issue_run >-- harness_thread / harness_entry
          +--< project_issue_activity --? project_issue_run
          +--< project_issue_evidence >-- storage_blob
          +--0..1 project_issue_work

chat --< chat_session >-- harness_session
harness_thread >-- harness_session --< harness_entry
harness_tool_invocation >-- harness_model_invocation >-- harness_thread
```

Run 通过复合 FK 绑定同 Issue 的 Thread、同阶段的预算，并保证 Thread/Entry 属于
同一 Session。Canvas 无 Session 归属；问答只复用 Harness 调用与历史。

### 6.1 Canvas 七表

| 表 | 字段 | 作用与约束 |
| --- | --- | --- |
| `canvas_document` | `id uuid PK; title varchar(256); revision bigint; created_at; updated_at` | revision 非负，仅作同步位置 |
| `canvas_group` | `id uuid PK; canvas_id uuid FK; title varchar(256); x/y/width/height float8` | 同画布复合 UK；坐标有限，尺寸为正 |
| `canvas_node` | `id uuid PK; canvas_id uuid FK; name varchar(256); name_key generated; x/y/width/height float8; group_id uuid?; function jsonb?` | `(canvas_id,name_key)` UK；group 必须同画布；function 为 `{name,args}` 对象 |
| `canvas_resource` | `id uuid PK; canvas_id uuid FK; owner_node_id uuid?; resource_index int?; name varchar(512); blob_id uuid?; text_content text?; created_at` | owner/index 同存同缺；当前槽位 UK；内容来源 XOR；同画布 Node FK |
| `canvas_function_run` | `node_id uuid PK/FK; request_id uuid; status varchar(16); attempt int; available_at; lease_token varchar(128)?; lease_until?; state_json jsonb; error text?; created_at; updated_at` | 当前单行执行；`(node_id,request_id)` UK；状态/租约形状约束 |
| `canvas_function_resource_pin` | `canvas_id uuid; node_id uuid; request_id uuid; role varchar(16); resource_id uuid` | 组合 PK；role=INPUT/OUTPUT；同画布 Node/Resource FK 与 Run 请求 FK |
| `canvas_command_dedup` | `canvas_id uuid FK; idempotency_key uuid; request_hash char(64); accepted_revision bigint` | `(canvas_id,idempotency_key)` PK；记录接受位置，不存完整响应 |

函数状态为 READY/RUNNING/SUCCEEDED/FAILED/CANCELLED/UNKNOWN。state_json 只含冻结函数、
实际输入、输出计划和插件 checkpoint；普通状态查询/claim 使用结构化列。
主要索引：节点分组、资源 owner 槽位、pin 的资源反查、可领取/过期租约 Run。
资源数组连续性、内容不可变、发布完整性及 Blob 计数由应用事务保证，不用复杂触发器。

### 6.2 Project 八表

下列可变实体附 `created_at/updated_at`；Run 使用 `started_at/ended_at`，只追加的
Activity、Evidence 和稳定 Thread 绑定仅需 `created_at`。

| 表 | 字段 | 作用与约束 |
| --- | --- | --- |
| `project` | `id uuid PK; title varchar(256); description text; workflow jsonb; yolo_enabled boolean; next_issue_number bigint; version bigint; archived_at?` | 项目配置、编号分配、CAS；workflow 必须为 object 且 states 为 array |
| `project_issue` | `id uuid PK; project_id uuid FK; number bigint; title varchar(256); description text; state varchar(64); blocked_from_state varchar(64)?; block_reason text?; pause_reason varchar(16)?; pause_detail text?; next_run_ordinal bigint; next_activity_sequence bigint; version bigint; archived_at?` | `(project_id,number)` UK；BLOCKED 形状约束；pause=USER/ERROR/UNKNOWN 或空 |
| `project_issue_agent_thread` | `issue_id uuid FK; agent_name varchar(64) FK; thread_id uuid FK` | PK `(issue_id,agent_name)`；thread_id UK；`(issue_id,thread_id)` UK 支持 Run 同域 FK |
| `project_issue_stage_budget` | `issue_id uuid FK; state varchar(64); max_runs int; budget_after_ordinal bigint` | PK `(issue_id,state)`；工作阶段；正数额度、非负高水位；不关联 Agent/Thread |
| `project_issue_run` | `id uuid PK; issue_id uuid; ordinal bigint; state varchar(64); session_id uuid; thread_id uuid; status varchar(16); start_entry_id uuid; end_entry_id uuid?; final_answer_entry_id uuid?; next_state varchar(64)?; observed_activity_sequence bigint; remaining_execution_ms bigint; active_since?; error text?; version bigint` | `(issue_id,ordinal)` UK；同 Issue 唯一活动 Run；同 Issue Thread/阶段预算 FK；历史坐标同 Session FK |
| `project_issue_activity` | `issue_id uuid FK; sequence bigint; kind varchar(24); actor_type varchar(16); actor_agent_name varchar(64)?; run_id uuid?; body text?; data jsonb; idempotency_key varchar(128); request_hash char(64)` | PK `(issue_id,sequence)`；请求键 UK；每 Run 一条 RUN；COMMENT/INSTRUCTION 正文，事件使用 typed data |
| `project_issue_work` | `issue_id uuid PK/FK; wake_version bigint; due_at; lease_token varchar(128)?; lease_until?` | 每 Issue 一个 durable mailbox；合并唤醒、续租、旧 claim 围栏 |
| `project_issue_evidence` | `issue_id uuid FK; blob_id uuid FK; actor_agent_name varchar(64)?; run_id uuid?; name varchar(512)` | PK `(issue_id,blob_id)`；来源 Run 必须同 Issue；独立 Blob retain |

阶段预算在首次自动执行接受时授权，修改在 Issue 锁下进行，无独立 version。
Run 的 Agent 从稳定 Thread 绑定解析，不重复保存 agent_name；Project 从 Issue
解析，不在每张子表重复 project_id。绑定不可重绑，已有 Run 引用的 Agent/Thread
及其 Session 不能绕过业务清理硬删。

Run 状态为 RUNNING/WAITING/COMPLETED/FAILED/CANCELLED/UNKNOWN。活动 Run 无终态区间；
终态需 end_entry_id、ended_at，FAILED/UNKNOWN 需原因。RUNNING 才有 active_since，
WAITING 停止活动计时。FK 校验同 Session，不足以证明父链先后，后者由 Runtime 验证。
Run.version 是内部回调/收尾 CAS，不是另一个用户配置版本。

RUN 活动只引用 Run 且由 SYSTEM 生成，无 body/data 副本；COMMENT/INSTRUCTION 使用正文，
INSTRUCTION 必须关联当前 Run；SPEC_CHANGE/STATE_CHANGE/CONTROL 的 data 采用封闭结构，
UI 派生展示文本。actor_type=AGENT 才需要 actor_agent_name，其他类型为空。
Evidence 的 Agent 作者非空表示 Agent 发布；来源 Run 若非空则必须有 Agent 作者。
人工发布两者为空；来自 Chat 的 Agent 发布可以没有 Issue Run。

主要索引：未归档 Issue 看板、Run 的 `(issue_id,state,ordinal)` 预算查询、Run Session/
Thread 反查、Activity 请求键与 RUN 部分唯一、Evidence Blob 反查、Work due/lease。
workflow 内的状态合法性不由 FK 保证；不为查 JSON 补造状态表或引用登记表。

### 6.3 Chat 与 Harness 变更

`chat_session(session_id uuid PK/FK, chat_id uuid FK, created_at)` 保留 Chat 的多 Session
能力；按 `(chat_id,created_at,session_id)` 查询。现有 Chat 增加可空 `archived_at`，
默认列表只显示未归档，可查询并恢复归档；保留 Chat.version，不另增归档布尔值或状态表。

根 Session 只能在产品接受事务中新建并绑定，禁止挂接已有 Session 或跨产品共享。
Chat 创建复用 Harness NEW_SESSION：调用方在首次请求前固定 sessionId/root threadId
与初始参数，重试沿用；以 threadId 查找、creationRequestHash 比较完成精确重放，
并复验 chat_session 仍指向原 Chat，不另加产品幂等列。新建路径验证身份未被使用；
已存在但非同请求/同产品的身份拒绝。Issue 创建则同时受 `(issue_id,agent_name)` 唯一键
约束。这些受控创建规则保证产品及 Agent 间的 Session 隔离，数据库没有跨关联表的
排他约束；并发创建、重放和深删除须有应用测试。

Harness 的数据库变更仅为：

- Thread 增加 `(session_id,id)` UK，支持同 Session 复合 FK。
- ToolInvocation 新增 WAITING_INPUT 状态及形状约束：有 binding，无 result/error。
- ToolInvocation 增加可空 `input_receipt jsonb`；仅已接受回答/拒答的 SUCCEEDED 调用携带，
  与 result 同存，结果物化时迁入 Entry 的 Runtime 元数据。权限审批继续使用原 approval。
- 更新非终态索引；增加按等待状态筛选的 `(created_at,id)` 部分索引，支持待处理分页。
- 交互回执和审批历史使用 Entry JSON 的运行时元数据，不增加专用交互表。

不将 Project ID、Issue ID 或通用 owner 字段写入 Harness。应用可按当前实际查询需求为
历史元数据增加表达式索引，但不能把 Entry 投影复制成永久 Invocation 或另一套历史表。

## 7. 事务、恢复与生命周期

### 7.1 锁与原子边界

| 路径 | 顺序/提交内容 |
| --- | --- |
| Canvas 编辑/发布 | Document → Node/Run → Resource → Blob；去重与业务修改同事务 |
| Project 常规动作 | Project SHARE → Issue UPDATE → 已有预算/Run/绑定 → Harness Session/Thread → Work |
| 工作流发布/项目归档 | Project UPDATE；不等待外部 I/O |
| Chat 接受/删除 | Chat 锁 → Session/Thread → 资源清理 |
| 用户交互提交 | 先解析产品范围，再进入相同外层锁序；不能先锁 Thread 再反锁 Issue |

多 Session/Thread/Blob 按固定 ID 顺序取锁。产品模块负责业务服务，platform 只做明确的
宿主适配和原子编排，不允许通过私有 Mapper 隐式跨域写入。

上表是已有对象的取锁顺序，不是首次创建的 INSERT 顺序。首次接受时，先持有产品锁并
完成额度检查，通过 Harness NEW_SESSION 创建 Session/ROOT/Thread、接受初始命令及
Harness Work，再写稳定 Thread 绑定、Run、Activity 和 Issue Work；起点取新建 ROOT。
已有 Thread 则锁定并冻结当前 head，再接受本次 Run 与命令。所有写入共享同一物理事务，
即时 FK 的被引用行必须先存在；Worker 只读取提交后的完整事实，不需要延迟 FK 或预创建
空会话。任一步失败整体回滚，包括初始 Harness 命令与 Work。

Work 是“重新检查当前 Issue”的 mailbox，不携带已过时的执行决定。Worker 领取后重新
读取当前 Run、阶段、Thread 和门禁；对已选 Run 的回写须验证 Run ID/version 及 claim。
`wake_version` 防止旧 Worker 完成 claim 时吞掉新唤醒。无需再复制一套 Work.runId、
业务状态和 gate generation。丢失通知由 claim 轮询恢复，事件不能代替 Work。

### 7.2 删除与归档

- Canvas：先确认所有外部执行已终结，再清理 pin、Run、Resource、Node、Group、dedup、
  Document。单节点解除挂接后，被其他运行 pin 的 Resource 仍保留。删除被引用节点先
  显式解除配置引用。
- Issue：先停止并收尾，阻止新接受；由所有 Agent Thread 解析并锁定对应 Session，
  再清理 Work、Activity、Evidence、Run、阶段预算、Thread 绑定；按 Harness 顺序清理
  相关调用、Thread、Entry、Session，并逐一释放 Session Blob 引用，最后删除 Issue。
  Project 在所有 Issue 清理后删除。
- Chat：归档不影响运行；深删除才安全停止并清理 chat_session 和对应 Session。
- 已被业务持有的 Session 不提供绕过业务清理的裸删除。Evidence 与 Session 引用各自
  释放一次，不能靠数据库 CASCADE 绕过引用计数。

UNKNOWN 未核查不能作为普通失败清理。归档不删除历史和资源；恢复归档不自动新建 Run。

## 8. 实施与验收

按能力顺序实施，每个切片包含服务、调用方、测试和文档，不先搭空壳框架：

1. 收敛领域表、Issue Agent Thread 与 Chat Session 关联、模块边界。
2. 完成 Harness WAITING_INPUT、交互回执/审批历史、统一待处理查询与 Pane。
3. 完成 Canvas 细粒度命令、持久草稿、Function SPI 与原子资源发布；迁移具体插件。
4. 完成 Project 工作流配置、阶段 Run/额度、交接、证据及通用 Pane 接入。
5. 同步唯一 Flyway 基线、API/工具与模块文档，移除旧产品入口、表、DTO、设置和死代码。

实现切换直接替换旧业务结构与入口，只维护本文的一套职责、协议与数据模型。
数据迁移/清理必须先在隔离环境验证备份恢复与转换，部署库另需授权维护窗口；
本方案不授权操作生产服务、清空已有数据库或删除外部 ComfyUI/S3 数据。

### 验收场景

| 核心路径 | 必须验证 |
| --- | --- |
| 画布编辑 | 不同节点/语义组并发成功；同组冲突保留草稿；旧 ACK 不清新输入；远端删除可另存；离线重试不覆盖新内容 |
| Function | 文本/图片/音频/视频、多输出原子发布；输入 pin；有 pin 不可删；物化崩溃恢复；旧租约/重复 start 不重复发布/收费 |
| 插件边界 | 无模型裁剪可执行；ComfyUI 走统一 Runtime；缺插件可读原内容；活跃任务/UNKNOWN 不被移除或重提 |
| Thread/阶段 | Issue+Agent 唯一 Thread；同 Agent 跨阶段续接并刷新职责/Environment；换 Agent 不改历史/预算，换回续接原 Thread；跨 Issue/Session 引用拒绝 |
| 交互 | 重启恢复问卷/审批；多题与自定义输入；重复提交/取消竞争；终态清理后回执可查；历史合成结果不能冒充回答 |
| 门禁 | 问卷不能授权工具；YOLO 不替用户回答；暂停期间记录答案但不派发；最后一次额度等待可恢复 |
| Project | 配置严格校验；有活动 Run 不改职责；交接崩溃续收尾；失败与暂停原子；旧回调不可推进返工阶段；普通评论不自激 |
| 生命周期 | Chat 创建丢响应可精确重放，归档无隐式 Stop；Project 归档前收尾；引用计数守恒；UNKNOWN 核查与安全删除 |

关键实现用 JaCoCo 等度量核心路径覆盖率，目标 ≥90%，分支覆盖率作为参考。浏览器用
双客户端、断网/重连和存储失败场景验收，而不是仅测试 reducer 的正常返回值。
端到端入口和开关沿用[开发与测试](operations/development-and-testing.md)，不默认触发
真实收费模型。

[唯一基线库表检查](../scripts/dev/verify/repository/check-canvas-project-schema.py)只使用本机
已有 `postgres:17.10`，在它自己拥有的无网络、无宿主端口临时容器里加载唯一生产基线
[`V1__schema.sql`](../schema/src/main/resources/db/migration/V1__schema.sql)，不读取部署数据库
连接变量，也不构建镜像。验证器校验 37 张业务表与 16 张目标表的列、外键和索引，并运行与 Java
契约测试共用的至少 135 条正负 SQL 探针；负例在目标表上制造缺外键、缺索引、缺字段和多余
对象，要求验证器精确报出该对象，证明门禁不会静默放过结构漂移。SQL 断言只验证
FK/UK/CHECK/索引与数据形状，关键拒绝校验具体约束名；不证明应用状态机、权限或浏览器行为已
实现，应用级校验必须沉淀为独立自动化测试。

```bash
python3 scripts/dev/verify/repository/check-canvas-project-schema.py
node scripts/dev/verify/repository/check.mjs
```
