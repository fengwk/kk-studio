package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import fun.fengwk.kkstudio.agent.runtime.AssistantMetadataMapper;
import fun.fengwk.kkstudio.agent.runtime.ToolCallMapper;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolCallDelta;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author fengwk
 */
public interface Provider {

    ToolCallMapper TOOL_CALL_MAPPER = new ToolCallMapper();
    AssistantMetadataMapper ASSISTANT_METADATA_MAPPER = new AssistantMetadataMapper();

    ProviderType getProviderType();

    ChatRequest buildChatRequest(List<ChatMessage> chatMessageList, ModelRequestConfig modelConfig);

    StreamingChatModel getChatModel();

    default AssistantResponseHandle asyncChat(List<ChatMessage> chatMessageList,
                                             ModelRequestConfig modelConfig,
                                             AssistantResponseHandler handler) {
        if (chatMessageList == null) {
            throw new IllegalArgumentException("chatMessageList must not be null");
        }
        if (modelConfig == null) {
            throw new IllegalArgumentException("modelConfig must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }

        ChatRequest request = buildChatRequest(chatMessageList, modelConfig);
        DefaultAssistantResponseHandle responseHandle = new DefaultAssistantResponseHandle();
        getChatModel().chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                responseHandle.bind(context.streamingHandle());
                if (partialResponse != null && partialResponse.text() != null) {
                    handler.onTextDelta(partialResponse.text(), responseHandle);
                }
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
                responseHandle.bind(context.streamingHandle());
                if (partialThinking != null && partialThinking.text() != null) {
                    handler.onThinkingDelta(partialThinking.text(), responseHandle);
                }
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
                responseHandle.bind(context.streamingHandle());
                if (partialToolCall == null) {
                    return;
                }
                ToolCallDelta toolCallDelta = new ToolCallDelta();
                toolCallDelta.setToolCallId(partialToolCall.id());
                toolCallDelta.setToolName(partialToolCall.name());
                toolCallDelta.setArgumentsDelta(partialToolCall.partialArguments());
                IndexedToolCallDelta indexedToolCallDelta = new IndexedToolCallDelta();
                indexedToolCallDelta.setIndex(partialToolCall.index());
                indexedToolCallDelta.setToolCallDelta(toolCallDelta);
                handler.onToolCallDelta(indexedToolCallDelta, responseHandle);
            }

            @Override
            public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                if (completeToolCall == null) {
                    return;
                }
                ToolCall toolCall = TOOL_CALL_MAPPER.from(completeToolCall.toolExecutionRequest());
                handler.onToolCallComplete(completeToolCall.index(), toolCall, responseHandle);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                List<ToolCall> toolCalls = completeResponse == null || completeResponse.aiMessage() == null
                    ? List.of()
                    : completeResponse.aiMessage().toolExecutionRequests().stream()
                        .map(TOOL_CALL_MAPPER::from)
                        .toList();
                AssistantResponse response = AssistantResponse.builder()
                    .text(completeResponse == null || completeResponse.aiMessage() == null ? null : completeResponse.aiMessage().text())
                    .thinking(completeResponse == null || completeResponse.aiMessage() == null ? null : completeResponse.aiMessage().thinking())
                    .toolCalls(toolCalls)
                    .metadata(completeResponse == null ? null : ASSISTANT_METADATA_MAPPER.from(completeResponse.metadata()))
                    .build();
                handler.onComplete(response, responseHandle);
            }

            @Override
            public void onError(Throwable error) {
                handler.onError(error, responseHandle);
            }
        });
        return responseHandle;
    }

    final class DefaultAssistantResponseHandle implements AssistantResponseHandle {

        private final AtomicReference<StreamingHandle> delegate = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        void bind(StreamingHandle streamingHandle) {
            if (streamingHandle == null) {
                return;
            }
            delegate.compareAndSet(null, streamingHandle);
            if (cancelled.get()) {
                streamingHandle.cancel();
            }
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            StreamingHandle streamingHandle = delegate.get();
            if (streamingHandle != null) {
                streamingHandle.cancel();
            }
        }

        @Override
        public boolean isCancelled() {
            StreamingHandle streamingHandle = delegate.get();
            return cancelled.get() || (streamingHandle != null && streamingHandle.isCancelled());
        }

    }

}
