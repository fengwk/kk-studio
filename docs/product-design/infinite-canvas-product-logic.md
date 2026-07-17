# 无限画布产品逻辑

> 产品定位、页面结构、交互原型和视觉规范见 [`infinite-canvas-prototype.md`](infinite-canvas-prototype.md)。工程模块、存储、协议和运行时见 [`infinite-canvas-implementation-design.md`](../technical-solution/infinite-canvas-implementation-design.md)。本文是无限画布与 Workflow 的产品逻辑事实源。

## 1. 产品结论

无限画布是人和总控 Agent 共同使用的资源工作台，不是 Workflow 编辑器本身。产品以以下关系为地基：

```text
Resource 是数据
Function 是行为
Node 是 Canvas 中的资源与能力载体
Link 决定上游资源对谁可见
@ResourceReference 表达节点实际使用了哪个资源
Workflow 将多个 Function 固化为新的 Function
```

完整闭环：

```text
资源进入 Canvas
-> 用户或 Agent 组织 Node 与 Link
-> 下游通过 @ 引用当前可见资源
-> FunctionNode 执行并发布新 Resource
-> 资源变化沿实际引用依赖向后传播
-> 稳定子过程在 Workflow Canvas 中固化
-> Workflow 发布为 Function 后回到 Canvas 复用
```

核心公式：

```text
Canvas = Nodes + Links + ResourceReferences

Node -> 0..N current Resources

Function(Resource Inputs, Config) -> Resource Outputs

Workflow(Function[]) -> Function
```

## 2. 核心概念

### 2.1 Node

Canvas 上所有可寻址、可选择、可移动、可连接的对象都是 Node。Node 统一具备身份、布局、层级和资源暴露能力。

首期只有三类核心 Node：

| Node | 职责 | 资源来源 |
| --- | --- | --- |
| ResourceNode | 承载上传、粘贴、编辑或提取出的具体内容 | 用户、导入器或其他 Node 的提取结果 |
| FunctionNode | 引用一个 Function，绑定输入并执行 | Function Run 成功输出 |
| GroupNode | 组织成员并聚合成员当前资源 | 成员 Resource 的递归聚合 |

文本、图片、视频、音频、文档和结构化数据是 ResourceNode 的不同视图，不再分别形成互不相干的顶层执行模型。图片生成、视频生成、Agent 和 Workflow 都是 FunctionNode 的不同 Function 实现。

### 2.2 Resource

Resource 是 Node 当前对外提供的实际数据。没有产生实际数据时，Node 的资源列表为空；系统不创建假的 `EmptyResource`。

Resource 具备稳定逻辑身份，内容变化通过不可变 ResourceVersion 表达：

```text
Resource
├── 稳定 id
├── 所属 Node
├── output channel / item key
├── kind
├── displayName
└── currentVersionId

ResourceVersion
├── 不可变 id
├── version
├── payloadRef
├── metadata
├── contentHash
└── producedByRunId
```

普通 `@ResourceReference` 跟随 Resource 的当前版本；一次执行开始后冻结具体 ResourceVersion。

### 2.3 Link

Link 是两个 Canvas Node 之间的有向可见关系：

```text
Source Node -> Target Node
```

它只表示 Target 可以看到 Source 当前对外提供的 Resource。Link 可以在 Source 尚无 Resource 时提前建立。

### 2.4 ResourceReference

Target 在 Prompt、参数、输入槽或结构化配置中选择一个已经存在的可见 Resource 后，形成 ResourceReference：

```text
Link       = 可以看到谁
@Reference = 实际使用了谁
```

只有 ResourceReference 才形成执行依赖和变化传播。仅有 Link、没有实际引用时，上游变化不会使下游失效。

### 2.5 Function

Function 是稳定、可注册、可执行的资源变换定义：

```text
Function
  named Resource Inputs
  + Config
  -> named Resource Outputs
```

Function 来源包括：

- 系统基础 Function，例如图片生成、视频生成、OCR、格式转换和校验。
- Workflow Function，由 Workflow Definition 发布得到。
- Agent Function，通过低耦合 Agent 执行端口接入。

### 2.6 Workflow

Workflow 是独立建模、独立编辑、独立版本化的 Function 组合程序。Workflow Canvas 中的 Binding 表示未来 Step 输出，不使用 Canvas Link 或 `@ResourceReference` 语义。

发布后的 Workflow Version 作为 Function 出现在 Function Library 中，Canvas 通过普通 FunctionNode 引用它。

## 3. 状态归属

| 状态层 | 内容 | 持久化 | 协作语义 |
| --- | --- | --- | --- |
| CanvasDocument | Node、Link、ResourceReference、Group 层级、布局、revision | 服务端 | Workspace 内共享 |
| Resource | 稳定身份、当前版本、历史版本、Payload、来源 | 服务端与对象存储 | 按 Workspace 权限共享 |
| Function Catalog | 系统 Function、Workflow Function、Function 签名 | 服务端或代码注册 | Workspace 可发现 |
| Execution | FunctionRun、输入快照、输出、成本、日志和错误 | 服务端 | 多端可观察 |
| Workflow | Draft、Version、Step、Binding、策略和测试结果 | 服务端 | Workspace 内共享 |
| 用户视图 | viewport、个人面板和显示偏好 | 用户级 | 不覆盖他人视图 |
| 临时交互 | 选区、拖拽、框选、打开的浮层、IME 状态 | 当前前端会话 | 不进入共享文档 |

关键边界：

- 当前选区和实时 viewport 不是 CanvasDocument 内容。
- Link 和 ResourceReference 是不同事实，不能从视觉连线直接推断执行依赖。
- ResourceVersion 不因重命名、移动或样式变化而创建。
- FunctionRun 不依赖 FunctionNode 组件存活。
- Workflow Definition 不保存某张 Canvas 的具体 Node ID。
- Agent Session、Run、Tool 等内部结构不进入 Canvas 领域模型。

## 4. CanvasDocument 与 Node

### 4.1 CanvasDocument

```ts
interface CanvasDocument {
  id: string
  workspaceId: string
  title: string
  schemaVersion: number
  revision: number
  lifecycle: 'draft' | 'active' | 'archived'
  homeViewport?: Viewport
}
```

规则：

1. `revision` 在每个成功 Canvas Command 后单调递增。
2. 所有修改携带 `baseRevision`，冲突时不能静默覆盖。
3. 文档保存公共首页视图；每个用户的实时 viewport 独立保存。
4. `archived` 文档只读，恢复为 `active` 后才允许修改。
5. 归档时存在 active FunctionRun 默认拒绝；用户先取消或等待终态，归档后的晚到输出不得发布。

### 4.2 CanvasNode

```ts
interface CanvasNode {
  id: string
  canvasId: string
  kind: 'resource' | 'function' | 'group'
  nodeType: string
  nodeTypeVersion: number
  revision: number
  name: string
  parentGroupId?: string
  transform: {
    x: number
    y: number
    width: number
    height: number
    rotation?: number
  }
  zIndex: number
  locked: boolean
  hidden: boolean
  validity: 'empty' | 'current' | 'stale' | 'broken'
  data: unknown
}
```

通用规则：

1. Node ID 创建后永久稳定。
2. `kind` 和 `nodeType` 创建后不可原地转换；NodeDefinition 只允许迁移 `nodeTypeVersion`，跨类型转换通过创建新 Node 并迁移 ResourceReference 完成。
3. 世界坐标使用有限数值，宽高必须大于零。
4. 锁定 Node 不能被用户普通编辑或 Agent Command 修改。
5. 隐藏 Node 不进入默认资源选择和 Agent 自动上下文，但不破坏已有显式 ResourceReference；隐藏属于展示状态，不改变 Group 聚合语义。
6. Node 可以在没有 Resource 时建立 Link。
7. Node 是否能被 `@` 引用取决于是否存在可用 Resource，而不是是否存在 Link。

### 4.3 ResourceNode

ResourceNode 保存资源编辑方式和展示配置：

```ts
interface ResourceNodeData {
  editorKind: 'text' | 'image' | 'video' | 'audio' | 'document' | 'structured'
  primaryResourceId?: string
  presentation: Record<string, unknown>
}
```

典型来源：

- 上传图片形成 Image ResourceNode。
- 上传文档形成 Document ResourceNode。
- 粘贴文本形成 Text ResourceNode。
- 从 FunctionNode 输出中“提取为独立节点”形成新的 ResourceNode。

ResourceNode 内容编辑成功后发布新的 ResourceVersion，并使所有实际引用它的后代失效。

### 4.4 FunctionNode

```ts
interface FunctionNodeData {
  functionRef: {
    functionId: string
    version: string
  }
  config: Record<string, unknown>
  configRevision: number
}
```

输入绑定来自 CanvasResourceReference，输出来自 Node 所属 Resource，运行态来自 FunctionRun；三者不在 `data` 中重复保存。

规则：

1. `functionId` 创建后固定；切换到同一 Function 的新 Version 必须显式执行 `UpgradeFunctionVersion` 并通过输入、输出和 Config 迁移校验，更换 Function ID 使用“替换节点”命令。
2. 修改 Config 后递增 `configRevision`；新增、修改或删除输入引用由 ResourceReference 集合单独表达。两类语义变化都会重新计算 validity。
3. 每个 FunctionNode 同时只允许一个 active FunctionRun。
4. FunctionRun 使用提交瞬间的 Config 和 ResourceVersion 快照。
5. 执行成功后原子发布输出 ResourceVersion。
6. 执行失败或取消不清空上一次成功输出。
7. 运行期间允许继续编辑 Config；晚到结果保留在 Run 历史中，只有 Node 仍可发布、执行主体仍有权限，且 Function Version、Config revision、Reference 集合、当前 ResourceVersion 和声明参与语义的字段均与输入快照一致时才能发布为 Node 当前输出。
8. FunctionNode 可以在首次成功前与下游建立 Link；下游资源选择中不会出现尚未产生的输出。

### 4.5 GroupNode

```ts
interface GroupNodeData {
  collapsed: boolean
  layout: 'free' | 'stack' | 'grid'
  resourceMode: 'recursive-members'
}
```

Group 规则：

1. 一个 Node 最多属于一个直接父 Group。
2. Group 可以嵌套，但层级不得形成环。
3. 移动 Group 时以一个 Command 批量移动全部后代。
4. 调整 Group 大小不缩放成员内容。
5. 将 Group Link 到下游后，下游可以看到 Group 递归成员的当前有效 Resource；`@Group` 聚合包含隐藏成员，避免展示开关改变执行输入。
6. `@Group` 表示聚合成员资源集合，不复制 Payload。
7. Group 集合按稳定 Resource 地址排序，画布位置与 zIndex 不影响输入顺序；需要业务顺序时使用显式 Sequence Resource 或 Workflow。
8. Group 成员或成员 Resource 变化时，引用 Group 集合的后代进入 `stale`。
9. 删除非空 Group 默认把直接成员提升到该 Group 的父级并保持世界坐标与相对 zIndex；“连同内容删除”是单独的破坏性动作。

## 5. Resource 身份、版本与命名

### 5.1 Resource 与 ResourceVersion

```ts
interface Resource {
  id: string
  workspaceId: string
  owner: {
    type: 'canvas-node' | 'function-run'
    id: string
    canvasId?: string
  }
  channelKey: string
  itemKey: string
  kind: 'text' | 'image' | 'video' | 'audio' | 'document' | 'json' | 'bundle'
  displayName: string
  currentVersionId: string
  available: boolean
}

interface ResourceVersion {
  id: string
  resourceId: string
  version: number
  payloadRef: string
  metadata: Record<string, unknown>
  contentHash?: string
  producedByRunId?: string
  createdAt: string
}
```

`Resource.id` 是逻辑地址，`ResourceVersion.id` 是某次不可变内容。移动 Node、修改显示名和改变画布样式不会创建 ResourceVersion。

Resource 有两种所有者：

- `canvas-node`：Canvas 对外可 Link 和引用的稳定 Resource。
- `function-run`：FunctionRun 的不可变输出，供 Workflow Binding、Run 历史和最终发布使用。

Executor 总是先把成功输出保存为 Run 所属 Resource。FunctionRun 由 Canvas FunctionNode 发起且仍满足发布条件时，再复用同一 Payload 为 Node 所属 Resource 创建新版本；Workflow 中间 Step 因此不需要伪造 Canvas Node。

### 5.2 输出通道

Function 使用稳定输出通道描述结果：

```text
generateStoryboard
├── storyboardImages: Image[]
└── assetResources: Resource[]
```

Canvas Node 所属 Resource 的稳定地址由以下字段组成：

```text
ownerNodeId + channelKey + itemKey
```

单资源通道使用固定 `itemKey = value`。多资源通道优先使用 Function 返回的语义 key；没有语义 key 时 Executor 必须先规范化稳定顺序，再使用 `item-0001`、`item-0002`。同一通道内 key 重复或顺序不确定视为输出契约错误。

### 5.3 多资源

一个 Node 可以同时对外提供多个 Resource：

```text
生图 FunctionNode
└── images
    ├── item-0001
    ├── item-0002
    ├── item-0003
    └── item-0004
```

下游有三种使用方式：

1. 引用整个输出通道，例如 `@角色候选.images`。
2. 引用其中一个 item，例如 `@角色候选.images/候选02`。
3. 将某个 item 提取为独立 ResourceNode，再单独编辑和连接。

普通 Canvas 不自动把 N 个 Resource 扇出为 N 次执行。目标输入声明 `cardinality = many` 时一次消费整个集合；声明 `one` 时必须明确选择一个 item。

一次成功 Run 对已声明输出通道执行完整替换：仍存在的 `itemKey` 创建新版本，本次缺失的旧 item 标记不可用。引用整个通道的下游进入 stale；引用已消失 item 的下游进入 broken。

逐项独立执行属于 Workflow `ForEach`，不通过额外的 Canvas“打散节点”隐式触发高成本任务。

### 5.4 当前版本与历史固定

普通 `@` 引用跟随逻辑 Resource 的当前版本：

```text
@图1 -> Resource 图1 -> currentVersion
```

执行记录冻结：

```text
@图1 -> ResourceVersion v3
```

如果用户需要永久保留历史内容，应执行“提取为独立 ResourceNode”或“从历史版本创建节点”。普通引用不提供隐式历史 pin 模式。

### 5.5 命名与冲突

- Node 名称、Resource `displayName` 均允许重复。
- 机器引用始终保存 ID 和 key，不保存名称字符串。
- 单资源默认继承 Node 名称。
- 多资源默认使用“Node 名称 + 序号”或 Function 提供的语义名称。
- `@` Mention 显示名称、类型、缩略图和 Group 路径，内部保存 ResourceReference。
- 重命名不会断开引用。
- 导出到文件系统时才处理文件名冲突，并自动增加后缀。

## 6. Link 与 ResourceReference

### 6.1 Link

```ts
interface CanvasLink {
  id: string
  canvasId: string
  sourceNodeId: string
  targetNodeId: string
}
```

规则：

1. Link 可以连接空 Node。
2. Link 不复制、不转移 Resource 所有权。
3. Target NodeDefinition 必须声明至少一个可绑定 targetPath；首期 GroupNode 不作为 Link Target，避免无消费语义的连线。
4. Target 只看到直接入边 Source 的 Resource；普通 Link 不递归穿透多跳节点。
5. Group Link 展开 Group 的递归成员 Resource。
6. 创建或删除一个未被引用的 Link 不触发资源传播。
7. 同一 Source 与 Target 之间只保留一条 Link。
8. Link 不表达 Workflow 执行顺序。

### 6.2 可见资源

```text
visibleResources(target)
=
union(currentResources(source) for incomingLinks(target))
```

只返回：

- 已经存在。
- 当前可用。
- 用户有权限。
- 未隐藏。
- 与目标输入类型兼容。

首次成功前为空的 FunctionNode 不会出现在下游 `@` 资源项中；成功发布后自动进入可见列表。

### 6.3 ResourceReference

```ts
interface ResourceReference {
  id: string
  targetNodeId: string
  targetPath: string
  visibilityLinkId: string
  dependencySourceNodeId: string
  selector:
    | { type: 'resource'; resourceId: string }
    | { type: 'channel'; ownerNodeId: string; channelKey: string }
    | { type: 'group'; groupNodeId: string; mode: 'recursive-current' }
}
```

`visibilityLinkId` 表示该引用通过哪条 Link 获得可见性；`selector` 表示实际使用的数据。两者必须分开，因为通过 Group Link 可以选择 Group 中某个成员的 Resource。

- `resource` 引用一个逻辑 Resource item。
- `channel` 引用某个 Node 输出通道的当前完整集合。
- `group` 引用 Group 当前递归成员集合。
- `dependencySourceNodeId` 由服务端从 selector 推导并保存为传播索引；成员 Resource 经 Group Link 被选择时，它指向实际成员 Node，而不是 Group。

创建规则：

1. `visibilityLinkId` 必须指向 Target；Link Source 必须是 selector 所有者，或当前包含 selector 所有者的祖先 Group。
2. `resource` 与 `channel` 当前必须解析到可用 Resource；`group` 可以解析为当前非空的递归集合。
3. Resource kind、cardinality 和 schema 必须满足目标输入定义。
4. Channel/Group 集合不做隐式类型过滤；集合内每个 Resource 都必须兼容，否则引用为 broken。需要子集时显式选择 Resource 或使用 Workflow 过滤 Function。
5. 新引用不能让包含 Group 聚合边的有效依赖图形成环；Group 内成员不能通过 `@Group` 间接依赖包含自己的集合。
6. 同一输入槽按 FunctionInput 的 cardinality 限制引用数量。
7. Group 成员移出可见 Group 后，依赖该 Group Link 的成员引用立即变为 broken。

### 6.4 删除 Link

Link 下存在以其为 `visibilityLinkId` 的 ResourceReference 时，普通删除被拒绝。用户必须选择：

- 取消并保留 Link。
- 同时移除引用；目标与后代进入 `stale` 或 `broken`。

Agent Command 必须显式使用“unlink-and-remove-references”，不能静默破坏依赖。

## 7. 依赖传播与有效性

### 7.1 依赖图

有效依赖图由两类边组成：

```text
ResourceReference: dependencySourceNodeId -> targetNodeId
Group aggregation: memberNodeId -> parentGroupId
```

递归 Group 通过逐级聚合边表达。有效依赖图必须是有向无环图，因此 Group 不能引用自己的递归聚合结果。Link 图只负责可见性，可以包含双向 Link，但不得包含自连接。

### 7.2 Node 有效性

| 状态 | 含义 | 是否可新建引用 |
| --- | --- | --- |
| empty | 输入与配置可执行，但尚无当前 Resource | 否 |
| current | 当前 Resource 与 Config、输入版本一致 | 是 |
| stale | 保留旧输出，但输入或 Config 已变化 | 否 |
| broken | 必填引用丢失、类型不兼容、NodeDefinition/Function Version 缺失或 Payload 不可用 | 否 |

状态按 `broken > stale > empty > current` 的条件优先级计算：先判断依赖和 Payload 是否损坏；有可用旧输出但快照不匹配时为 stale；输入有效但没有当前输出时为 empty；其余为 current。ResourceNode 的内容当前可用时为 `current`；FunctionNode 的有效性由最新发布 Run 的 Function Version、输入快照、Config revision 和当前 ResourceReference 共同决定。Run 状态是独立维度，不写入 validity。

### 7.3 传播触发

以下变化触发传播：

- Resource 发布新当前版本。
- Resource 变为不可用或被删除。
- FunctionNode Config 发生语义变化。
- 新增、修改或删除 ResourceReference。
- Group 成员或递归成员 Resource 变化。
- FunctionNode 切换到新的 Function Version。

以下变化不触发传播：

- Node 移动、缩放、旋转和 zIndex 变化。
- viewport 变化。
- Group 折叠和展开。
- 纯展示样式变化。
- 打开或关闭面板。

Resource 名称只有在 Function 明确声明“名称参与输入”时才属于语义变化。

### 7.4 传播结果

传播只更新有效性，不自动调用高成本 Function：

```text
上游变化
-> 直接依赖节点 stale / broken
-> 继续标记全部后代
-> 用户选择更新当前节点、更新到此处或更新后续链路
```

旧 ResourceVersion 继续可查看、下载和从历史创建新节点，但 stale Node 的旧输出默认不能被新建引用。

### 7.5 更新链路

- “运行当前节点”：必填上游必须为 current。
- “更新到此处”：按拓扑顺序运行当前节点依赖的 stale 祖先，再运行当前节点。
- “更新后续链路”：展示执行计划、成本和确认点后按拓扑推进。
- stale ResourceNode 没有 Function 可自动刷新，是执行计划中的人工编辑边界。

任何自动推进都必须由用户或 Workflow Runtime 显式发起，Canvas 传播本身不执行 Function。

## 8. Function 与执行

### 8.1 FunctionDefinition

```ts
interface FunctionDefinition {
  id: string
  version: string
  name: string
  description: string
  kind: 'system' | 'workflow' | 'agent'
  scope: 'system' | 'workspace'
  workspaceId?: string
  inputs: FunctionInput[]
  outputs: FunctionOutput[]
  configSchema: Record<string, unknown>
  executionPolicy: {
    sideEffect: 'read-only' | 'idempotent' | 'non-idempotent'
    timeoutMillis?: number
    cacheable: boolean
  }
}

interface FunctionInput {
  key: string
  displayName: string
  acceptedKinds: string[]
  cardinality: 'one' | 'many'
  required: boolean
  schema?: Record<string, unknown>
  semanticFields?: string[]
}

interface FunctionOutput {
  key: string
  displayName: string
  producedKinds: string[]
  cardinality: 'one' | 'many'
  required: boolean
  schema?: Record<string, unknown>
}
```

输入使用同一数量规则：required one 为 1，optional one 为 0..1，required many 为 1..N，optional many 为 0..N。

输出数量规则：`one + required` 必须恰好 1 项，`one + optional` 为 0..1 项，`many + required` 为 1..N 项，`many + optional` 为 0..N 项。Node 在所有输出均为空时不创建假 Resource。

Function Version 不可变。FunctionNode 始终引用具体版本，升级需要显式操作并触发 stale 传播。

### 8.2 FunctionRun

```ts
interface FunctionRun {
  id: string
  workspaceId: string
  canvasId?: string
  functionNodeId?: string
  functionRef: { id: string; version: string }
  status: 'queued' | 'running' | 'waiting' | 'succeeded' | 'failed' | 'cancelled'
  configRevision?: number
  inputSnapshot: FunctionInputSnapshot[]
  parentRunId?: string
  retryOfRunId?: string
  attempt: number
  outputResources: RunOutputReference[]
  publication: {
    status: 'not-applicable' | 'published' | 'not-published'
    reason?: 'superseded' | 'permission-revoked' | 'node-deleted'
  }
  error?: { code: string; message: string }
}

interface FunctionInputSnapshot {
  inputKey: string
  referenceIds: string[]
  resourceVersions: ResourceVersionReference[]
}

interface ResourceVersionReference {
  resourceId: string
  resourceVersionId: string
  kind: Resource['kind']
  semanticHash: string
}

interface RunOutputReference {
  channelKey: string
  itemKey: string
  resourceId: string
  resourceVersionId: string
}
```

集合输入在快照中保留规范化 item 顺序。Workflow Step 的输入使用相同结构，只是 ResourceVersion 来自 Workflow Input 或上游 Run 所属 Resource。

执行流程：

1. 校验 FunctionNode 当前输入。
2. 冻结 Function Version、Config revision 和 ResourceVersion。
3. 创建 FunctionRun。
4. 执行实现。
5. 校验输出契约，并保存 Run 所属 Resource 与 ResourceVersion。
6. 如果由 Canvas FunctionNode 发起，重新校验 Function Version、Config revision、Reference 集合及当前输入版本。
7. 条件一致时通过系统 Canvas Command 原子发布 Node 所属 ResourceVersion；不一致时标记 `not-published` 及原因，只保留 Run 输出。
8. 更新 FunctionNode 有效性，并在实际发布后向引用后代传播 stale。

Function 输出按 Run 原子提交：只有输出契约完整通过后才成为可消费的 Run Resource。failed/cancelled Run 的部分结果只能作为诊断 Artifact 保留，不能被 Workflow Binding 或 Canvas 发布。

### 8.3 运行期间编辑

运行期间可以修改 Config、ResourceReference 或上游 Resource，但不会改变当前 Run。Run 完成时：

- Node 与权限仍可发布，且 Function Version、Config revision、Reference ID 集合、当前 ResourceVersion 和 semanticHash 均与输入快照一致：输出发布为 current，Node 置为 `current`。
- 任一条件已变化：Run 可以成功，但输出只保留在 Run 历史，`publication.status = not-published`、`reason = superseded`；Node 按当前输入与已有输出重新计算为 empty、stale 或 broken。
- Run 已取消：晚到结果只进入审计，不发布为当前输出。

### 8.4 重试与历史

- 继续恢复同一次可恢复 Run。
- 执行策略允许的瞬时错误可以在同一 Run 内增加 attempt；用户重试创建新的 FunctionRun，并通过 retryOf 关联旧 Run。
- 重新运行使用当前 Config 和当前输入版本。
- 历史输出不被新运行删除。
- FunctionNode 输出本身只通过 Run 发布；需要人工编辑时先提取为独立 ResourceNode，提取结果不会被后续运行覆盖。

### 8.5 并发

- 同一个 FunctionNode 同时只有一个 active Run。
- 不同 FunctionNode 可以并行。
- Workflow Runtime 可以并行执行无依赖 Step。
- 非幂等 Function 发生未知终态时必须等待人工确认，不能自动重试。

## 9. Workflow 产品逻辑

### 9.1 独立 Workflow Canvas

Workflow 使用独立页面和领域模型：

```text
Workflow Canvas
├── InputParamNode
├── FunctionStepNode
├── ForEachNode
├── ConditionNode
├── ApprovalNode
├── OutputNode
└── WorkflowBinding
```

它可以复用平移、缩放、选择和节点渲染基础设施，但不复用 Canvas Link、ResourceReference、保存 Store 或传播状态机。

### 9.2 Workflow Definition

```ts
interface WorkflowDefinition {
  id: string
  workspaceId: string
  name: string
  description: string
  schemaVersion: number
  draftRevision: number
  inputs: WorkflowInput[]
  steps: WorkflowStep[]
  bindings: WorkflowBinding[]
  outputs: WorkflowOutput[]
  policies: WorkflowPolicy
  presentation: Record<string, { x: number; y: number; width?: number; height?: number }>
}
```

Workflow Input 和 Output 共同形成发布后的 Function 签名。`presentation` 只保存 Workflow Canvas 布局，不参与执行和 Function contentHash。

### 9.3 Workflow Binding

Workflow Binding 可以在没有任何实际 Resource 时建立：

```text
Input Param -> Step Input
Step Output -> Step Input
Step Output -> Workflow Output
```

它表达运行时未来值的传递，与 Canvas 的当前资源引用完全不同。

### 9.4 Function Step

每个 FunctionStep 引用具体 Function Version：

```ts
interface FunctionStep {
  id: string
  functionRef: { id: string; version: string }
  config: Record<string, unknown>
  retryPolicy?: {
    maxAttempts: number
    backoffMillis: number
  }
}
```

系统基础 Function、Agent Function 和已发布 Workflow Function 使用同一 Step 结构。MVP 禁止 Workflow 递归调用自己或形成 Function 依赖环。

### 9.5 ForEach

ForEach 接收一个 Resource Collection，并为每个 item 展开相同子步骤：

```text
Resource[]
-> ForEach resource
   -> TransformResource(resource)
-> Resource[]
```

规则：

- 每个 iteration 有稳定 key。
- iteration 可以并行。
- 输出按输入顺序或语义 key 聚合。
- ForEach 必须声明最大项目数和最大并发；运行输入超过上限时拒绝或重新确认。
- 执行前按实际项目数展示预计成本。
- 默认 fail-fast；可显式配置 collect-errors。
- ForEach 是 Workflow 控制结构，不自动出现在普通 Canvas。

### 9.6 Condition 与 Approval

- Condition 只能基于已声明的结构化值选择分支。
- Approval 在高成本、发布、删除或不确定结果处挂起 WorkflowRun。
- 任意自由循环不进入首期；重复处理使用 ForEach，复杂迭代由受约束 Function 或 Agent 完成。

### 9.7 Workflow Version

Workflow Draft 可反复编辑；发布前必须通过：

- 图无环校验。
- Function Version 存在性校验。
- 输入输出类型与 cardinality 校验。
- 必填参数可达性校验。
- ForEach 聚合校验。
- 权限、成本和副作用检查。
- 测试样例执行。

发布生成不可变 WorkflowVersion，并在 Function Library 注册对应 Workflow Function。已有 Canvas FunctionNode 固定旧版本，升级必须显式执行。

Workflow Draft 独立保存 Test Case。输入 Fixture 固定具体 ResourceVersion 或内联标量，Assertion 只检查输出 kind、数量、schema 和显式字段；发布记录本次 Test Summary，但测试数据不进入 Function 运行签名。
用于发布的 Test Summary 必须对应当前规范化语义 contentHash；只调整 presentation 不会使测试失效，修改 AST、签名或执行策略会失效。

### 9.8 Workflow DSL

Workflow 执行语义的唯一事实源是结构化 AST；可视化图使用 AST 加 presentation，文本 DSL 只读写 AST：

```text
Workflow DSL
<-> Workflow Definition AST
-> Runtime Execution Plan

Workflow Canvas
<-> Workflow Definition AST + presentation
```

概念语法：

```text
workflow generateStoryboard(story: Text) -> images: Image[] {
  scenes = exec splitStoryboard(story)
  reviewed = exec reviewStoryboard(scenes)
  assets = exec extractAssets(reviewed)
  generated = foreach asset in assets {
    exec generateAsset(asset)
  }
  images = exec composeStoryboard(reviewed, generated)
  emit images
}
```

DSL 不直接执行任意宿主语言代码。

### 9.9 Workflow Run

Workflow Function 被调用时创建父 FunctionRun，内部 Step 创建子 FunctionRun：

```text
Workflow FunctionRun
├── Step FunctionRun
├── Step FunctionRun
├── ForEach
│   ├── Iteration FunctionRun
│   └── Iteration FunctionRun
└── Step FunctionRun
```

运行时只调度输入已经准备完成的 Step，支持持久等待、恢复、取消、缓存和部分重试。
取消父 Workflow FunctionRun 时向非终态子 Run 传播取消请求；无法中止的外部调用只保留审计结果，不再被父 Run 聚合或发布。

### 9.10 Workflow 在 Canvas 中的表现

Workflow 发布后在 Canvas 中仍是普通 FunctionNode：

```text
[创意 ResourceNode]
        -> [分镜 Workflow FunctionNode]
        -> [后续 FunctionNode]
```

Workflow FunctionNode 可以在首次输出前建立下游 Link；下游在输出实际存在后才能通过 `@` 引用。

### 9.11 从 Canvas 固化 Workflow

用户或 Agent 选择一段稳定过程并执行“提取为 Workflow”：

1. 实际 ResourceReference 边界转换为 Workflow Input。
2. 选区内 FunctionNode 转换为 FunctionStep。
3. 选区内实际引用转换为 Workflow Binding。
4. 对外输出转换为 Workflow Output。
5. 当前具体 Resource 进入 Test Fixture，不默认写死为常量。
6. Group 和布局只用于初始 Workflow Canvas 排版，不改变执行语义。
7. 用户在 Workflow Canvas 中确认参数、常量、错误策略和输出后发布。

仅有 Link、没有实际 `@` 引用的关系不自动转换为 Workflow Binding。

## 10. 核心用户流程

### 10.1 上传图片并继续生成

```text
上传图片
-> 创建 Image ResourceNode
-> 发布 Image Resource
-> Link 到 Image Generation FunctionNode
-> 在 FunctionNode 中选择 @图片
-> 执行
-> FunctionNode 发布新 Image Resource
```

### 10.2 预先连接空 FunctionNode

```text
创建生图 FunctionNode（无 Resource）
-> 先 Link 到视频 FunctionNode
-> 视频节点暂时看不到可引用图片
-> 生图成功发布图片
-> 视频节点资源列表出现该图片
-> 用户选择 @图片后才建立实际依赖
```

### 10.3 多图处理

```text
生图 FunctionNode 输出 4 张图
-> 下游 many 输入可引用整个 images 通道
-> one 输入要求选择具体 item
-> 独立编辑时提取 item 为 ResourceNode
-> 逐项生成视频时在 Workflow 中使用 ForEach
```

### 10.4 Group 复用

```text
将角色图、场景图和文档放入 Group
-> Group 聚合成员资源
-> Link Group 到下游
-> 下游选择 @Group 或具体成员资源
```

### 10.5 稳定过程固化

```text
首次由人或总控 Agent 在 Canvas 调试
-> 选择稳定 FunctionNode 子图
-> 提取 Workflow Draft
-> 定义 Input / Output
-> 测试并发布
-> 以后在 Canvas 只创建一个 Workflow FunctionNode
```

## 11. Agent 接入边界

Agent 的具体 Harness、Session、Run 和前端面板在 Agent 基座稳定后接入。Canvas 与 Workflow 只依赖以下产品级能力：

```text
Agent Catalog
  查询可用 Agent

Agent Execution
  使用 Agent 配置、输入 Resource 和任务描述发起执行

Tool Registration
  Canvas / Workflow 注册模块 Tool
```

Canvas 和 Workflow 只保存 Agent Adapter 可解析的不透明 AgentRef。默认产品体验优先从 Workspace Agent Library 选择统一管理的 Agent；是否支持内联 Agent Spec、AgentRef 的最终格式、Snapshot 字段和 SessionMode 等待 Agent 基座稳定后确定，不能写入 Canvas 核心 schema。每次 Agent FunctionRun 开始时必须由 Adapter 冻结可审计的 Agent 解析结果，避免运行中配置漂移；无论最终适配方式如何，Canvas 都不建立第二套 Agent Runtime。

模块 Tool 由各自领域拥有；以下是逻辑能力名，最终 ToolDescriptor、版本和注册 SPI 等待 Agent 基座稳定后适配：

```text
canvas.inspect
canvas.apply
workflow.inspect
workflow.apply
workflow.validate
workflow.test
workflow.publish
function.search
function.execute
```

Tool 只能调用 Canvas/Workflow Command Service，不能直接修改数据库或前端 Store。Agent 默认最终文本转为 Text Resource，额外图片、文档和结构化结果作为其他 Resource 发布；用户编辑最终报告时先提取为独立 Text ResourceNode。
Harness Artifact 只是 Output Payload 来源，必须经 Adapter 转成 ResourceStore Payload 和 Run 所属 Resource，不能直接充当产品 Resource 身份。

Dock 直接执行没有 FunctionNode owner，finalReport 首先是 Run 所属 Text Resource 并显示在 Thread；“放到画布”或“编辑”通过 Command 提取为 ResourceNode。Agent FunctionNode 执行则按普通发布 barrier 原地发布输出。

底部 Agent Dock 是总控 Agent 的交互入口，不是新的 Canvas 基础对象。总控 Agent 通过 Tool 创建 Node、Link、ResourceReference、Workflow Draft 和 FunctionRun。

Dock 提交冻结 `canvasId + revision + contextMode + selectedNodeIds`：

- 当前选区模式只解析选中 Node 当前有效且有权限的 ResourceVersion。
- 整张 Canvas 模式只发送结构摘要和受限 `canvas.inspect` Scope，Agent 按需读取 Node/Resource，不把全部 Payload 无上限塞入 Prompt。
- hidden Node 不进入自动上下文；用户显式引用的 Resource 仍可按权限使用。
- 上下文超过模型预算时先按类型、大小和用户固定项生成可解释裁剪结果，不静默随机丢弃。
- Agent 写入仍使用提交后的最新 baseRevision；若 Canvas 已变化，Command 按正常冲突规则处理。

Canvas/Workflow 调用 `system.agent.execute` 是产品层 Agent Function；Agent 内部通过 `task` 等 Tool 委派 Subagent 属于 Harness 内部执行树，两者不共享 Canvas Node 或 Workflow Step 模型。

## 12. Command、保存与撤销

所有共享文档修改统一通过 Canvas Command：

```ts
interface CanvasCommand {
  commandId: string
  canvasId: string
  baseRevision: number
  actor: { type: 'user' | 'agent' | 'system'; id: string }
  type: string
  payloadVersion: number
  payload: unknown
}
```

Workflow Draft 使用独立但同构的 WorkflowCommand，携带 `workflowId`、`baseDraftRevision`、`commandId` 和 actor，并以相同 local ref 机制原子创建 Step 与 Binding。Canvas Command 与 WorkflowCommand 不共享 payload 类型或 revision。

核心命令：

```text
UpdateCanvasMetadata
SetCanvasLifecycle
CreateNodes
UpdateNodes
MoveNodes
DuplicateNodes
DeleteNodes
CreateLinks
DeleteLinks
BindResourceReferences
UnbindResourceReferences
CreateGroup
MoveIntoGroup
RemoveGroup
PublishFunctionOutputs
UpgradeFunctionVersion
```

规则：

- 成功命令返回新 document revision。
- `commandId` 是幂等键；相同 ID 只有请求内容一致时才返回原结果，内容不同必须报幂等冲突。
- 批量创建使用 command-local ref；同一批后续操作可以引用新对象，服务端在结果中返回 local ref 到稳定 ID 的映射。
- 拖拽、尺寸调整和连续文本输入合并为一个撤销单元。
- viewport、选区和浮层不进入文档撤销历史。
- Function 外部副作用、成本和运行审计不由普通 Undo 删除。
- Undo `PublishFunctionOutputs` 只恢复 Node 当前 Resource 指针；ResourceVersion 和 FunctionRun 仍保留。
- 用户显式“恢复此历史版本”会复用旧 Payload 创建一个新的单调 ResourceVersion；只有 Undo 补偿命令可以把 current pointer 暂时指回前一版本。
- Schema migration 是 Undo 边界；迁移前 Command 仍可审计，但不会用旧 inverse 修改新 schema。
- 协作中 Undo 仍以当前 revision 提交补偿命令；若目标字段已被他人修改，返回冲突并要求确认，不静默覆盖后来变更。
- 保存状态必须来自服务端确认，不能由前端定时器伪造。

## 13. 删除、复制与历史

| 对象 | 默认行为 |
| --- | --- |
| Node 无下游引用 | 删除 Node、Link 和当前文档内 Resource 投影 |
| Node 有下游引用 | 提示受影响节点；确认后删除引用并传播 broken/stale |
| FunctionNode 有 active Run | 先取消或等待终态 |
| 非空 Group | 默认只删除 Group，成员留在原位 |
| ResourceVersion | 不直接删除；按引用和保留策略回收 Payload |
| Workflow Draft | 无 active 测试 Run 时可删除 |
| Workflow Version | 已发布版本不可修改；被引用时不可物理删除 |

Node 删除在保留期内保存 tombstone，Run 所属 Resource、FunctionRun、Command 和审计引用不级联删除。恢复命令可以在 ID 未被回收前重建 Canvas 投影。

复制 Node 时创建新 Node ID。ResourceNode 复制可以复用不可变 Payload，但必须创建新的逻辑 Resource 身份。FunctionNode 复制保留 Function Version 与 Config，清空运行态和输出。

复制多选或 Group 时递归复制选区内 Node，并重映射选区内部 Group 层级、Link 和 ResourceReference；与选区外部相连的 Link/Reference 默认不复制，避免新副本意外依赖或影响外部 Node。

Canvas 导出包包含 schemaVersion、固定 revision 的 Node/Link/Reference 快照、当前 ResourceVersion manifest 和选择携带的 Payload；默认不导出凭据、Agent 内部 Session、费用和完整 Run 日志。导入时为 Canvas、Node 和 Resource 分配新 ID 并重映射引用，FunctionRef 保持具体版本；目标 Workspace 无法解析的 Function 或 Payload 以 broken 状态保留，不静默替换。

## 14. 权限、成本与风险动作

| 能力 | Viewer | Editor | Owner |
| --- | --- | --- | --- |
| 查看 Canvas、Workflow 和运行 | 是 | 是 | 是 |
| 编辑 Node、Link、Reference | 否 | 是 | 是 |
| 执行普通 Function | 否 | 是 | 是 |
| 编辑 Workflow Draft | 否 | 是 | 是 |
| 发布 Workflow Version | 否 | 按策略 | 是 |
| 管理 Function / Agent / 模型凭据 | 否 | 否 | 是 |

执行前确认：

- 显示 Function、模型、预计用量和最大成本。
- ForEach 使用“单次成本 × 项目数”计算上界。
- 高成本、外部发布、批量删除和非幂等 Function 必须显式确认。
- Provider 已开始处理后产生的费用进入 Run 记录。
- 权限撤销后，未开始的 Run 取消；已开始的外部调用按执行策略记录结果但不自动发布。

## 15. 异常与恢复

| 场景 | 行为 |
| --- | --- |
| Canvas 保存失败 | 保留本地待提交 Command，展示失败并允许重试 |
| Revision 冲突 | 拉取最新文档，重放无冲突命令；语义冲突要求用户处理 |
| Link 来源无 Resource | Link 保留，下游可见资源为空 |
| 已引用 Resource 变为不可用 | Reference 标记 broken，目标和后代传播 |
| Function 执行失败 | 保留旧输出和完整错误，可使用同一快照重试 |
| Function 晚到结果 | Cancelled 后不发布；Config、Reference 或上游版本已变时只保留在 Run 历史 |
| Workflow Step 失败 | 按 Step policy 重试；耗尽后父 Run 失败或等待处理 |
| ForEach 部分失败 | fail-fast 取消剩余项，或 collect-errors 聚合成功与错误 |
| 页面刷新 | 从服务端恢复文档、用户视图、active Run 和待处理确认 |
| SSE 断开 | 使用 cursor 重连并补齐事件 |
| 浏览器离线 | 本地 Command 排队；恢复后按 revision 提交 |

## 16. 原型映射

当前交互原型继续用于验证视觉和交互，不代表最终领域实现。

| 原型概念 | 正式产品映射 |
| --- | --- |
| 文本、图片、文件节点 | ResourceNode |
| 文本/图片/视频生成节点 | FunctionNode，分别引用系统生成 Function |
| 节点参考素材 | 由 Link 可见资源和 `@ResourceReference` 派生 |
| Generator 当前结果 | FunctionNode 输出 Resource |
| Agent Run 卡片 | Agent FunctionRun 的 Canvas 投影或运行详情 |
| Frame | GroupNode 的空间视图 |
| Result Group | GroupNode 或多资源输出通道视图 |
| Agent Dock | 总控 Agent 交互入口 |
| Thread | Agent Session / FunctionRun Activity 视图 |

原型中的固定世界尺寸、硬编码资源、定时器运行、伪保存、Frame 视觉分组和独立 `referenceItemIds` 均不是正式产品事实源。

## 17. 分期

### P0：领域地基

- CanvasDocument、Node、Link、ResourceReference 和 Command。
- Resource / ResourceVersion 与 Payload 存储。
- ResourceNode、FunctionNode、GroupNode。
- Function Catalog、系统 Function 注册和 FunctionRun。
- 引用 DAG、stale 传播、保存、撤销和刷新恢复。
- 文件、文本和图片导入。

### P1：Workflow 闭环

- Workflow Draft、独立 Workflow Canvas 和校验。
- FunctionStep、ForEach、Output 和 WorkflowRun。
- Workflow Version 发布并注册为 Function。
- Canvas 提取 Workflow 和 Workflow FunctionNode。
- 成本、缓存、重试和测试 Fixture。

### P2：Agent 与协作

- Agent Catalog / Execution Port。
- Canvas/Workflow Tool Adapter。
- 总控 Agent Dock 和嵌入式 Activity Panel。
- 多人实时协作、权限和评论。
- Function、Workflow 和作品市场。

## 18. 产品逻辑验收

1. 任意 Node 可以在没有 Resource 时建立 Link。
2. 下游只能 `@` 已经存在、当前有效且通过 Link 可见的 Resource。
3. 仅有 Link 不形成依赖，实际 ResourceReference 才触发传播。
4. Resource 名称可以重复，引用始终基于稳定 ID 和 key。
5. Resource 内容变化创建不可变版本，普通引用跟随当前版本，Run 冻结具体版本。
6. 多资源输出支持整体引用和单项引用，普通 Canvas 不隐式扇出高成本执行。
7. FunctionNode 成功后原子发布 Resource，失败和取消不覆盖旧结果。
8. 上游语义变化使实际依赖后代 stale 或 broken，但不会自动调用 Function。
9. Group 移动联动成员，并可作为聚合 Resource 集合被引用。
10. Workflow 在独立 Canvas 中构建，Binding 可以引用未来 Step 输出。
11. Workflow 发布为不可变 Function Version，普通 Canvas 只通过 FunctionNode 使用。
12. Workflow ForEach 能逐项执行并聚合结果，执行前可估算成本。
13. Canvas 与 Workflow 所有修改都经过带 revision 的 Command。
14. 页面刷新、执行恢复和 SSE 重连不会丢失已确认状态。
15. Agent 接入不要求 Canvas 依赖 Harness 内部 Session、Run 或 Tool 类型。
