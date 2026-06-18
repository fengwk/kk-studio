# 本地环境 daemon

本文是整体技术方案的一部分，描述本地环境 daemon 的职责与能力边界。

## 目标

本地 daemon 只负责一件事：

> 把本地环境能力稳定地接入云端，让云端内嵌 agent 可以按工具方式使用这些能力。

因此 daemon 的角色是：

- 环境注册器
- keepalive client
- 动态工具注册器
- remote tool host

## 核心职责

### 1. 通道职责

- 建立到 server 的 WebSocket 长连接。
- 完成首次 `env_register`。
- 周期发送 `env_heartbeat`。
- 接收 server 主动下发的控制消息。

### 2. 环境职责

- 上报环境唯一标识。
- 上报设备、OS、workspace root 等环境信息。
- 上报本地 runtime 与 tool capability 快照。

### 3. 工具职责

- 注册原生 Java tool。
- 承载可扩展的脚本类工具。
- 接收云端 remote tool call。
- 流式回传工具输出、完成态与错误态。

## keepalive 最小协议

### daemon -> server

- `env_register`
- `env_heartbeat`
- `env_capability_update`

### server -> daemon

- `ack`
- `ping`
- `env_reconfigure`
- `tool_call_start`
- `tool_call_cancel`

## 能力快照模型

daemon 统一上报：

- `envId`
- `deviceName`
- `os`
- `arch`
- `workspaceRoots`
- `runtimeList`
- `toolCapabilities`
- `revision`

其中 `toolCapabilities` 至少包含：

- `toolName`
- `schemaJson`
- `sourceType`
- `enabled`
- `metadata`

## envId 与 revision 生命周期

- daemon 首次启动时若本地无 `envId`，发送不带 `envId` 的 `env_register`。
- server 为该 daemon 分配新的 `envId`，并在 `EnvRegisterResponseDTO` 中返回。
- daemon 必须把 `envId` 持久化到本地工作目录下的稳定状态文件，后续重启继续复用。
- 若本地状态丢失，则按新环境重新注册，生成新的 `envId`，不尝试模糊匹配旧记录。
- `revision` 由 daemon 本地维护，针对 capability snapshot 单调递增。
- 每次 runtimeList 或 toolCapabilities 发生变化时，daemon 生成新 revision 并上报完整快照。
- server 对小于当前已接收 revision 的快照执行忽略，对等于当前 revision 的快照执行幂等覆盖。

## 动态工具模型

`sourceType` 预留：

- `JAVA`
- `PROCESS`
- `SCRIPT`
- `REMOTE`

工具接入顺序：

1. 原生 Java tool。
2. 为脚本工具预留模型与协议扩展位。

## 本地原生工具接入方式

- daemon 以本地原生工具为单位上报 capability。
- 每个工具归属某个 env，并通过统一环境接口被云端注册与调用。
- 云端 agent 只通过工具名和 schema 使用这些能力。
