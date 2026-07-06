package fun.fengwk.kkstudio.agent.session.projection;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.agent.message.AgentAssistantMessage;
import fun.fengwk.kkstudio.agent.message.AgentMessage;
import fun.fengwk.kkstudio.agent.message.AgentSystemMessage;
import fun.fengwk.kkstudio.agent.message.AgentToolMessage;
import fun.fengwk.kkstudio.agent.message.AgentUserMessage;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.payload.AbortPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.session.payload.ToolDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolStartPayload;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

/**
 * SessionEventMessageProjector 的默认实现。
 *
 * <p>语义说明： - 默认实现按事件栈闭合 assistant / tool 生命周期。 - assistant 与 tool 的完整内容都由 delta 重放得到。 - end
 * 事件负责闭合边界；assistant_error / tool_error / abort 负责异常闭合并追加投影消息。 - set_agent_info / set_model_info
 * 负责更新当前配置事实。
 *
 * @author fengwk
 */
@Slf4j
public class DefaultSessionEventMessageProjector implements SessionEventMessageProjector {

  private static final String ASSISTANT_INTERRUPTED_MESSAGE = "[assistant response interrupted]";
  private static final String TOOL_INTERRUPTED_MESSAGE = "[tool execution interrupted]";

  @Override
  public SessionEventProjection project(List<SessionEvent> branchEvents) {
    return project(branchEvents, true);
  }

  @Override
  public SessionEventProjection projectForRuntime(List<SessionEvent> branchEvents) {
    return project(branchEvents, false);
  }

  private SessionEventProjection project(List<SessionEvent> branchEvents, boolean closeOpenStates) {
    if (branchEvents == null) {
      throw new IllegalArgumentException("branchEvents must not be null");
    }
    if (branchEvents.isEmpty()) {
      return new SessionEventProjection(null, null, List.of());
    }

    List<AgentMessage> chatMessages = new ArrayList<>();
    SetAgentInfoPayload agentInfo = null;
    SetModelInfoPayload modelInfo = null;
    Deque<OpenState> openStates = new ArrayDeque<>();
    for (SessionEvent branchEvent : branchEvents) {
      if (branchEvent == null) {
        warn(null, "null_event");
        continue;
      }
      SessionEventType eventType = branchEvent.getEventType();
      if (eventType == null) {
        warn(branchEvent, "missing_event_type");
        continue;
      }

      if (eventType == SessionEventType.set_agent_info) {
        agentInfo = onSetAgentInfo(branchEvent, agentInfo);
      } else if (eventType == SessionEventType.set_model_info) {
        modelInfo = onSetModelInfo(branchEvent, modelInfo);
      } else if (eventType == SessionEventType.assistant_start) {
        onAssistantStart(branchEvent, openStates, chatMessages);
      } else if (eventType == SessionEventType.assistant_delta) {
        onAssistantDelta(branchEvent, openStates);
      } else if (eventType == SessionEventType.assistant_end) {
        onAssistantEnd(branchEvent, openStates, chatMessages);
      } else if (eventType == SessionEventType.tool_start) {
        onToolStart(branchEvent, openStates);
      } else if (eventType == SessionEventType.tool_delta) {
        onToolDelta(branchEvent, openStates);
      } else if (eventType == SessionEventType.tool_end) {
        onToolEnd(branchEvent, openStates, chatMessages);
      } else if (eventType == SessionEventType.assistant_error) {
        onAssistantError(branchEvent, openStates, chatMessages);
      } else if (eventType == SessionEventType.tool_error) {
        onToolError(branchEvent, openStates, chatMessages);
      } else if (eventType == SessionEventType.abort) {
        onAbort(branchEvent, openStates, chatMessages);
      }
    }

    if (closeOpenStates) {
      projectInterruptedOpenStates(openStates, chatMessages);
    }

    if (agentInfo != null
        && agentInfo.getSystemPrompt() != null
        && !agentInfo.getSystemPrompt().isBlank()) {
      chatMessages.add(0, new AgentSystemMessage(agentInfo.getSystemPrompt()));
    }

    return new SessionEventProjection(agentInfo, modelInfo, List.copyOf(chatMessages));
  }

  private SetAgentInfoPayload onSetAgentInfo(
      SessionEvent event, SetAgentInfoPayload currentAgentInfo) {
    if (event.getPayload() instanceof SetAgentInfoPayload payload) {
      return payload;
    }
    warn(event, "invalid_set_agent_info_payload");
    return currentAgentInfo;
  }

  private SetModelInfoPayload onSetModelInfo(
      SessionEvent event, SetModelInfoPayload currentModelInfo) {
    if (event.getPayload() instanceof SetModelInfoPayload payload) {
      return payload;
    }
    warn(event, "invalid_set_model_info_payload");
    return currentModelInfo;
  }

  private void onAssistantStart(
      SessionEvent event, Deque<OpenState> openStates, List<AgentMessage> chatMessages) {
    if (event.getPayload() instanceof AssistantStartPayload payload) {
      appendUserMessages(event, payload, chatMessages);
    } else {
      warn(event, "invalid_assistant_start_payload");
    }
    openStates.addLast(new AssistantState(event));
  }

  private void appendUserMessages(
      SessionEvent event, AssistantStartPayload payload, List<AgentMessage> chatMessages) {
    if (payload.getUserMessages() == null) {
      return;
    }
    for (String userMessage : payload.getUserMessages()) {
      if (userMessage == null) {
        warn(event, "null_user_message");
        continue;
      }
      chatMessages.add(new AgentUserMessage(userMessage));
    }
  }

  private void onAssistantDelta(SessionEvent event, Deque<OpenState> openStates) {
    if (!(event.getPayload() instanceof AssistantDeltaPayload payload)) {
      warn(event, "invalid_assistant_delta_payload");
      return;
    }
    AssistantState assistantState = findLastAssistantState(openStates);
    if (assistantState == null) {
      warn(event, "orphan_assistant_delta");
      return;
    }
    if (isEmptyAssistantDelta(payload)) {
      warn(event, "empty_assistant_delta");
      return;
    }

    if (payload.getTextDelta() != null) {
      assistantState.text.append(payload.getTextDelta());
    }
    if (payload.getThinkingDelta() != null) {
      assistantState.thinking.append(payload.getThinkingDelta());
    }
    if (payload.getToolCallsDelta() != null) {
      for (IndexedToolCallDelta indexedToolCallDelta : payload.getToolCallsDelta()) {
        applyToolCallDelta(event, assistantState, indexedToolCallDelta);
      }
    }
  }

  private boolean isEmptyAssistantDelta(AssistantDeltaPayload payload) {
    return payload.getTextDelta() == null
        && payload.getThinkingDelta() == null
        && (payload.getToolCallsDelta() == null || payload.getToolCallsDelta().isEmpty());
  }

  private void onAssistantEnd(
      SessionEvent event, Deque<OpenState> openStates, List<AgentMessage> chatMessages) {
    if (!(event.getPayload() instanceof AssistantEndPayload)) {
      warn(event, "invalid_assistant_end_payload");
      return;
    }
    AssistantState assistantState = removeLastAssistantState(openStates);
    if (assistantState == null) {
      warn(event, "orphan_assistant_end");
      return;
    }
    chatMessages.add(buildAssistantMessage(event, assistantState));
    chatMessages.addAll(assistantState.delayedToolMessages);
  }

  private void onToolStart(SessionEvent event, Deque<OpenState> openStates) {
    if (!(event.getPayload() instanceof ToolStartPayload payload)) {
      warn(event, "invalid_tool_start_payload");
      return;
    }
    if (isBlank(payload.getToolCallId()) || isBlank(payload.getToolName())) {
      warn(event, "invalid_tool_start_identity");
      return;
    }
    openStates.addLast(new ToolState(event, payload.getToolCallId(), payload.getToolName()));
  }

  private void onToolDelta(SessionEvent event, Deque<OpenState> openStates) {
    if (!(event.getPayload() instanceof ToolDeltaPayload payload)) {
      warn(event, "invalid_tool_delta_payload");
      return;
    }
    if (isBlank(payload.getToolCallId())) {
      warn(event, "missing_tool_delta_tool_call_id");
      return;
    }
    ToolState toolState = findToolState(openStates, payload.getToolCallId());
    if (toolState == null) {
      warn(event, "orphan_tool_delta");
      return;
    }
    if (payload.getContentDeltas() == null || payload.getContentDeltas().isEmpty()) {
      warn(event, "empty_tool_delta");
      return;
    }
    for (IndexedToolContentDelta indexedToolContentDelta : payload.getContentDeltas()) {
      applyToolContentDelta(event, toolState, indexedToolContentDelta);
    }
  }

  private void onToolEnd(
      SessionEvent event, Deque<OpenState> openStates, List<AgentMessage> chatMessages) {
    if (!(event.getPayload() instanceof ToolEndPayload payload)) {
      warn(event, "invalid_tool_end_payload");
      return;
    }
    if (isBlank(payload.getToolCallId())) {
      warn(event, "missing_tool_end_tool_call_id");
      return;
    }
    AssistantState ownerAssistant = findOwningAssistantState(openStates, payload.getToolCallId());
    ToolState toolState = removeToolState(openStates, payload.getToolCallId());
    if (toolState == null) {
      warn(event, "orphan_tool_end");
      return;
    }
    appendToolResultMessage(event, ownerAssistant, toolState, chatMessages);
  }

  private void onAssistantError(
      SessionEvent event, Deque<OpenState> openStates, List<AgentMessage> chatMessages) {
    String message = null;
    if (event.getPayload() instanceof AssistantErrorPayload payload) {
      message = payload.getMessage();
    } else {
      warn(event, "invalid_assistant_error_payload");
    }
    AssistantState assistantState = removeLastAssistantState(openStates);
    if (assistantState == null) {
      warn(event, "orphan_assistant_error");
      return;
    }
    appendAssistantClosingMessage(assistantState, message);
    chatMessages.add(buildAssistantMessage(event, assistantState));
    chatMessages.addAll(assistantState.delayedToolMessages);
  }

  private void onToolError(
      SessionEvent event, Deque<OpenState> openStates, List<AgentMessage> chatMessages) {
    if (!(event.getPayload() instanceof ToolErrorPayload payload)) {
      warn(event, "invalid_tool_error_payload");
      return;
    }
    if (isBlank(payload.getToolCallId())) {
      warn(event, "missing_tool_error_tool_call_id");
      return;
    }
    AssistantState ownerAssistant = findOwningAssistantState(openStates, payload.getToolCallId());
    ToolState toolState = removeToolState(openStates, payload.getToolCallId());
    if (toolState == null) {
      warn(event, "orphan_tool_error");
      return;
    }
    appendToolClosingMessage(toolState, payload.getMessage());
    appendToolResultMessage(event, ownerAssistant, toolState, chatMessages, true);
  }

  private void onAbort(
      SessionEvent event, Deque<OpenState> openStates, List<AgentMessage> chatMessages) {
    if (!(event.getPayload() instanceof AbortPayload)) {
      warn(event, "invalid_abort_payload");
    }
    closeInterruptedOpenStates(openStates, chatMessages, false);
  }

  private void projectInterruptedOpenStates(
      Deque<OpenState> openStates, List<AgentMessage> chatMessages) {
    closeInterruptedOpenStates(openStates, chatMessages, true);
  }

  private void closeInterruptedOpenStates(
      Deque<OpenState> openStates, List<AgentMessage> chatMessages, boolean warnInterrupted) {
    while (!openStates.isEmpty()) {
      OpenState openState = openStates.pollFirst();
      if (openState instanceof AssistantState assistantState) {
        if (warnInterrupted) {
          warn(assistantState.startEvent, "interrupted_assistant_open_state");
        }
        appendAssistantClosingMessage(assistantState, ASSISTANT_INTERRUPTED_MESSAGE);
        chatMessages.add(buildAssistantMessage(assistantState.startEvent, assistantState));
        chatMessages.addAll(assistantState.delayedToolMessages);
      } else if (openState instanceof ToolState toolState) {
        if (warnInterrupted) {
          warn(toolState.startEvent, "interrupted_tool_open_state");
        }
        appendToolClosingMessage(toolState, TOOL_INTERRUPTED_MESSAGE);
        chatMessages.add(buildToolResultMessage(toolState.startEvent, toolState));
      }
    }
  }

  private AssistantState findLastAssistantState(Deque<OpenState> openStates) {
    for (Iterator<OpenState> iterator = openStates.descendingIterator(); iterator.hasNext(); ) {
      OpenState openState = iterator.next();
      if (openState instanceof AssistantState assistantState) {
        return assistantState;
      }
    }
    return null;
  }

  private AssistantState findOwningAssistantState(Deque<OpenState> openStates, String toolCallId) {
    if (isBlank(toolCallId)) {
      return null;
    }
    boolean foundTool = false;
    for (Iterator<OpenState> iterator = openStates.descendingIterator(); iterator.hasNext(); ) {
      OpenState openState = iterator.next();
      if (!foundTool) {
        if (openState instanceof ToolState toolState && toolCallId.equals(toolState.toolCallId)) {
          foundTool = true;
        }
        continue;
      }
      if (openState instanceof AssistantState assistantState) {
        return assistantState;
      }
    }
    return null;
  }

  private AssistantState removeLastAssistantState(Deque<OpenState> openStates) {
    Deque<OpenState> buffer = new ArrayDeque<>();
    AssistantState result = null;
    while (!openStates.isEmpty()) {
      OpenState openState = openStates.pollLast();
      if (openState instanceof AssistantState assistantState) {
        result = assistantState;
        break;
      }
      buffer.addFirst(openState);
    }
    while (!buffer.isEmpty()) {
      openStates.addLast(buffer.pollFirst());
    }
    return result;
  }

  private ToolState findToolState(Deque<OpenState> openStates, String toolCallId) {
    if (isBlank(toolCallId)) {
      return null;
    }
    for (Iterator<OpenState> iterator = openStates.descendingIterator(); iterator.hasNext(); ) {
      OpenState openState = iterator.next();
      if (openState instanceof ToolState toolState && toolCallId.equals(toolState.toolCallId)) {
        return toolState;
      }
    }
    return null;
  }

  private ToolState removeToolState(Deque<OpenState> openStates, String toolCallId) {
    if (isBlank(toolCallId)) {
      return null;
    }
    Deque<OpenState> buffer = new ArrayDeque<>();
    ToolState result = null;
    while (!openStates.isEmpty()) {
      OpenState openState = openStates.pollLast();
      if (openState instanceof ToolState toolState && toolCallId.equals(toolState.toolCallId)) {
        result = toolState;
        break;
      }
      buffer.addFirst(openState);
    }
    while (!buffer.isEmpty()) {
      openStates.addLast(buffer.pollFirst());
    }
    return result;
  }

  private void applyToolCallDelta(
      SessionEvent event,
      AssistantState assistantState,
      IndexedToolCallDelta indexedToolCallDelta) {
    if (indexedToolCallDelta == null) {
      warn(event, "null_tool_call_delta_item");
      return;
    }
    if (indexedToolCallDelta.getIndex() == null) {
      warn(event, "missing_tool_call_delta_index");
      return;
    }
    if (indexedToolCallDelta.getToolCallDelta() == null) {
      warn(event, "missing_tool_call_delta_payload");
      return;
    }
    ToolCallDelta toolCallDelta = indexedToolCallDelta.getToolCallDelta();
    if (toolCallDelta.getToolCallId() == null
        && toolCallDelta.getToolName() == null
        && toolCallDelta.getArgumentsDelta() == null) {
      warn(event, "empty_tool_call_delta_item");
      return;
    }

    ToolCallState toolCallState =
        assistantState.toolCalls.computeIfAbsent(
            indexedToolCallDelta.getIndex(), key -> new ToolCallState());
    applyStableField(
        event,
        "tool_call_id_conflict",
        toolCallState.toolCallId,
        toolCallDelta.getToolCallId(),
        value -> toolCallState.toolCallId = value);
    applyStableField(
        event,
        "tool_name_conflict",
        toolCallState.toolName,
        toolCallDelta.getToolName(),
        value -> toolCallState.toolName = value);
    if (toolCallDelta.getArgumentsDelta() != null) {
      toolCallState.arguments.append(toolCallDelta.getArgumentsDelta());
    }
  }

  private void applyStableField(
      SessionEvent event,
      String conflictReason,
      String currentValue,
      String newValue,
      StableFieldSetter setter) {
    if (newValue == null) {
      return;
    }
    if (currentValue == null) {
      setter.set(newValue);
      return;
    }
    if (!currentValue.equals(newValue)) {
      warn(event, conflictReason);
    }
  }

  private void applyToolContentDelta(
      SessionEvent event, ToolState toolState, IndexedToolContentDelta indexedToolContentDelta) {
    if (indexedToolContentDelta == null) {
      warn(event, "null_tool_content_delta_item");
      return;
    }
    if (indexedToolContentDelta.getIndex() == null) {
      warn(event, "missing_tool_content_delta_index");
      return;
    }
    if (indexedToolContentDelta.getContentDelta() == null) {
      warn(event, "missing_tool_content_delta_payload");
      return;
    }
    ToolContentDelta contentDelta = indexedToolContentDelta.getContentDelta();
    if (contentDelta.getType() == null) {
      warn(event, "missing_tool_content_type");
      return;
    }
    if (contentDelta.getType() == ToolContentType.text) {
      applyTextContentDelta(event, toolState, indexedToolContentDelta.getIndex(), contentDelta);
    } else {
      applyMediaContentDelta(event, toolState, indexedToolContentDelta.getIndex(), contentDelta);
    }
  }

  private void applyTextContentDelta(
      SessionEvent event, ToolState toolState, Integer index, ToolContentDelta contentDelta) {
    if (contentDelta.getText() == null) {
      warn(event, "missing_text_content_delta");
      return;
    }
    ToolContentAccumulator accumulator =
        getOrCreateContentAccumulator(event, toolState, index, ToolContentType.text);
    if (accumulator == null) {
      return;
    }
    accumulator.text.append(contentDelta.getText());
  }

  private void applyMediaContentDelta(
      SessionEvent event, ToolState toolState, Integer index, ToolContentDelta contentDelta) {
    ToolContentAccumulator accumulator =
        getOrCreateContentAccumulator(event, toolState, index, contentDelta.getType());
    if (accumulator == null) {
      return;
    }
    setMediaField(
        event,
        "media_data_conflict",
        accumulator.data,
        contentDelta.getData(),
        value -> accumulator.data = value);
    setMediaField(
        event,
        "media_mime_conflict",
        accumulator.mime,
        contentDelta.getMime(),
        value -> accumulator.mime = value);
    setMediaField(
        event,
        "media_name_conflict",
        accumulator.name,
        contentDelta.getName(),
        value -> accumulator.name = value);
  }

  private ToolContentAccumulator getOrCreateContentAccumulator(
      SessionEvent event, ToolState toolState, Integer index, ToolContentType type) {
    ToolContentAccumulator accumulator = toolState.contents.get(index);
    if (accumulator == null) {
      accumulator = new ToolContentAccumulator(type);
      toolState.contents.put(index, accumulator);
      return accumulator;
    }
    if (accumulator.type != type) {
      warn(event, "tool_content_type_conflict");
      return null;
    }
    return accumulator;
  }

  private void setMediaField(
      SessionEvent event,
      String conflictReason,
      String currentValue,
      String newValue,
      StableFieldSetter setter) {
    if (newValue == null) {
      return;
    }
    if (currentValue == null) {
      setter.set(newValue);
      return;
    }
    if (!currentValue.equals(newValue)) {
      warn(event, conflictReason);
    }
  }

  private void appendAssistantClosingMessage(AssistantState assistantState, String closingMessage) {
    if (closingMessage == null || closingMessage.isBlank()) {
      return;
    }
    if (!assistantState.text.isEmpty()) {
      assistantState.text.append(System.lineSeparator());
    }
    assistantState.text.append(closingMessage);
  }

  private void appendToolClosingMessage(ToolState toolState, String closingMessage) {
    if (closingMessage == null || closingMessage.isBlank()) {
      return;
    }
    ToolContentAccumulator last =
        toolState.contents.isEmpty() ? null : toolState.contents.lastEntry().getValue();
    if (last != null && last.type == ToolContentType.text) {
      if (!last.text.isEmpty()) {
        last.text.append(System.lineSeparator());
      }
      last.text.append(closingMessage);
      return;
    }
    int nextIndex = toolState.contents.isEmpty() ? 0 : toolState.contents.lastKey() + 1;
    ToolContentAccumulator accumulator = new ToolContentAccumulator(ToolContentType.text);
    accumulator.text.append(closingMessage);
    toolState.contents.put(nextIndex, accumulator);
  }

  private AgentAssistantMessage buildAssistantMessage(
      SessionEvent event, AssistantState assistantState) {
    List<ToolCall> toolCalls = new ArrayList<>();
    for (ToolCallState toolCallState : assistantState.toolCalls.values()) {
      ToolCall toolCall = toToolCall(event, toolCallState);
      if (toolCall != null) {
        toolCalls.add(toolCall);
      }
    }
    String text = assistantState.text.isEmpty() ? null : assistantState.text.toString();
    String thinking = assistantState.thinking.isEmpty() ? null : assistantState.thinking.toString();
    String projectedText = text != null || toolCalls.isEmpty() ? text == null ? "" : text : null;
    return new AgentAssistantMessage(projectedText, thinking, toolCalls);
  }

  private ToolCall toToolCall(SessionEvent event, ToolCallState toolCallState) {
    if (isBlank(toolCallState.toolCallId) || isBlank(toolCallState.toolName)) {
      warn(event, "incomplete_tool_call_skipped");
      return null;
    }
    ToolCall toolCall = new ToolCall();
    toolCall.setToolCallId(toolCallState.toolCallId);
    toolCall.setToolName(toolCallState.toolName);
    toolCall.setArguments(toolCallState.arguments.toString());
    return toolCall;
  }

  private AgentToolMessage buildToolResultMessage(SessionEvent event, ToolState toolState) {
    return buildToolResultMessage(event, toolState, false);
  }

  private AgentToolMessage buildToolResultMessage(
      SessionEvent event, ToolState toolState, boolean isError) {
    List<ToolContent> contents = new ArrayList<>();
    for (ToolContentAccumulator accumulator : toolState.contents.values()) {
      ToolContent content = toContent(event, accumulator);
      if (content != null) {
        contents.add(content);
      }
    }
    if (contents.isEmpty()) {
      contents.add(newTextContent(""));
    }
    return new AgentToolMessage(toolState.toolCallId, toolState.toolName, contents, isError);
  }

  private void appendToolResultMessage(
      SessionEvent event,
      AssistantState ownerAssistant,
      ToolState toolState,
      List<AgentMessage> chatMessages) {
    appendToolResultMessage(event, ownerAssistant, toolState, chatMessages, false);
  }

  private void appendToolResultMessage(
      SessionEvent event,
      AssistantState ownerAssistant,
      ToolState toolState,
      List<AgentMessage> chatMessages,
      boolean isError) {
    AgentToolMessage message = buildToolResultMessage(event, toolState, isError);
    if (ownerAssistant == null) {
      chatMessages.add(message);
      return;
    }
    ownerAssistant.delayedToolMessages.add(message);
  }

  private ToolContent toContent(SessionEvent event, ToolContentAccumulator accumulator) {
    if (accumulator.type == ToolContentType.text) {
      return newTextContent(accumulator.text.toString());
    }
    if (isInvalidMedia(accumulator)) {
      warn(event, "invalid_media_content_skipped");
      return null;
    }
    ToolContent content = new ToolContent();
    content.setType(accumulator.type);
    content.setData(accumulator.data);
    content.setMime(accumulator.mime);
    content.setName(accumulator.name);
    return content;
  }

  private ToolContent newTextContent(String text) {
    ToolContent content = new ToolContent();
    content.setType(ToolContentType.text);
    content.setText(text);
    return content;
  }

  private boolean isInvalidMedia(ToolContentAccumulator accumulator) {
    if (accumulator.data == null
        || accumulator.data.isBlank()
        || accumulator.mime == null
        || accumulator.mime.isBlank()) {
      return true;
    }
    if (accumulator.type == ToolContentType.image) {
      return !accumulator.mime.startsWith("image/");
    }
    if (accumulator.type == ToolContentType.audio) {
      return !accumulator.mime.startsWith("audio/");
    }
    if (accumulator.type == ToolContentType.video) {
      return !accumulator.mime.startsWith("video/");
    }
    return true;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private void warn(SessionEvent event, String reason) {
    log.warn(
        "[session-projection] recover malformed session event: sessionId={} eventId={} eventType={} reason={}",
        event == null ? null : event.getSessionId(),
        event == null ? null : event.getEventId(),
        event == null ? null : event.getEventType(),
        reason);
  }

  private sealed interface OpenState permits AssistantState, ToolState {}

  private static final class AssistantState implements OpenState {
    private final SessionEvent startEvent;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final TreeMap<Integer, ToolCallState> toolCalls = new TreeMap<>();
    private final List<AgentMessage> delayedToolMessages = new ArrayList<>();

    private AssistantState(SessionEvent startEvent) {
      this.startEvent = startEvent;
    }
  }

  private static final class ToolState implements OpenState {
    private final SessionEvent startEvent;
    private final String toolCallId;
    private final String toolName;
    private final TreeMap<Integer, ToolContentAccumulator> contents = new TreeMap<>();

    private ToolState(SessionEvent startEvent, String toolCallId, String toolName) {
      this.startEvent = startEvent;
      this.toolCallId = toolCallId;
      this.toolName = toolName;
    }
  }

  private static final class ToolCallState {
    private String toolCallId;
    private String toolName;
    private final StringBuilder arguments = new StringBuilder();
  }

  private static final class ToolContentAccumulator {
    private final ToolContentType type;
    private final StringBuilder text = new StringBuilder();
    private String data;
    private String mime;
    private String name;

    private ToolContentAccumulator(ToolContentType type) {
      this.type = type;
    }
  }

  private interface StableFieldSetter {

    void set(String value);
  }
}
