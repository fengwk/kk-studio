package fun.fengwk.kkstudio.agent.runtime;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.data.message.SystemMessage;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.payload.AbortPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.ErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.session.payload.ToolDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolEndPayload;
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
 * 语义说明：
 * - 默认实现按事件栈闭合 assistant / tool 生命周期。
 * - assistant 与 tool 的完整内容都由 delta 重放得到。
 * - end 事件负责闭合边界；error / abort 负责异常闭合并追加投影消息。
 * - set_agent_info / set_model_info 负责更新当前配置事实。
 *
 * @author fengwk
 */
public class DefaultSessionEventMessageProjector implements SessionEventMessageProjector {

    private final ToolCallMapper toolCallMapper = new ToolCallMapper();

    @Override
    public SessionEventProjection project(List<SessionEvent> branchEvents) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        SetAgentInfoPayload agentInfo = null;
        SetModelInfoPayload modelInfo = null;
        if (branchEvents == null || branchEvents.isEmpty()) {
            return new SessionEventProjection(null, null, chatMessages);
        }

        Deque<OpenState> openStates = new ArrayDeque<>();
        for (SessionEvent branchEvent : branchEvents) {
            if (branchEvent == null) {
                continue;
            }
            SessionEventType eventType = branchEvent.getEventType();
            if (eventType == null) {
                continue;
            }

            switch (eventType) {
                case set_agent_info -> agentInfo = branchEvent.getPayload() instanceof SetAgentInfoPayload payload ? payload : agentInfo;
                case set_model_info -> modelInfo = branchEvent.getPayload() instanceof SetModelInfoPayload payload ? payload : modelInfo;
                case assistant_start -> onAssistantStart(branchEvent, openStates, chatMessages);
                case assistant_delta -> onAssistantDelta(branchEvent, openStates);
                case assistant_end -> onAssistantEnd(openStates, chatMessages);
                case tool_start -> onToolStart(branchEvent, openStates);
                case tool_delta -> onToolDelta(branchEvent, openStates);
                case tool_end -> onToolEnd(branchEvent, openStates, chatMessages);
                case error -> onError(branchEvent, openStates, chatMessages);
                case abort -> onAbort(branchEvent, openStates, chatMessages);
            }
        }

        if (agentInfo != null && agentInfo.getSystemPrompt() != null && !agentInfo.getSystemPrompt().isBlank()) {
            chatMessages.add(0, SystemMessage.from(agentInfo.getSystemPrompt()));
        }

        return new SessionEventProjection(agentInfo, modelInfo, List.copyOf(chatMessages));
    }

    private void onAssistantStart(SessionEvent event, Deque<OpenState> openStates, List<ChatMessage> chatMessages) {
        if (event.getPayload() instanceof AssistantStartPayload payload && payload.getUserMessages() != null) {
            for (String userMessage : payload.getUserMessages()) {
                if (userMessage != null) {
                    chatMessages.add(UserMessage.userMessage(userMessage));
                }
            }
        }
        openStates.addLast(new AssistantState());
    }

    private void onAssistantDelta(SessionEvent event, Deque<OpenState> openStates) {
        if (!(event.getPayload() instanceof AssistantDeltaPayload payload)) {
            return;
        }
        AssistantState assistantState = findLastAssistantState(openStates);
        if (assistantState == null) {
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
                applyToolCallDelta(assistantState, indexedToolCallDelta);
            }
        }
    }

    private void onAssistantEnd(Deque<OpenState> openStates, List<ChatMessage> chatMessages) {
        AssistantState assistantState = removeLastAssistantState(openStates);
        if (assistantState == null) {
            return;
        }
        chatMessages.add(buildAiMessage(assistantState));
    }

    private void onToolStart(SessionEvent event, Deque<OpenState> openStates) {
        if (!(event.getPayload() instanceof ToolStartPayload payload)) {
            return;
        }
        ToolState toolState = new ToolState();
        toolState.toolCallId = payload.getToolCallId();
        toolState.toolName = payload.getToolName();
        openStates.addLast(toolState);
    }

    private void onToolDelta(SessionEvent event, Deque<OpenState> openStates) {
        if (!(event.getPayload() instanceof ToolDeltaPayload payload)) {
            return;
        }
        ToolState toolState = findToolState(openStates, payload.getToolCallId());
        if (toolState == null || payload.getContentDeltas() == null) {
            return;
        }
        for (IndexedToolContentDelta indexedToolContentDelta : payload.getContentDeltas()) {
            applyToolContentDelta(toolState, indexedToolContentDelta);
        }
    }

    private void onToolEnd(SessionEvent event, Deque<OpenState> openStates, List<ChatMessage> chatMessages) {
        if (!(event.getPayload() instanceof ToolEndPayload payload)) {
            return;
        }
        ToolState toolState = removeToolState(openStates, payload.getToolCallId());
        if (toolState == null) {
            return;
        }
        chatMessages.add(buildToolResultMessage(toolState));
    }

    private void onError(SessionEvent event, Deque<OpenState> openStates, List<ChatMessage> chatMessages) {
        String message = event.getPayload() instanceof ErrorPayload payload ? payload.getMessage() : null;
        String toolCallId = event.getPayload() instanceof ErrorPayload payload ? payload.getToolCallId() : null;
        closeWithMessage(openStates, chatMessages, message, toolCallId);
    }

    private void onAbort(SessionEvent event, Deque<OpenState> openStates, List<ChatMessage> chatMessages) {
        String reason = event.getPayload() instanceof AbortPayload payload ? payload.getReason() : null;
        String toolCallId = event.getPayload() instanceof AbortPayload payload ? payload.getToolCallId() : null;
        closeWithMessage(openStates, chatMessages, reason, toolCallId);
    }

    private void closeWithMessage(Deque<OpenState> openStates, List<ChatMessage> chatMessages, String closingMessage, String toolCallId) {
        OpenState openState = toolCallId == null || toolCallId.isBlank()
            ? openStates.pollLast()
            : removeToolState(openStates, toolCallId);
        if (openState instanceof AssistantState assistantState) {
            appendAssistantClosingMessage(assistantState, closingMessage);
            chatMessages.add(buildAiMessage(assistantState));
        } else if (openState instanceof ToolState toolState) {
            appendToolClosingMessage(toolState, closingMessage);
            chatMessages.add(buildToolResultMessage(toolState));
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
        if (toolCallId == null || toolCallId.isBlank()) {
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
        if (toolCallId == null || toolCallId.isBlank()) {
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

    private void applyToolCallDelta(AssistantState assistantState, IndexedToolCallDelta indexedToolCallDelta) {
        if (indexedToolCallDelta == null || indexedToolCallDelta.getIndex() == null || indexedToolCallDelta.getToolCallDelta() == null) {
            return;
        }
        ToolCallState toolCallState = assistantState.toolCalls.computeIfAbsent(indexedToolCallDelta.getIndex(), key -> new ToolCallState());
        ToolCallDelta toolCallDelta = indexedToolCallDelta.getToolCallDelta();
        if (toolCallDelta.getToolCallId() != null) {
            toolCallState.toolCallId = toolCallDelta.getToolCallId();
        }
        if (toolCallDelta.getToolName() != null) {
            toolCallState.toolName = toolCallDelta.getToolName();
        }
        if (toolCallDelta.getArgumentsDelta() != null) {
            toolCallState.arguments.append(toolCallDelta.getArgumentsDelta());
        }
    }

    private void applyToolContentDelta(ToolState toolState, IndexedToolContentDelta indexedToolContentDelta) {
        if (indexedToolContentDelta == null || indexedToolContentDelta.getIndex() == null || indexedToolContentDelta.getContentDelta() == null) {
            return;
        }
        int index = indexedToolContentDelta.getIndex();
        while (toolState.contents.size() <= index) {
            toolState.contents.add(null);
        }
        ToolContentAccumulator current = toolState.contents.get(index);
        ToolContentDelta contentDelta = indexedToolContentDelta.getContentDelta();
        if (contentDelta.getType() == null) {
            return;
        }

        if (current == null) {
            current = new ToolContentAccumulator();
            current.type = contentDelta.getType();
            toolState.contents.set(index, current);
        }

        if (current.type == ToolContentType.text && contentDelta.getType() == ToolContentType.text) {
            if (contentDelta.getText() != null) {
                current.text.append(contentDelta.getText());
            }
            return;
        }

        if (isMediaType(current.type) && current.type == contentDelta.getType()) {
            if (current.data == null) {
                current.data = contentDelta.getData();
            }
            if (current.mime == null) {
                current.mime = contentDelta.getMime();
            }
            if (current.name == null) {
                current.name = contentDelta.getName();
            }
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
        ToolContentAccumulator last = toolState.contents.isEmpty() ? null : toolState.contents.get(toolState.contents.size() - 1);
        if (last != null && last.type == ToolContentType.text) {
            if (!last.text.isEmpty()) {
                last.text.append(System.lineSeparator());
            }
            last.text.append(closingMessage);
            return;
        }
        ToolContentAccumulator accumulator = new ToolContentAccumulator();
        accumulator.type = ToolContentType.text;
        accumulator.text.append(closingMessage);
        toolState.contents.add(accumulator);
    }

    private AiMessage buildAiMessage(AssistantState assistantState) {
        List<ToolExecutionRequest> toolExecutionRequests = assistantState.toolCalls.values().stream()
            .map(this::toToolExecutionRequest)
            .toList();
        String text = assistantState.text.isEmpty() ? null : assistantState.text.toString();
        String thinking = assistantState.thinking.isEmpty() ? null : assistantState.thinking.toString();

        AiMessage.Builder builder = AiMessage.builder()
            .toolExecutionRequests(toolExecutionRequests);
        if (text != null || toolExecutionRequests.isEmpty()) {
            builder.text(text == null ? "" : text);
        }
        if (thinking != null) {
            builder.thinking(thinking);
        }
        return builder.build();
    }

    private ToolExecutionRequest toToolExecutionRequest(ToolCallState toolCallState) {
        ToolCall toolCall = new ToolCall();
        toolCall.setToolCallId(toolCallState.toolCallId);
        toolCall.setToolName(toolCallState.toolName);
        toolCall.setArguments(toolCallState.arguments.toString());
        return toolCallMapper.toToolExecutionRequest(toolCall);
    }

    private ToolExecutionResultMessage buildToolResultMessage(ToolState toolState) {
        List<Content> contents = new ArrayList<>();
        for (ToolContentAccumulator accumulator : toolState.contents) {
            if (accumulator == null || accumulator.type == null) {
                continue;
            }
            if (accumulator.type == ToolContentType.text) {
                contents.add(TextContent.from(accumulator.text.toString()));
            } else if (isMediaType(accumulator.type)) {
                contents.add(toMediaContent(accumulator));
            }
        }

        ToolExecutionResultMessage.Builder builder = ToolExecutionResultMessage.builder()
            .id(toolState.toolCallId)
            .toolName(toolState.toolName);
        if (contents.isEmpty()) {
            builder.text("");
        } else if (contents.size() == 1 && contents.get(0) instanceof TextContent textContent) {
            builder.text(textContent.text());
        } else {
            builder.contents(contents);
        }
        return builder.build();
    }

    private Content toMediaContent(ToolContentAccumulator accumulator) {
        String mime = accumulator.mime == null ? "application/octet-stream" : accumulator.mime;
        String data = accumulator.data == null ? "" : accumulator.data;
        if (accumulator.type == ToolContentType.image || mime.startsWith("image/")) {
            return ImageContent.from(data, mime);
        }
        if (accumulator.type == ToolContentType.audio || mime.startsWith("audio/")) {
            return AudioContent.from(data, mime);
        }
        if (accumulator.type == ToolContentType.video || mime.startsWith("video/")) {
            return VideoContent.from(data, mime);
        }
        String name = accumulator.name == null ? "media" : accumulator.name;
        return TextContent.from("[media] " + name + " (" + mime + ")");
    }

    private boolean isMediaType(ToolContentType type) {
        return type == ToolContentType.image || type == ToolContentType.audio || type == ToolContentType.video;
    }

    private sealed interface OpenState permits AssistantState, ToolState {
    }

    private static final class AssistantState implements OpenState {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final TreeMap<Integer, ToolCallState> toolCalls = new TreeMap<>();
    }

    private static final class ToolState implements OpenState {
        private String toolCallId;
        private String toolName;
        private final List<ToolContentAccumulator> contents = new ArrayList<>();
    }

    private static final class ToolCallState {
        private String toolCallId;
        private String toolName;
        private final StringBuilder arguments = new StringBuilder();
    }

    private static final class ToolContentAccumulator {
        private ToolContentType type;
        private final StringBuilder text = new StringBuilder();
        private String data;
        private String mime;
        private String name;
    }

}
