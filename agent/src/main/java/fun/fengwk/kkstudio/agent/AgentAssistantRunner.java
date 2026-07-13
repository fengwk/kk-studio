package fun.fengwk.kkstudio.agent;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.agent.message.AgentMessage;
import fun.fengwk.kkstudio.agent.message.AgentUserMessage;
import fun.fengwk.kkstudio.agent.provider.AssistantResponse;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandle;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandler;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.payload.AssistantDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * AgentAssistantRunner 负责一次 assistant attempt 的完整生命周期。
 *
 * <p>职责范围：
 *
 * <ul>
 *   <li>写 assistant_start 事件，合并 userMessages
 *   <li>调用 provider.asyncChat 启动流
 *   <li>处理流式 delta（text / thinking / toolCallDelta / toolCallComplete）
 *   <li>处理 complete（含 gap 补齐）+ toolCalls 路由
 *   <li>处理 error 事件（写 assistant_error event + 清状态）
 *   <li>取消时释放 handle
 * </ul>
 *
 * <p>不负责：
 *
 * <ul>
 *   <li>解析运行时配置（由 Agent 调用 AgentRuntimeConfigResolver 完成）
 *   <li>管理 retry task（由 Agent 持有）
 *   <li>决定是否 abort / 调度 retry（由 Agent 状态机决定）
 *   <li>维护 run 级状态（AgentRunContext 由 Agent 持有）
 * </ul>
 *
 * @author fengwk
 */
@Slf4j
final class AgentAssistantRunner {

  private final AgentSessionWriter writer;
  private final Consumer<AgentSignal> enqueueSignal;
  private final Consumer<List<ToolCall>> onToolCallsReady;

  AgentAssistantRunner(
      AgentSessionWriter writer,
      Consumer<AgentSignal> enqueueSignal,
      Consumer<List<ToolCall>> onToolCallsReady) {
    this.writer = requireNonNull(writer, "writer");
    this.enqueueSignal = requireNonNull(enqueueSignal, "enqueueSignal");
    this.onToolCallsReady = requireNonNull(onToolCallsReady, "onToolCallsReady");
  }

  /** 取消当前 attempt 的 provider handle（best-effort）。 */
  void cancelActive(AgentRunContext runContext) {
    AssistantResponseHandle handle =
        runContext == null || runContext.activeAssistant == null
            ? null
            : runContext.activeAssistant.handle;
    if (handle != null) {
      cancel(handle);
    }
  }

  /** 启动一次 assistant attempt。需要 Agent 先调用 AgentRuntimeConfigResolver 解析并持久化 config。 */
  void startAttempt(
      AgentRunContext runContext,
      AgentRuntimeConfigResolver.ResolvedRuntimeConfig runtimeConfig,
      List<String> userMessages) {
    if (runContext == null || runContext.aborted) {
      return;
    }
    requireNonNull(runtimeConfig, "runtimeConfig");

    runContext.resolvedTools = runtimeConfig.getResolvedTools();
    AssistantAttemptState attemptState = new AssistantAttemptState();
    runContext.activeAssistant = attemptState;

    List<AgentMessage> messagesForModel =
        withUserMessages(writer.getProjectedMessages(), userMessages);
    AssistantStartPayload assistantStartPayload = new AssistantStartPayload();
    assistantStartPayload.setUserMessages(userMessages);
    writer.appendEvent(SessionEventType.assistant_start, assistantStartPayload);

    try {
      AssistantResponseHandle handle =
          runtimeConfig
              .getProvider()
              .asyncChat(
                  messagesForModel,
                  runtimeConfig.getModelInfo(),
                  runtimeConfig.getVariant(),
                  runtimeConfig.getToolInfos(),
                  new RunnerResponseHandler(attemptState));
      if (handle == null) {
        enqueueAssistantErrorIfActive(
            runContext,
            attemptState,
            new IllegalStateException("provider asyncChat returned null handle"));
      } else if (isActiveAssistant(runContext, attemptState)) {
        attemptState.handle = handle;
      } else {
        cancel(handle);
      }
    } catch (RuntimeException error) {
      enqueueAssistantErrorIfActive(runContext, attemptState, error);
    }
  }

  void onTextDelta(AgentRunContext runContext, AssistantTextDeltaSignal signal) {
    if (!isActiveAssistant(runContext, signal.attemptState())
        || signal.textDelta() == null
        || signal.textDelta().isEmpty()) {
      return;
    }
    signal.attemptState().text.append(signal.textDelta());
    AssistantDeltaPayload payload = new AssistantDeltaPayload();
    payload.setTextDelta(signal.textDelta());
    writer.appendEvent(SessionEventType.assistant_delta, payload);
  }

  void onThinkingDelta(AgentRunContext runContext, AssistantThinkingDeltaSignal signal) {
    if (!isActiveAssistant(runContext, signal.attemptState())
        || signal.thinkingDelta() == null
        || signal.thinkingDelta().isEmpty()) {
      return;
    }
    signal.attemptState().thinking.append(signal.thinkingDelta());
    AssistantDeltaPayload payload = new AssistantDeltaPayload();
    payload.setThinkingDelta(signal.thinkingDelta());
    writer.appendEvent(SessionEventType.assistant_delta, payload);
  }

  void onToolCallDelta(AgentRunContext runContext, AssistantToolCallDeltaSignal signal) {
    if (!isActiveAssistant(runContext, signal.attemptState())
        || signal.toolCallDelta() == null
        || !isUsableToolCallIndex(signal.toolCallDelta().getIndex())) {
      return;
    }
    signal.attemptState().applyToolCallDelta(signal.toolCallDelta());
    AssistantDeltaPayload payload = new AssistantDeltaPayload();
    payload.setToolCallsDelta(List.of(signal.toolCallDelta()));
    writer.appendEvent(SessionEventType.assistant_delta, payload);
  }

  void onToolCallComplete(AgentRunContext runContext, AssistantToolCallCompleteSignal signal) {
    if (!isActiveAssistant(runContext, signal.attemptState())
        || !isUsableToolCallIndex(signal.index())
        || signal.toolCall() == null) {
      return;
    }
    List<IndexedToolCallDelta> gap =
        signal.attemptState().computeToolCallGap(signal.index(), signal.toolCall());
    if (!gap.isEmpty()) {
      AssistantDeltaPayload payload = new AssistantDeltaPayload();
      payload.setToolCallsDelta(gap);
      writer.appendEvent(SessionEventType.assistant_delta, payload);
    }
  }

  void onComplete(AgentRunContext runContext, AssistantCompleteSignal signal) {
    if (!isActiveAssistant(runContext, signal.attemptState())) {
      return;
    }
    AssistantResponse response =
        signal.response() == null ? AssistantResponse.builder().build() : signal.response();
    String toolCallError = validateToolCalls(response.getToolCalls());
    if (toolCallError != null) {
      enqueueAssistantErrorIfActive(
          runContext, signal.attemptState(), new IllegalArgumentException(toolCallError));
      return;
    }
    runContext.activeAssistant = null;
    signal.attemptState().handle = null;

    appendAssistantCompletionGap(signal.attemptState(), response);

    AssistantEndPayload assistantEndPayload = new AssistantEndPayload();
    assistantEndPayload.setMetadata(response.getMetadata());
    writer.appendEvent(SessionEventType.assistant_end, assistantEndPayload);

    runContext.retryCount = 0;
    List<ToolCall> toolCalls =
        response.getToolCalls() == null ? List.of() : response.getToolCalls();
    onToolCallsReady.accept(toolCalls);
  }

  /**
   * 写入 error 并关闭当前 attempt。
   *
   * @return 本信号是否属于当前活跃 attempt
   */
  boolean onError(AgentRunContext runContext, AssistantErrorSignal signal) {
    if (!isActiveAssistant(runContext, signal.attemptState())) {
      return false;
    }
    runContext.activeAssistant = null;
    signal.attemptState().handle = null;

    writer.appendEvent(
        SessionEventType.assistant_error, newAssistantErrorPayload(toErrorMessage(signal.error())));
    return true;
  }

  // --- internal ---

  private static String validateToolCalls(List<ToolCall> toolCalls) {
    if (toolCalls == null || toolCalls.isEmpty()) {
      return null;
    }
    Set<String> toolCallIds = new HashSet<>();
    for (int index = 0; index < toolCalls.size(); index++) {
      ToolCall toolCall = toolCalls.get(index);
      if (toolCall == null) {
        return "assistant tool call must not be null: index=" + index;
      }
      if (toolCall.getToolCallId() == null || toolCall.getToolCallId().isBlank()) {
        return "assistant tool call id must not be blank: index=" + index;
      }
      if (toolCall.getToolName() == null || toolCall.getToolName().isBlank()) {
        return "assistant tool name must not be blank: index=" + index;
      }
      if (!toolCallIds.add(toolCall.getToolCallId())) {
        return "assistant tool call id must be unique: " + toolCall.getToolCallId();
      }
    }
    return null;
  }

  private void appendAssistantCompletionGap(
      AssistantAttemptState attemptState, AssistantResponse response) {
    List<IndexedToolCallDelta> toolCallGaps =
        attemptState.computeToolCallGaps(response.getToolCalls());
    String textGap = AgentTextDelta.gap(attemptState.text.toString(), response.getText());
    String thinkingGap =
        AgentTextDelta.gap(attemptState.thinking.toString(), response.getThinking());
    if ((textGap == null || textGap.isEmpty())
        && (thinkingGap == null || thinkingGap.isEmpty())
        && toolCallGaps.isEmpty()) {
      return;
    }
    AssistantDeltaPayload payload = new AssistantDeltaPayload();
    if (textGap != null && !textGap.isEmpty()) {
      attemptState.text.append(textGap);
      payload.setTextDelta(textGap);
    }
    if (thinkingGap != null && !thinkingGap.isEmpty()) {
      attemptState.thinking.append(thinkingGap);
      payload.setThinkingDelta(thinkingGap);
    }
    if (!toolCallGaps.isEmpty()) {
      payload.setToolCallsDelta(toolCallGaps);
    }
    writer.appendEvent(SessionEventType.assistant_delta, payload);
  }

  private static List<AgentMessage> withUserMessages(
      List<AgentMessage> messages, List<String> userMessages) {
    if (userMessages == null || userMessages.isEmpty()) {
      return List.copyOf(messages);
    }
    List<AgentMessage> result = new ArrayList<>(messages);
    for (String userMessage : userMessages) {
      if (userMessage != null && !userMessage.isBlank()) {
        result.add(new AgentUserMessage(userMessage));
      }
    }
    return List.copyOf(result);
  }

  private void enqueueAssistantErrorIfActive(
      AgentRunContext runContext, AssistantAttemptState attemptState, RuntimeException error) {
    if (isActiveAssistant(runContext, attemptState)) {
      enqueueSignal.accept(new AssistantErrorSignal(attemptState, error));
    }
  }

  private void cancel(AssistantResponseHandle handle) {
    try {
      handle.cancel();
    } catch (RuntimeException error) {
      log.warn("[agent] assistant cancel failed", error);
    }
  }

  private static boolean isActiveAssistant(
      AgentRunContext runContext, AssistantAttemptState attemptState) {
    return runContext != null && runContext.activeAssistant == attemptState && !runContext.aborted;
  }

  private static boolean isUsableToolCallIndex(Integer index) {
    return index != null && index >= 0;
  }

  private static AssistantErrorPayload newAssistantErrorPayload(String message) {
    AssistantErrorPayload payload = new AssistantErrorPayload();
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

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }

  /** AssistantResponseHandler 把 provider 回调包成 AgentSignal 入队。 不持有 Agent 引用，避免反向耦合。 */
  private final class RunnerResponseHandler implements AssistantResponseHandler {

    private final AssistantAttemptState attemptState;

    RunnerResponseHandler(AssistantAttemptState attemptState) {
      this.attemptState = attemptState;
    }

    @Override
    public void onTextDelta(String textDelta, AssistantResponseHandle handle) {
      enqueueSignal.accept(new AssistantTextDeltaSignal(attemptState, textDelta));
    }

    @Override
    public void onThinkingDelta(String thinkingDelta, AssistantResponseHandle handle) {
      enqueueSignal.accept(new AssistantThinkingDeltaSignal(attemptState, thinkingDelta));
    }

    @Override
    public void onToolCallDelta(
        IndexedToolCallDelta toolCallDelta, AssistantResponseHandle handle) {
      enqueueSignal.accept(new AssistantToolCallDeltaSignal(attemptState, toolCallDelta));
    }

    @Override
    public void onToolCallComplete(
        Integer index, ToolCall toolCall, AssistantResponseHandle handle) {
      enqueueSignal.accept(new AssistantToolCallCompleteSignal(attemptState, index, toolCall));
    }

    @Override
    public void onComplete(AssistantResponse response, AssistantResponseHandle handle) {
      enqueueSignal.accept(new AssistantCompleteSignal(attemptState, response));
    }

    @Override
    public void onError(Throwable error, AssistantResponseHandle handle) {
      enqueueSignal.accept(new AssistantErrorSignal(attemptState, error));
    }
  }
}
