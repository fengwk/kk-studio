package fun.fengwk.kkstudio.agent;

import fun.fengwk.kkstudio.agent.provider.AssistantResponse;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;

import java.util.List;

/**
 * AgentSignal 表示回流到 Agent 的异步信号。
 *
 * @author fengwk
 */
sealed interface AgentSignal permits StartLoopSignal, RetryAssistantSignal, AbortSignal,
    AssistantTextDeltaSignal, AssistantThinkingDeltaSignal, AssistantToolCallDeltaSignal,
    AssistantToolCallCompleteSignal, AssistantCompleteSignal, AssistantErrorSignal,
    ToolPartialSignal, ToolCompleteSignal, ToolErrorSignal {
}

/**
 * 启动主 loop 的触发信号。
 */
enum StartLoopSignal implements AgentSignal {
    INSTANCE
}

/**
 * 触发 assistant 重试的延迟回流信号。
 */
record RetryAssistantSignal(AgentRunContext runContext) implements AgentSignal {
}

/**
 * 触发显式取消的控制信号。
 */
record AbortSignal(String reason) implements AgentSignal {
}

/**
 * assistant 文本增量回流信号。
 */
record AssistantTextDeltaSignal(AssistantAttemptState attemptState, String textDelta) implements AgentSignal {
}

/**
 * assistant thinking 增量回流信号。
 */
record AssistantThinkingDeltaSignal(AssistantAttemptState attemptState, String thinkingDelta) implements AgentSignal {
}

/**
 * assistant tool call 增量回流信号。
 */
record AssistantToolCallDeltaSignal(AssistantAttemptState attemptState,
                                    IndexedToolCallDelta toolCallDelta) implements AgentSignal {
}

/**
 * assistant 单个 tool call 完整结果回流信号。
 */
record AssistantToolCallCompleteSignal(AssistantAttemptState attemptState,
                                       Integer index,
                                       ToolCall toolCall) implements AgentSignal {
}

/**
 * assistant 完整结束回流信号。
 */
record AssistantCompleteSignal(AssistantAttemptState attemptState,
                               AssistantResponse response) implements AgentSignal {
}

/**
 * assistant 异常结束回流信号。
 */
record AssistantErrorSignal(AssistantAttemptState attemptState,
                            Throwable error) implements AgentSignal {
}

/**
 * tool 增量结果回流信号。
 */
record ToolPartialSignal(ToolExecutionState toolState,
                         List<IndexedToolContentDelta> contentDeltas) implements AgentSignal {
}

/**
 * tool 完整结果回流信号。
 */
record ToolCompleteSignal(ToolExecutionState toolState,
                          List<ToolContent> contents) implements AgentSignal {
}

/**
 * tool 异常结束回流信号。
 */
record ToolErrorSignal(ToolExecutionState toolState,
                       Throwable error) implements AgentSignal {
}
