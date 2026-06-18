# 架构总览

本文是整体技术方案的一部分，描述仓库级架构和模块边界。

## 系统形态

系统采用以下形态：

- 云端内嵌运行自研 agent。
- 本地 daemon 只提供环境能力与工具能力。
- 所有 session / event / run 都以云端为 canonical store。
- Studio 作为统一操作入口管理本地环境和工具能力。

对应执行模型：

- agent 逻辑不下沉到本地 daemon。
- 本地环境能力通过统一环境注册表与工具模型接入云端。
- 复杂的 run 生命周期、event append、branch 继续执行全部留在云端。

相关定义：

- session / event / run 存储对象见 `storage-models.md`。
- branch、assistant attempt、tool call 等执行语义见 `agent-engine.md`。

## 模块职责

### `agent`

职责：

- 自研 agent 执行内核。
- session event tree 与消息投影。
- provider / tool 执行抽象。
- 单个 agent run 的主循环、重试、取消与边界校验。

### `daemon`

职责：

- 本地环境进程。
- 与 server 保持长连接与 keepalive。
- 上报本地环境、runtime 与 tool capability。
- 承载 remote tool host。
- 统一管理原生 Java tool 与可扩展的脚本工具。

### `core`

职责：

- control plane 领域模型。
- agent profile、env/runtime/tool capability、session/event、run 等存储与服务。
- studio 与 daemon 共用的领域规则。
- remote tool registry 与云端 tool proxy 组装。

### `web`

职责：

- Studio API。
- daemon-facing HTTP / WS API。
- 鉴权、会话态、实时推送。

## 系统执行链路

系统执行链路包括：

1. daemon 独立启动。
2. daemon 连接 web 开出的云端接口。
3. daemon 完成 `env_register` 与 keepalive。
4. core / web 具备 agent、session、event 的存储模型。
5. 云端 agent 能通过 remote tool 调用本地能力。

## 环境工具的暴露方式

- 本地环境作为能力载体，通过统一环境注册表上报环境与工具能力。
- 云端根据 `agent_profile_tool_binding` 组装 agent 可见的工具列表。
- agent 只感知工具，不直接感知环境连接、在线状态和调用路由。
- 某个 agent profile 可以绑定特定环境上报的工具能力。

这些工具由 daemon 上报能力并在本地执行，云端只暴露绑定后的工具视图给 agent。
