package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantUsage;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolCallDelta;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author fengwk
 */
public interface Provider {

    ProviderType getProviderType();

    /**
     * 由实现类根据 ModelInfo + Variant + toolSpecifications 构造 LangChain4j 的 ChatRequest。
     */
    ChatRequest buildChatRequest(List<ChatMessage> chatMessageList,
                                 ModelInfo modelInfo,
                                 Variant variant,
                                 List<ToolSpecification> toolSpecifications);

    StreamingChatModel getChatModel();

    AssistantMetadata toAssistantMetadata(ChatResponseMetadata metadata);

    default AssistantResponseHandle asyncChat(List<ChatMessage> chatMessageList,
                                             ModelInfo modelInfo,
                                             Variant variant,
                                             List<ToolInfo> toolInfos,
                                             AssistantResponseHandler handler) {
        if (chatMessageList == null) {
            throw new IllegalArgumentException("chatMessageList must not be null");
        }
        if (modelInfo == null) {
            throw new IllegalArgumentException("modelInfo must not be null");
        }
        if (variant == null) {
            throw new IllegalArgumentException("variant must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }

        ChatRequest request = buildChatRequest(chatMessageList, modelInfo, variant, resolveToolSpecifications(toolInfos));
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
                IndexedToolCallDelta indexedToolCallDelta = new IndexedToolCallDelta();
                indexedToolCallDelta.setIndex(partialToolCall.index());
                indexedToolCallDelta.setToolCallDelta(toToolCallDelta(partialToolCall));
                handler.onToolCallDelta(indexedToolCallDelta, responseHandle);
            }

            @Override
            public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                if (completeToolCall == null) {
                    return;
                }
                ToolCall toolCall = toToolCall(completeToolCall.toolExecutionRequest());
                handler.onToolCallComplete(completeToolCall.index(), toolCall, responseHandle);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                List<ToolCall> toolCalls = completeResponse == null || completeResponse.aiMessage() == null
                    ? List.of()
                    : completeResponse.aiMessage().toolExecutionRequests().stream()
                        .map(Provider.this::toToolCall)
                        .toList();
                AssistantResponse response = AssistantResponse.builder()
                    .text(completeResponse == null || completeResponse.aiMessage() == null ? null : completeResponse.aiMessage().text())
                    .thinking(completeResponse == null || completeResponse.aiMessage() == null ? null : completeResponse.aiMessage().thinking())
                    .toolCalls(toolCalls)
                    .metadata(completeResponse == null ? null : toAssistantMetadata(completeResponse.metadata()))
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

    private ToolCall toToolCall(dev.langchain4j.agent.tool.ToolExecutionRequest request) {
        if (request == null) {
            return null;
        }
        ToolCall toolCall = new ToolCall();
        toolCall.setToolCallId(request.id());
        toolCall.setToolName(request.name());
        toolCall.setArguments(request.arguments());
        return toolCall;
    }

    private ToolCallDelta toToolCallDelta(PartialToolCall partialToolCall) {
        ToolCallDelta toolCallDelta = new ToolCallDelta();
        toolCallDelta.setToolCallId(partialToolCall.id());
        toolCallDelta.setToolName(partialToolCall.name());
        toolCallDelta.setArgumentsDelta(partialToolCall.partialArguments());
        return toolCallDelta;
    }

    static AssistantMetadata toCommonAssistantMetadata(ChatResponseMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        AssistantMetadata assistantMetadata = new AssistantMetadata();
        assistantMetadata.setId(metadata.id());
        assistantMetadata.setModelName(metadata.modelName());
        assistantMetadata.setFinishReason(metadata.finishReason() == null ? null : metadata.finishReason().name());
        assistantMetadata.setUsage(toCommonAssistantUsage(metadata.tokenUsage()));
        return assistantMetadata;
    }

    private static AssistantUsage toCommonAssistantUsage(dev.langchain4j.model.output.TokenUsage tokenUsage) {
        if (tokenUsage == null) {
            return null;
        }
        AssistantUsage usage = new AssistantUsage();
        usage.setInputTokens(tokenUsage.inputTokenCount());
        usage.setOutputTokens(tokenUsage.outputTokenCount());
        usage.setTotalTokens(tokenUsage.totalTokenCount());
        return usage;
    }

    static List<ToolSpecification> resolveToolSpecifications(List<ToolInfo> toolInfos) {
        if (toolInfos == null || toolInfos.isEmpty()) {
            return List.of();
        }
        List<ToolSpecification> result = new ArrayList<>();
        for (ToolInfo toolInfo : toolInfos) {
            if (toolInfo == null) {
                continue;
            }
            result.add(ToolSpecification.builder()
                .name(toolInfo.getName())
                .description(toolInfo.getDescription())
                .build());
        }
        return result;
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
