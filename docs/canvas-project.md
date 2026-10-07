# Canvas、Project 与交互

Canvas 用节点组织内容，通过显式运行 Function 产生资源；Project 用 Issue 组织工作，
通过配置化阶段分配 Agent、控制额度并跟踪交付。两者并列存在，各自拥有领域数据；
Project 使用 Harness 的 Agent 执行与人机交互，媒体内容使用 Storage Blob。

```text
Canvas：节点 + 资源引用 + Function -> 发布节点输出
Project：workflow + Issue         -> 阶段、分工、执行与交付
Harness：Thread + Invocation      -> Agent Loop、审批和问答

Issue + Agent -> 稳定 Thread      # 工作上下文
Issue + state -> 阶段预算          # 新 Run 授权
Run           -> 一次执行记录      # 冻结身份与历史区间
```

本文按使用顺序说明编辑、运行和交接，再给出数据关系与生命周期。全局依赖与执行模型见
[系统设计](system-design.md)；具体领域契约见 [Canvas Core](modules/canvas-core.md)、
[Canvas Infra](modules/canvas-infra.md)和 [Project](modules/project.md)。

## Canvas：组织内容与引用

一个 Canvas 包含资源节点及视觉分组。节点保存名称、资源数组、可选 Function、位置和尺寸：

```text
ResourceNode
├── id / name
├── resources: Resource[]
├── function?: { name, args }
└── position / size / group

Resource: TEXT | IMAGE | VIDEO | AUDIO
```

TEXT 使用内联正文，媒体引用不可变 Blob；每条 Resource 恰好选择一种内容来源。节点
资源数组保持内容分支一致：全为内联文本或全为媒体。编辑文字或替换媒体创建新 Resource，
复制媒体时新建 Resource 并共享 Blob 字节。Resource 由节点挂接或 Function pin 保护，
解除挂接且无 pin 后可回收。

节点名称按 NFKC、去首尾空格和小写得到唯一键，要求画布内非空且唯一，显示名可保留
大小写。引用使用 UUID，重命名保持绑定。分组是单层视觉组织。

### Function 配置与引用

Function 保存为 `{name,args}`，具体函数声明参数 Schema、输入限制和有界输出计划
（1..8 个槽位）。模型、prompt 等参数由具体函数决定。例如裁剪函数：

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

`{"type":"resource","nodeId":"...","index":0}` 是核心资源引用值，可出现在 args 的
任意嵌套位置。连线由配置投影，插件未安装时仍可识别引用。`index` 从零开始，指向源
节点当前输出位置；运行接受时解析为实际 Resource 并冻结。

- 保存引用时校验同画布目标；尚无输出的位置可保存为显式失效引用，启动前要求完整解析并校验类型。
- 位置失效需用户修正；删除被引用节点需显式解除引用，可以放入同一原子编辑批。
- 配置大小、引用数和遍历深度均有上限。
- 上游输出更新后，由用户显式启动下游 Function。

### 编辑、冲突与草稿

服务端接受 typed commands。`canvas_document.revision` 用于同步排序、补漏和确认
接受位置；编辑前置条件按语义组提供：

| 编辑内容 | 前置条件 |
| --- | --- |
| 名称 | 打开重命名菜单时冻结的旧名称 |
| 资源数组 | 编辑起点的有序 Resource ID 列表 |
| Function | 编辑起点的完整 `{name,args}`，按 JSON 语义比较 |
| 删除节点 | 当前内容、配置与引用关系满足删除前提 |
| 布局 | 在线按接受顺序收敛；重连积压先比较布局基线 |

多命令批全部验证后原子提交，冲突返回受影响对象和服务端值。不同节点及不同语义组可
独立编辑；Function 参数整体比较，以保留参数之间的约束。数据库短事务排序提交，
上传和函数调用在事务外执行。命令类型与冲突载荷见
[Canvas Core](modules/canvas-core.md#typed-command-与并发语义)。

浏览器按三层状态展示：

```text
服务端已确认快照 + 待确认操作 + 编辑草稿 = 当前展示
```

草稿记录编辑基线与 generation。远端更新只推进确认层，ACK 只清除对应操作和
generation；dirty 输入、编辑器和选择保留。请求发送后冻结 ID、参数与前置条件，
网络重试沿用原请求；解决冲突时创建新请求。去重记录与业务修改同事务提交，
精确重试返回原接受回执。

IndexedDB 按 `userId + canvasId + editingSessionId` 保存草稿与待确认操作，当前
`userId` 为 `anonymous`；标签页使用独立编辑会话，复制标签页会重新分配身份。
刷新、路由切换和重连后恢复本地状态并与 Snapshot 对照。只有 IndexedDB 事务完成才
算落盘，失败时显示告警；跨刷新恢复还要求 sessionStorage 成功保存编辑会话。
存储不可用时使用页内状态，清除站点数据会删除本地草稿。

冲突时同时保留草稿与远端事实，用户选择以远端基线重试、另存新节点或放弃草稿。
目标已被删除时支持另存新 ID 或放弃。revision 有缺口时回读 Snapshot，并保留未保存
输入。浏览器同步与存储细节见 [Frontend](modules/frontend.md)。

## Function：从启动到输出发布

Function 由构建期 Plugin 提供定义、校验、执行与取消能力。Canvas Runtime 管理冻结
输入、pin、租约、恢复、发布和清理；adapter 通过受控上下文读取输入并物化输出。
当前 Plugin 包括 `canvas-media` 与 `canvas-comfyui`，由发行物依赖选择并自动装配。
ComfyUI 的模板随 Plugin 发布，外部引擎执行任务，adapter 负责提交、查询、取消和下载。

插件缺失时既有配置与输出仍可读取；启动该函数要求 adapter 可用。参数面板用完整
JSON 保留未知配置，用户可清除或选择已安装函数。

```text
启动事务：校验请求、配置与输入
          冻结 Function、实际 Resource 和输出计划
          pin 输入，写 READY Run
  -> Worker claim + heartbeat
  -> 持久记录提交意图，事务外执行 adapter
  -> checkpoint 外部任务身份与进度
  -> 物化事务：创建 Resource + OUTPUT pin
  -> 成功事务：复验 requestId、lease 和输出完整性
               挂接完整 Resource[]，Run=SUCCEEDED
               释放本次 pin，回收无引用 Resource，推进 revision
```

每个节点保存当前或最后一次 Run。READY、RUNNING 或待核查 UNKNOWN 期间，
新启动请求、手工替换输出和删除节点被拒绝；配置可继续编辑，供下一次运行使用。
失败或取消保留上次成功输出；成功发布要求完整的有序输出数组。

输入冻结后独立于源节点当前挂接关系。输出 ID 先记录在冻结计划中，Resource 与
OUTPUT pin 在物化时同事务创建。Resource 行持有 Blob 引用，pin 负责保护 Resource
生命周期；成功、确定失败或取消后释放 pin，Storage GC 在事务外回收最终无引用字节。

READY 表示下一步可调度，RUNNING 表示 Worker 持有租约。当前 adapter 在 Worker 中
同步等待或轮询，heartbeat 续租并占用固定并发槽位；恢复依据 checkpoint 查询已有任务。
Function 状态为 READY、RUNNING、SUCCEEDED、FAILED、CANCELLED、UNKNOWN。

### 结果不明时的核查

外部提交前先持久化提交意图。提交结果不明且缺少安全查询或幂等依据时，Run 进入
UNKNOWN，保留必要 pin 并退出自动调度。人工核查记录依据后，选择：

- `RESUME`：确认原任务身份，回到 READY，继续查询原任务。
- `FAILED` 或 `CANCELLED`：确认收尾结果，释放 pin 并清理无 owner 的目标资源。

UNKNOWN 的取消与删除要求先完成核实。租约围栏防止旧 Worker 发布结果，外部是否执行
由查询或人工核查确认；取消请求的接受与外部取消成功分别判断。持久执行契约见
[Canvas Infra](modules/canvas-infra.md)。

## Project：配置阶段与 Agent 分工

Project 保存标题、描述、workflow 和首次创建 Thread 使用的 YOLO 策略。Issue 保存
需求、当前阶段、控制门禁及活动时间线。创建 Project 后，先配置工作阶段，再创建 Issue
并通过合法动作推进。

### Workflow 配置

`project.workflow` 是严格 JSON。以下流程由设计和检查两个 Agent 阶段组成：

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

数组顺序决定展示顺序，`next` 声明正常转移白名单。状态编码匹配
`[A-Z][A-Z0-9_]{0,63}`，项目内唯一。INIT、BLOCKED、DONE 是必备保留态，其余为工作
阶段，数量由项目配置决定。工作阶段可配置 `agent`、`environment`、`instructions`、
`maxRuns` 和 `enabled`（默认 true）。绑定 Agent 要求正数 maxRuns；人工阶段省略
Agent、Environment 与 Run 额度，由人工显式推进。

默认流程为 INIT→WORK→DONE，另含 BLOCKED，WORK 是人工阶段。业务阻塞与恢复使用
专用操作；DONE 显式重开回 INIT。流程允许返工环，拒绝自身边。启用工作阶段须从
INIT 可达，INIT 须存在到 DONE 的正常路径。

workflow 整体保存时锁定 Project、检查版本，并要求项目无活动 Run。保存校验编码、
合法边、可达性与引用；包含已归档 Issue 在内的当前阶段及阻塞恢复点都须保留。
工作流编辑读取 Project Snapshot 的 `referencedStateCodes` 实施相同引用保护，
不依赖仅包含未归档 Issue 的可见看板列表。后台推送刷新约束而不覆盖打开中的草稿。
Agent 与阶段 Environment 由 Project 在执行接受时以显式命令提交并冻结，缺失引用明确拒绝；
live turn 不再按 workflow 反查。
合法动作由当前状态、workflow、Run 与门禁共同决定，见 [Project](modules/project.md)。

### Thread、阶段预算与 Run

```text
Issue A
  +-- designer -> Thread D -> Run 1(DESIGN), Run 3(REWORK)
  +-- reviewer -> Thread R -> Run 2(REVIEW), Run 4(REVIEW)
```

`(issueId, agentName)` 在首次接受执行时创建稳定 Thread 绑定；同 Agent 跨阶段、
返工与重开复用该 Thread。每个绑定拥有独立 Harness Session，提供历史与附件授权
范围；Thread 解析 Session，Run 冻结 Session/Thread 和历史区间。

阶段职责与上下文的刷新由业务在每次新 Run 接受时显式提交，运行中的 turn 不复查 Issue 状态；
阶段 Environment 未配置时本轮不选择环境。当前请求上下文来自冻结的 `ProjectRunScope`，
历史 BranchSettings 保持其原有记录。Issue Thread 的输入、Stop、配置与派发经过 Issue 工作流
编排；公共 batch 创建面拒绝 `ISSUE_AGENT` owner。Project YOLO 只在首次创建绑定 Thread 时写入，
已有 Thread 保持自己的策略。

阶段额度按 `(issueId,state)` 授权：

```text
used = COUNT(Run WHERE issue_id = ? AND state = ?
                   AND ordinal > budget_after_ordinal)
remaining = max(0, max_runs - used)
```

Run ordinal 在 Issue 内单调分配。新 Run 的失败、取消和 UNKNOWN 都消耗一次额度；
恢复已接受 Run、工具循环、协议续生成和问答等待沿用原次数。更换 Agent、返工或重开
仍使用同一阶段预算。workflow 默认额度用于首次授权，既有额度由明确授权或重置操作维护。

人工重置要求无活动 Run，高水位取服务端 `next_run_ordinal - 1`，前后额度与高水位写入 CONTROL
活动。used/remaining 在读取时计算，Agent 通过受控执行使用额度。已接受 Run 还受
持久活动时长余额与模型、工具安全上限约束，安全等待暂停活动计时。

### 业务状态与控制门禁

| 事实 | 表达方式 | 用户操作 |
| --- | --- | --- |
| 当前工作阶段 | `Issue.state` | 沿合法边转移，DONE 可显式重开 |
| 业务阻塞 | BLOCKED、原阶段和原因 | 阻塞后显式恢复到原阶段 |
| 人工暂停或执行失败 | USER / ERROR 暂停原因 | 检查后显式恢复 |
| 副作用结果不明 | UNKNOWN Run 与暂停原因 | 记录核查依据，再显式恢复 |
| 工具等待输入或审批 | Harness ToolInvocation | 在 Pane 或待处理入口回答/决定 |
| 新 Run 额度耗尽 | 阶段预算与 Run 历史计算 | 人工授权或重置 |

同一 Issue 只有一个 RUNNING/WAITING 主 Run。门禁关闭后停止新派发，在途调用按原 Run
身份收尾，到安全点才置 WAITING。回答问卷后，业务 BLOCKED 或人工暂停仍需各自恢复。
输入等待恢复沿用原 Run，即使新 Run 额度已经用尽也可继续。

人工 Stop 收尾为 CANCELLED 并保留 USER 暂停；在途结果不明时进入 UNKNOWN。
UNKNOWN 核查记录提交后转为 USER 暂停，执行需再显式恢复。直接换阶段和归档要求无
活动 Run，归档后的产品拒绝执行。Chat 归档用于列表收纳，正在执行的 Chat 保持运行。

### 接受、交接与报告

Run 接受在同一物理事务内完成状态和额度检查、Thread 选择或创建、起点冻结、显式
Agent/Model/Environment/contributor state 命令与任务输入提交、Run 与 RUN 活动插入、
Harness 命令接受及 Work 登记。首次创建先写 Harness Session、
ROOT 和 Thread，再写产品绑定与 Run，满足即时外键；失败时全部回滚。

进入有 Agent 的阶段登记 Work。Agent 正常结束且未交接时，Run 终结并保持当前阶段；
再次执行需要接受新 Run 并检查额度。额度耗尽时等待人工授权。

Agent 使用 `issue_transition`（参数 `to_state`）请求交接，目标先写入 Run.next_state，
工具返回“已接受，收尾后生效”。随后派发仅允许模型收尾与冻结 descriptor 声明的只读
工具；用户输入继续受控接受，处理完后才能交接。

```text
接受 next_state
  -> 处理剩余输入、问答与在途调用，到安全点
  -> root Join 冻结 terminalEntryId / finalAnswerEntryId
  -> 事务复验 Run ID/version、历史区间、合法边与门禁
  -> 冻结报告，Run=COMPLETED，写 active=false scope
  -> 更新 Issue.state，记录活动，发布证据，请求后继 Work
```

暂停时保留原 Run 的交接目标，恢复后继续收尾；失败或取消终结该 Run。旧 Worker、
旧 Run 和迟到 callback 通过执行身份与版本围栏拒绝。崩溃恢复继续原 Run，沿用原额度。

Run 区间为 `(start_entry_id,end_entry_id]`，沿 Entry 父链解释；最终回答必须是区间内
真实可见 Entry（取自 root Join 冻结的 `finalAnswerEntryId`），缺少报告时展示终态原因。
Issue 的要求由需求和阶段定义，Agent 交接走 `issue_transition`；Issue Run 的任务输入是
可信 `CUSTOM_MESSAGE`，其 owner 命令不出现在公共 batch 端点。

## 活动、证据与人工交互

### Issue 时间线与资源授权

Activity 记录 COMMENT、RUN、INSTRUCTION、SPEC_CHANGE、STATE_CHANGE 和 CONTROL。
RUN 活动引用 Run，状态与报告按引用读取；评论记录作者，定向 INSTRUCTION 只投递给
明确的当前 Run。普通评论用于时间线沟通，要影响当前执行时提交定向指令。
指令接受与处理游标推进同事务完成。成功写操作以请求键和规范化
指纹去重，精确重试返回原事实。

Evidence 独立持有 Blob，`(issueId,blobId)` 唯一，首次发布时 retain。最终报告中的合法
受管资源在收尾时发布，也支持人工上传；来源 Session 删除后 Evidence 仍保留。
文本文件交付需先物化为 Blob。公开证据展示与 Agent Session 的读取授权分别校验，
未授权资源访问拒绝。

Agent 从 Run 接受时冻结的 `ProjectRunScope` 收到本次 Issue、阶段职责、合法后继与
Environment，不按 live turn 重读；当前 Project 工具目录提供 `issue_transition`（作用域写）。
UI 从 Activity 和证据展示交接材料。Agent 的 `read` 按当前 Session Blob 引用授权，资源
URI 只用于定位。

### 问答、审批与待处理入口

Chat 和 Issue 复用 Harness ToolInvocation 的持久等待。`ask_user` 接受时冻结问卷：

```json
{
  "questions": [
    {
      "question": "交付格式？",
      "options": [
        {"label": "PDF", "recommended": true},
        {"label": "Markdown"}
      ]
    },
    {
      "question": "需要哪些附件？",
      "multiple": true,
      "options": [{"label": "图片"}, {"label": "源文件"}]
    }
  ]
}
```

答案按问题位置提交，例如 `{"answers":[["PDF"],["图片","源文件"]]}`。单选默认，
每题提供自定义输入；推荐仅做标记，等待用户选择。答案须覆盖所有题，单选恰好一个，
多选去重并允许一项自定义文本；题数、选项数与长度有界。模型只提供问题与选项，
问卷由接受路径冻结。同题选项标签唯一，单选至多一个推荐，多选允许多个；
选项为空时使用自定义回答。

用户在 Pane 卡片明确提交回答或拒答。拒答形成 `{"declined":true}` 工具结果；Stop
取消等待，迟到回复被拒绝。委派子 Thread 也可等待回答，祖先按自身状态与执行树
对账恢复，不递归投影子树等待。

| Invocation 状态 | 展示与接受 |
| --- | --- |
| `WAITING_INPUT` | 冻结问卷卡片；校验回答结构与提交身份后写入 ToolResult |
| `WAITING_APPROVAL` | 真实冻结调用的审批卡片；允许后回 READY，拒绝后返回失败工具结果 |

全局待处理列表、Pane 和角标读取相同 Invocation 事实，按产品范围校验访问并分页。
模型输出完成后，工具仍可处于等待状态。等待释放 Worker、外部 Handle 和长连接；
人工决定与后续 Work 同事务提交。审批允许只授予调用资格，执行结果由工具实际返回。

回答回执保存 submissionId、actor 与 acceptedAt。终态结果追加到 Entry 后，在同一事务
删除 Invocation，并把 invocationId、调用坐标与回执转入 Runtime 历史元数据。
清理后重试沿历史父链核验原 Thread；相同提交返回原接受事实，同键异内容、冲突提交和
已取消目标被拒绝。问卷答案保存在结果内容，审批审计保存在 Runtime 元数据。

暂停时可记录人工决定，后续派发仍受暂停、归档、Run 身份与授权约束。执行恢复依赖
持久 Work；页面通过重连、回读和待处理轮询恢复。详细问卷、审批与历史幂等契约见
[Harness Runtime](modules/harness-runtime.md)和 [Platform](modules/platform.md)。

## 数据关系与生命周期

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

Canvas Resource 与 pin 使用同画布外键，Run 固定 requestId 与 lease；Project Run 通过
复合外键关联同 Issue 的 Thread 和同阶段预算，并绑定同 Session 的 Thread/Entry。
父链区间、权限、workflow 合法性和输出完整性由应用验证。字段、索引与数据库形状以
[`V1__schema.sql`](../schema/src/main/resources/db/migration/V1__schema.sql)为准，
关系说明与维护入口见 [Schema](modules/schema.md)。

### 事务与恢复

Canvas 编辑与发布按 Document→Node/Run→Resource→Blob 取锁。Project 动作先锁
Project 和 Issue，再锁已有预算、Run、绑定及 Harness Session/Thread，最后登记 Work；
人工交互先解析产品范围，沿相同外层锁序进入。多 Session、Thread 和 Blob 按固定 ID
顺序取锁。跨域原子写入由宿主端口编排，外部 I/O 留在事务外。

Issue Work 是“重新检查当前 Issue”的 durable mailbox。Worker 领取后重读阶段、门禁、
Thread 与 Run，回写复验 claim 和 Run 坐标。`wake_version` 在请求唤醒时递增，
防止旧 claim 完成时吞掉新唤醒；轮询发现遗漏通知。调度与收尾实现见
[Project](modules/project.md)，Canvas Worker 见 [Canvas Infra](modules/canvas-infra.md)。

### 归档与删除

- **Canvas**：READY/RUNNING/UNKNOWN Run 阻止深删除。允许删除后，按 pin、Run、
  Resource、Node、Group、dedup、Document 清理。解除挂接后，被其他运行 pin 的资源继续保留。
- **Issue / Project**：活动 Run 或未解除 UNKNOWN 阻止深删除。允许后清理 Evidence、
  Activity、Work、Run 与阶段预算，再深删除各 Agent Session 及稳定绑定，最后 CAS
  删除 Issue；Project 在全部 Issue 清理后删除。
- **Chat**：归档保留执行与历史，深删除安全停止并清理关联 Session。
- **Blob**：业务删除显式释放各自引用。Session 引用与 Evidence 引用独立计数，
  对象字节由 Storage GC 清理。

归档保留历史和资源，恢复归档后由明确执行操作启动工作。UNKNOWN 的清理以核查
事实为前提。生产数据操作需审批并备份，运行入口见
[部署与运行](operations/deployment.md)。

## 开发与验证入口

修改 Canvas 命令和 Function 契约先读 [Canvas Core](modules/canvas-core.md)，数据库
发布与 Worker 读 [Canvas Infra](modules/canvas-infra.md)；修改阶段、预算和交接读
[Project](modules/project.md)，产品宿主编排读 [Platform](modules/platform.md)；
浏览器草稿与问答卡片读 [Frontend](modules/frontend.md)。

数据库结构检查使用本机已有 PostgreSQL 镜像，在隔离临时容器中加载生产基线并验证
表、外键、索引与 SQL 探针；应用状态机、授权和浏览器行为由对应模块自动化测试覆盖。
端到端选择与全部质量入口见 [开发与测试](operations/development-and-testing.md)。

```bash
python3 scripts/dev/verify/repository/check-canvas-project-schema.py
node scripts/dev/verify/repository/check.mjs
```

验证脚本说明见
[基线库表检查](../scripts/dev/verify/repository/check-canvas-project-schema.py)。
