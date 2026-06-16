# Agent 模块技术方案

## 1. 目标

Agent 模块采用 `SessionEvent` 单层事件流模型，统一支撑：

- 实时展示
- 上下文重放
- branch 继续执行
- assistant / tool 流式输出
- 异常恢复与显式取消

第一版保持 KISS：

- 不引入 DAG merge
- 不引入 `loop_start / loop_end`
- 不在持久化层保存多份完整消息快照
- 不在 Agent 内部持有私有线程池

## 2. 核心原则

### 2.1 SessionEvent 是唯一事实来源

持久化层只有 `SessionEvent`。

它同时承担：

- 历史事实记录
- 下一轮 assistant 输入来源
- 展示与重放来源

### 2.2 Delta 是内容真相

对 assistant / tool 两类流：

- `*_delta` 保存内容变化
- `*_end` 只保存边界与少量元信息

因此：

- assistant 完整消息由 `assistant_delta` 重放得到
- tool 完整结果由 `tool_delta` 重放得到

### 2.3 Error / Abort 是合法闭合

`error` 与 `abort` 都可以闭合：

- `assistant_start`
- `tool_start`

一旦被 `error / abort` 闭合：

- 不再额外写对应 `assistant_end / tool_end`
- 已存在 delta 仍参与重放

### 2.4 Agent 只串行推进 branch event

同一 branch 上：

- event append 必须串行
- assistant 调用无并发
- 多个 `toolCallId` 可以并行执行
- 多个 tool callback 可以交错到达
- 但回流到 Agent 后必须串行落 event

### 2.5 Agent 不自带线程调度

Agent 内部只维护：

- 异步状态机
- CAS 门闩
- 待处理信号队列

外部基础设施只提供：

- 一个全局唯一的单线程调度器

该调度器仅用于模型重试的延迟触发。

## 3. 术语

### 3.1 Session

第一版 `Session` 仅保留：

- `sessionId`
- `currentHeadEventId`

### 3.2 Branch

`Branch` 是逻辑游标，不是实体。

字段：

- `sessionId`
- `headEventId`

### 3.3 UserRequestQueue

`submit` 先进入 `UserRequestQueue`。

每一轮 `assistant_start` 前都要收割队列中的全部用户消息。

## 4. 事件类型

第一版 `SessionEventType` 为：

- `set_agent_info`
- `set_model_info`
- `assistant_start`
- `assistant_delta`
- `assistant_end`
- `tool_start`
- `tool_delta`
- `tool_end`
- `error`
- `abort`

## 5. 配置事件

### 5.1 set_agent_info

保存当前实际生效的 agent 信息。

payload：

- `agentName`
- `systemPrompt`
- `tools: List<String>`
- `subagents: List<String>`
- `skills: List<String>`

语义：

- 记录的是**实际展开后的 agent 信息**
- replay 不依赖 registry 的未来状态
- 不投影成业务消息，但会在投影时生成当前 system message

### 5.2 set_model_info

保存当前实际生效的模型选择。

payload：

- `provider`
- `model`
- `variant`

语义：

- 记录的是**实际解析后的最终调用信息**
- 每次实际 assistant 调用前都要检查是否变化

## 6. assistant / tool 事件

### 6.1 assistant_start

payload：

- `userMessages: List<String>`

语义：

- 每一轮 assistant 都要写 `assistant_start`
- 首轮写收割到的用户消息
- tool 后续轮允许空列表
- 模型重试时也要重新写一条，但 `userMessages = []`

### 6.2 assistant_delta

payload：

- `textDelta`
- `thinkingDelta`
- `toolCallsDelta`

其中 `toolCallsDelta` 元素为：

- `index`
- `toolCallDelta`

`toolCallDelta` 字段：

- `toolCallId`
- `toolName`
- `argumentsDelta`

### 6.3 assistant_end

payload：

- `metadata`

语义：

- 只保存 metadata
- 最终 text / thinking / tool calls 都通过 delta 补齐

### 6.4 tool_start

payload：

- `toolCallId`
- `toolName`
- `arguments`

### 6.5 tool_delta

payload：

- `toolCallId`
- `contentDeltas`

### 6.6 tool_end

payload：

- `toolCallId`

语义：

- 只负责闭合 tool 生命周期

### 6.7 error

payload：

- `toolCallId`（可选）
- `message`

语义：

- `toolCallId = null` 时闭合当前 assistant
- `toolCallId != null` 时闭合指定 tool
- provider / tool / timeout 等自然失败统一写 `error`

### 6.8 abort

payload：

- `toolCallId`（可选）
- `reason`

语义：

- 仅由显式取消触发
- `toolCallId = null` 时闭合当前 assistant
- `toolCallId != null` 时闭合指定 tool

## 7. 主链语义

### 7.1 submit

`submit` 仅负责：

- 入队
- 触发主 loop 尝试启动

不负责：

- 同步等待结果
- 返回运行句柄

### 7.2 loop 启动

主 loop 规则：

1. 先检查 `UserRequestQueue`
2. 队列非空时 CAS `idle -> busy`
3. 抢占成功才真正进入 loop
4. 抢占失败说明已有 loop 在执行，安全返回

### 7.3 turn 结构

```text
pull all user messages
-> assistant attempt
-> if tool calls exist:
     run tool batch
     -> next assistant attempt
   else:
     final answer
```

补充：

- 第一次 assistant 无 tool call，即 final answer
- tool 全部结束后，进入下一轮 assistant 前再次收割用户消息
- final answer 后先释放 loop，再重新检查队列并触发新 loop

## 8. assistant 调用前的配置刷新

每次**实际 assistant 调用前**：

1. 获取最新 `AgentInfo`
2. 获取最新模型选择
3. 若与当前事实不同：
  - 先写 `set_agent_info`
  - 再写 `set_model_info`
4. 然后写 `assistant_start`
5. 再发起模型调用

约束：

- 只在 assistant 前刷新配置
- tool 执行前不刷新
- 模型重试前也要刷新配置

## 9. 重试语义

### 9.1 范围

只支持**当前模型级别重试**：

- 不切 provider
- 不切 model fallback
- 不做额外 runtime fallback

### 9.2 失败后的事件表现

一次失败但可重试的尝试会显式进入历史：

```text
assistant_start
-> assistant_delta*
-> error
-> assistant_start
-> ...
-> assistant_end
```

语义：

- 每次模型调用尝试都是一个独立 assistant 生命周期
- 历史中可看到失败与已消耗副作用
- 重试时新的 `assistant_start.userMessages = []`

### 9.3 退避与上下文

运行中维护一个全异步 `context`，记录例如：

- 当前重试次数
- 当前 assistant handle
- 当前 tool handles
- 待处理信号队列状态

当模型失败且未超限时：

1. 当前尝试写 `error`
2. `retryCount++`
3. 通过全局单线程调度器注册一次延迟回调
4. 延迟按指数退避计算
5. 回调触发后重新进入下一次 assistant 尝试

## 10. 流式回调与 gap 补齐

### 10.1 assistant

Provider 层封装自己的 handler 协议，不让 Agent 直接依赖 LangChain4j 回调细节。

回调类型：

- text delta
- thinking delta
- tool call delta
- tool call complete
- complete
- error

`complete` 到来时：

- 对比当前已聚合内容与最终完整结果
- 若存在 gap，则先补最后一个 `assistant_delta`
- 再写 `assistant_end`

### 10.2 tool

`ToolExecutionHandler` 采用：

- `onPartial(List<IndexedToolContentDelta>, context)`
- `onComplete(List<ToolContent>, context)`
- `onError(Throwable, context)`

`onComplete` 必须提供最终完整数据。

在 `tool_end` 前：

- 对比当前已聚合内容与最终完整结果
- 若存在 gap，则先补最后一个 `tool_delta`
- 再写 `tool_end`

## 11. 异步协调模型

### 11.1 assistant

assistant 本身没有并发，不需要多异步同步。

### 11.2 tool

多个 `toolCallId` 可以并行执行。

但回流到 Agent 时采用：

- CAS + 待处理队列

即：

- callback 原样入队
- 同一时刻只有一个 drain 在消费
- event append 永远串行

## 12. 投影规则

`SessionEventMessageProjector` 负责把 branch event 流投影为当前消息上下文。

规则：

- 取最新 `set_agent_info` 生成当前 `SystemMessage`
- assistant/tool 完整消息由 delta 重放
- `error / abort` 负责闭合当前 open state
- 指定 `toolCallId` 的 `error / abort` 只闭合对应 tool

## 13. 注册表职责

### 13.1 AgentRegistry

负责：

- `registerAgent(AgentInfo)`
- `getAgent(name)`

`AgentInfo` 第一版包含：

- `name`
- `systemPrompt`
- `defaultProvider`
- `defaultModel`
- `defaultVariant`
- `tools`
- `subagents`
- `skills`

### 13.2 ProviderRegistry / ProviderManager

`ProviderRegistry`：

- 输入 `provider` 名称
- 输出 `ProviderInfo`

`ProviderManager`：

- 输入 `ProviderInfo`
- 输出 `Provider`

### 13.3 ModelRegistry

`ModelRegistry`：

- `registerModel(ModelInfo)`
- `getModel(provider, model)`

运行时再从 `ModelInfo` 中解析 `variant`。

`Variant` 中：

- 通用参数直接平铺
- provider 专有参数直接使用 `Map<String, Object> providerOptions`

`Variant` 是运行时模型请求参数的**唯一载体**，由 `AgentRuntimeConfigResolver` 解析后直接交给 `Provider.asyncChat(...)`。不再存在中间的 `ModelRequestConfig` 之类的搬运结构，`Provider` 内部自行把 `Variant + tool specifications` 翻译成 LangChain4j 的 `ChatRequest`。

### 13.4 ToolRegistry

负责：

- `registerTool(name, ToolInfo, Tool)`
- `getToolInfo(name)`
- `getTool(name)`

## 14. 当前代码结构

第一版代码职责拆分为：

- `Agent`
  - 负责主链异步状态机与 event 串行推进
- `AgentRuntimeConfigResolver`
  - 负责 agent/model 选择解析与运行时 provider/request config 装配
- `SessionManager`
  - 负责 branch 视角历史读取与事件追加
- `session.projection.SessionEventMessageProjector`
  - 负责事件重放与消息投影

这样可以避免把配置解析、事件推进、流式回调适配全部塞进一个超大类中。

## 15. 第一版不做的事情

- DAG merge
- Session 父子层级
- loop_start / loop_end
- provider/model fallback
- tool retry 策略
- projection 级别的复杂多视图展示模型
