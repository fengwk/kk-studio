package fun.fengwk.kkstudio.agent;

import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.tool.ToolCallRequest;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.session.payload.ToolDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolStartPayload;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandle;
import fun.fengwk.kkstudio.agent.tool.ToolRegistration;
import fun.fengwk.kkstudio.agent.tool.ToolRegistry;
import fun.fengwk.kkstudio.agent.tool.execution.ToolCallExecutor;
import fun.fengwk.kkstudio.agent.tool.execution.ToolExecutionListener;

import java.util.List;
import java.util.function.Consumer;

/**
 * AgentToolOrchestrator 负责一次 tool batch 的完整生命周期。
 *
 * <p>职责范围：
 * <ul>
 *   <li>tool_start 事件写入与 ToolExecutionState 创建</li>
 *   <li>tool delta 流式回调</li>
 *   <li>tool complete / error 处理（含 gap 补齐）</li>
 *   <li>tool not found 处理与稳定错误文本</li>
 *   <li>全部 tool 终态聚合</li>
 * </ul>
 *
 * @author fengwk
 */
final class AgentToolOrchestrator {

  private final AgentSessionWriter writer;
  private final ToolRegistry toolRegistry;
  private final ToolCallExecutor toolCallExecutor;
  private final Consumer<AgentSignal> enqueueSignal;
  private final Runnable onAllToolsClosed;

  AgentToolOrchestrator(
      AgentSessionWriter writer,
      ToolRegistry toolRegistry,
      ToolCallExecutor toolCallExecutor,
      Consumer<AgentSignal> enqueueSignal,
      Runnable onAllToolsClosed) {
    this.writer = requireNonNull(writer, "writer");
    this.toolRegistry = requireNonNull(toolRegistry, "toolRegistry");
    this.toolCallExecutor = requireNonNull(toolCallExecutor, "toolCallExecutor");
    this.enqueueSignal = requireNonNull(enqueueSignal, "enqueueSignal");
    this.onAllToolsClosed = requireNonNull(onAllToolsClosed, "onAllToolsClosed");
  }

  /** 启动当前 assistant 产出的一批 tool call。 */
  void startBatch(AgentRunContext runContext, List<ToolCall> toolCalls) {
    if (runContext == null || runContext.aborted) {
      return;
    }
    runContext.toolStates.clear();
    for (ToolCall toolCall : toolCalls) {
      if (runContext == null || runContext.aborted) {
        break;
      }
      if (toolCall == null) {
        continue;
      }
      startSingleTool(runContext, toolCall);
    }
    if (allToolsClosed(runContext)) {
      onAllToolsClosed.run();
    }
  }

  void onPartial(AgentRunContext runContext, ToolPartialSignal signal) {
    if (!isActiveTool(runContext, signal.toolState())
        || signal.contentDeltas() == null
        || signal.contentDeltas().isEmpty()) {
      return;
    }
    signal.toolState().applyContentDeltas(signal.contentDeltas());
    ToolDeltaPayload toolDeltaPayload = new ToolDeltaPayload();
    toolDeltaPayload.setToolCallId(signal.toolState().toolCall.getToolCallId());
    toolDeltaPayload.setContentDeltas(signal.contentDeltas());
    writer.appendEvent(SessionEventType.tool_delta, toolDeltaPayload);
  }

  void onComplete(AgentRunContext runContext, ToolCompleteSignal signal) {
    if (!isActiveTool(runContext, signal.toolState())) {
      return;
    }
    signal.toolState().handle = null;
    List<IndexedToolContentDelta> gap = signal.toolState().computeGap(signal.contents());
    if (!gap.isEmpty()) {
      ToolDeltaPayload toolDeltaPayload = new ToolDeltaPayload();
      toolDeltaPayload.setToolCallId(signal.toolState().toolCall.getToolCallId());
      toolDeltaPayload.setContentDeltas(gap);
      writer.appendEvent(SessionEventType.tool_delta, toolDeltaPayload);
    }
    ToolEndPayload toolEndPayload = new ToolEndPayload();
    toolEndPayload.setToolCallId(signal.toolState().toolCall.getToolCallId());
    writer.appendEvent(SessionEventType.tool_end, toolEndPayload);
    signal.toolState().closed = true;
    if (allToolsClosed(runContext)) {
      onAllToolsClosed.run();
    }
  }

  void onError(AgentRunContext runContext, ToolErrorSignal signal) {
    if (!isActiveTool(runContext, signal.toolState())) {
      return;
    }
    signal.toolState().handle = null;
    writer.appendEvent(
        SessionEventType.tool_error,
        newToolErrorPayload(
            signal.toolState().toolCall.getToolCallId(), toErrorMessage(signal.error())));
    signal.toolState().closed = true;
    if (allToolsClosed(runContext)) {
      onAllToolsClosed.run();
    }
  }

  /** 取消当前 batch 中所有 active tool 的 handle（best-effort）。 */
  void cancelAll(AgentRunContext runContext) {
    if (runContext == null) {
      return;
    }
    for (ToolExecutionState toolState : runContext.toolStates.values()) {
      if (!toolState.closed && toolState.handle != null) {
        try {
          toolState.handle.cancel();
        } catch (RuntimeException error) {
          // best-effort：与 Agent.safeCancel 行为一致，但 orchestrator 不持有 logger，
          // 让 Agent 在 cancelCurrentRunResources 那一层做 logging。
        }
      }
      toolState.closed = true;
    }
  }

  // --- internal ---

  private void startSingleTool(AgentRunContext runContext, ToolCall toolCall) {
    ToolStartPayload toolStartPayload = new ToolStartPayload();
    toolStartPayload.setToolCallId(toolCall.getToolCallId());
    toolStartPayload.setToolName(toolCall.getToolName());
    toolStartPayload.setArguments(toolCall.getArguments());
    writer.appendEvent(SessionEventType.tool_start, toolStartPayload);

    ToolExecutionState toolState = new ToolExecutionState(runContext, toolCall);
    runContext.toolStates.put(toolCall.getToolCallId(), toolState);
    ToolRegistration registration = toolRegistry.get(toolCall.getToolName());
    if (registration == null) {
      writer.appendEvent(
          SessionEventType.tool_error,
          newToolErrorPayload(
              toolCall.getToolCallId(), newToolNotFoundMessage(toolCall.getToolName())));
      toolState.closed = true;
      return;
    }
    try {
      ToolCallRequest request = toToolCallRequest(toolCall, registration);
      ToolExecutionHandle handle =
          toolCallExecutor.execute(
              registration,
              request,
              new ToolExecutionListener() {
                @Override
                public void onPartial(List<IndexedToolContentDelta> partial) {
                  enqueueSignal.accept(new ToolPartialSignal(toolState, partial));
                }

                @Override
                public void onComplete(List<ToolContent> result) {
                  enqueueSignal.accept(new ToolCompleteSignal(toolState, result));
                }

                @Override
                public void onError(Throwable error) {
                  enqueueSignal.accept(new ToolErrorSignal(toolState, error));
                }
              });
      toolState.handle = handle;
    } catch (RuntimeException error) {
      writer.appendEvent(
          SessionEventType.tool_error,
          newToolErrorPayload(toolCall.getToolCallId(), toErrorMessage(error)));
      toolState.closed = true;
    }
  }

  private static boolean allToolsClosed(AgentRunContext runContext) {
    if (runContext == null) {
      return false;
    }
    for (ToolExecutionState toolState : runContext.toolStates.values()) {
      if (!toolState.closed) {
        return false;
      }
    }
    return true;
  }

  private static boolean isActiveTool(AgentRunContext runContext, ToolExecutionState toolState) {
    return runContext != null
        && runContext.toolStates.get(toolState.toolCall.getToolCallId()) == toolState
        && !toolState.closed
        && !runContext.aborted;
  }

  private static ToolErrorPayload newToolErrorPayload(String toolCallId, String message) {
    ToolErrorPayload payload = new ToolErrorPayload();
    payload.setToolCallId(toolCallId);
    payload.setMessage(message);
    return payload;
  }

  private static String toErrorMessage(Throwable error) {
    if (error == null) {
      return "unknown error";
    }
    String message = error.getMessage();
    return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
  }

  private String newToolNotFoundMessage(String toolName) {
    List<String> toolNames = toolRegistry.listToolNames();
    String availableTools = toolNames.isEmpty() ? "none" : String.join(", ", toolNames);
    return "tool not found: " + toolName + ". Available tools: " + availableTools + ".";
  }

  private static ToolCallRequest toToolCallRequest(ToolCall toolCall, ToolRegistration registration) {
    if (toolCall == null) {
      return null;
    }
    return new ToolCallRequest(
        toolCall.getToolCallId(),
        toolCall.getToolName(),
        toolCall.getArguments(),
        registration.getToolInfo().getInputSchema());
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }
}
