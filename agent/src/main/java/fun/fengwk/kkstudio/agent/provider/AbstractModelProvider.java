package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
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
import dev.langchain4j.model.output.TokenUsage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantUsage;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolCallDelta;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import fun.fengwk.kkstudio.agent.tool.schema.ToolArraySchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolBooleanSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolNumberSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolObjectSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolSchemaElement;
import fun.fengwk.kkstudio.agent.tool.schema.ToolStringSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;

/**
 * AbstractModelProvider 提供基于 LangChain4j StreamingChatModel 的通用实现。
 *
 * 接入新 provider 的建议步骤：
 * 1. 继承本类。
 * 2. 实现 getChatModel(ModelInfo, Variant)，返回该供应商对应的 StreamingChatModel。
 * 3. 若该供应商需要额外请求参数，覆盖 setParameters(...)。
 * 4. 若该供应商需要特殊的 tool schema 转换，覆盖 resolveToolSpecifications(...)。
 * 5. 若该供应商的 metadata / token usage 需要额外提取，覆盖 toAssistantMetadata(...)。
 *
 * 第一版边界：
 * - 通用模型参数直接来自 Variant。
 * - ProviderInfo 只承载连接与超时配置。
 * - LangChain4j SDK 类型只允许停留在 provider 包内。
 *
 * @author fengwk
 */
@Slf4j
public abstract class AbstractModelProvider implements Provider {

    private final ProviderInfo providerInfo;

    /**
     * 使用稳定的 ProviderInfo 创建 provider 基座实例。
     */
    protected AbstractModelProvider(ProviderInfo providerInfo) {
        this.providerInfo = Objects.requireNonNull(providerInfo, "providerInfo must not be null");
    }

    /**
     * 返回当前 provider 绑定的连接配置。
     */
    protected ProviderInfo getProviderInfo() {
        return providerInfo;
    }

    /**
     * 返回供应商底层的 StreamingChatModel。
     *
     * 实现要求：
     * - 绑定 ProviderInfo 中的 baseUrl / apiKey / timeout
     * - 若底层 SDK 在构造时就要求 modelName，应使用 modelInfo.getName()
     * - 若供应商支持 thinking，按当前产品语义显式开启
     */
    protected abstract StreamingChatModel getChatModel(ModelInfo modelInfo, Variant variant);

    /**
     * 创建 LangChain4j 参数 builder。
     *
     * 默认使用 DefaultChatRequestParameters，可在个别供应商中覆盖。
     */
    protected DefaultChatRequestParameters.Builder<?> newParametersBuilder() {
        return DefaultChatRequestParameters.builder();
    }

    /**
     * 追加供应商专有参数。
     *
     * 调用时机：
     * - 通用参数已由 applyCommonParameters(...) 填充
     * - 子类只补充该供应商独有的字段
     */
    protected DefaultChatRequestParameters.Builder<?> setParameters(DefaultChatRequestParameters.Builder<?> parametersBuilder,
                                                                    ModelInfo modelInfo,
                                                                    Variant variant,
                                                                    List<ToolSpecification> toolSpecifications) {
        return parametersBuilder;
    }

    /**
     * 将重放后的上下文、模型信息、变体参数与工具描述翻译为 LangChain4j ChatRequest。
     */
    protected ChatRequest buildChatRequest(List<ChatMessage> chatMessageList,
                                           ModelInfo modelInfo,
                                           Variant variant,
                                           List<ToolSpecification> toolSpecifications) {
        ChatRequest.Builder builder = ChatRequest.builder()
            .messages(chatMessageList);

        DefaultChatRequestParameters.Builder<?> parametersBuilder = newParametersBuilder();
        applyCommonParameters(parametersBuilder, modelInfo, variant, toolSpecifications);
        setParameters(parametersBuilder, modelInfo, variant, toolSpecifications);
        builder.parameters(parametersBuilder.build());

        return builder.build();
    }

    /**
     * 对外执行一次统一的异步 assistant 调用。
     *
     * 该方法负责：
     * - 校验调用参数
     * - 构造底层 ChatRequest
     * - 绑定 StreamingHandle
     * - 把底层 SDK 回调桥接为统一的 AssistantResponseHandler 事件
     */
    @Override
    public AssistantResponseHandle asyncChat(List<ChatMessage> chatMessageList,
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
        ToolCallCompatibilityNormalizer toolCallNormalizer = new ToolCallCompatibilityNormalizer();
        getChatModel(modelInfo, variant).chat(request, new StreamingChatResponseHandler() {
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
                ToolCall toolCall = toolCallNormalizer.normalize("onCompleteToolCall",
                    completeToolCall.index(),
                    toToolCall(completeToolCall.toolExecutionRequest()));
                if (toolCall == null) {
                    return;
                }
                handler.onToolCallComplete(completeToolCall.index(), toolCall, responseHandle);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                List<ToolExecutionRequest> toolExecutionRequests = completeResponse == null || completeResponse.aiMessage() == null
                    ? List.of()
                    : completeResponse.aiMessage().toolExecutionRequests();
                List<ToolCall> toolCalls = toolCallNormalizer.normalizeCompleteResponse(toolExecutionRequests);
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

    /**
     * 返回当前 provider 的类型标识。
     */
    @Override
    public ProviderType getProviderType() {
        return getProviderInfo().getProviderType();
    }

    /**
     * 将供应商返回的 metadata 映射为持久化 AssistantMetadata。
     *
     * 默认仅提取通用字段；若供应商存在 cache token 等专有字段，应在子类覆盖后补充。
     */
    protected AssistantMetadata toAssistantMetadata(ChatResponseMetadata metadata) {
        return toCommonAssistantMetadata(metadata);
    }

    /**
     * 提取各供应商共通的 metadata 字段。
     */
    protected AssistantMetadata toCommonAssistantMetadata(ChatResponseMetadata metadata) {
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

    /**
     * 提取各供应商共通的 token usage 字段。
     */
    protected AssistantUsage toCommonAssistantUsage(TokenUsage tokenUsage) {
        if (tokenUsage == null) {
            return null;
        }
        AssistantUsage usage = new AssistantUsage();
        usage.setInputTokens(tokenUsage.inputTokenCount());
        usage.setOutputTokens(tokenUsage.outputTokenCount());
        usage.setTotalTokens(tokenUsage.totalTokenCount());
        return usage;
    }

    /**
     * 将 ToolInfo 翻译为底层 SDK 需要的 ToolSpecification。
     *
     * 默认映射 name/description/inputSchema。
     * 若后续某个 provider 需要消费额外 schema 能力，可在子类覆盖。
     */
    protected List<ToolSpecification> resolveToolSpecifications(List<ToolInfo> toolInfos) {
        if (toolInfos == null || toolInfos.isEmpty()) {
            return List.of();
        }
        List<ToolSpecification> result = new ArrayList<>();
        for (ToolInfo toolInfo : toolInfos) {
            if (toolInfo == null) {
                continue;
            }
            ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(toolInfo.getName())
                .description(toolInfo.getDescription());
            if (toolInfo.getInputSchema() != null) {
                builder.parameters(toJsonParamsSchema(toolInfo.getInputSchema()));
            }
            result.add(builder.build());
        }
        return result;
    }

    /**
     * 将顶层 ToolParamsSchema 转换为 LangChain4j JsonObjectSchema。
     */
    protected JsonObjectSchema toJsonParamsSchema(ToolParamsSchema schema) {
        return buildJsonObjectSchema(schema.getDescription(), schema.getProperties(), schema.getRequired(), schema.getAdditionalProperties());
    }

    /**
     * 将嵌套 ToolObjectSchema 转换为 LangChain4j JsonObjectSchema。
     */
    protected JsonObjectSchema toJsonObjectSchema(ToolObjectSchema schema) {
        return buildJsonObjectSchema(schema.getDescription(), schema.getProperties(), schema.getRequired(), schema.getAdditionalProperties());
    }

    /**
     * 复用顶层参数对象与嵌套对象节点的 JsonObjectSchema 构造逻辑。
     */
    private JsonObjectSchema buildJsonObjectSchema(String description,
                                                   Map<String, ToolSchemaElement> properties,
                                                   List<String> required,
                                                   Boolean additionalProperties) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        if (description != null) {
            builder.description(description);
        }
        if (properties != null && !properties.isEmpty()) {
            builder.addProperties(toJsonObjectProperties(properties));
        }
        if (required != null && !required.isEmpty()) {
            builder.required(required);
        }
        if (additionalProperties != null) {
            builder.additionalProperties(additionalProperties);
        }
        return builder.build();
    }

    /**
     * 将自有 ToolSchemaElement 转换为 LangChain4j JsonSchemaElement。
     */
    protected JsonSchemaElement toJsonSchemaElement(ToolSchemaElement schemaElement) {
        if (schemaElement instanceof ToolStringSchema schema) {
            return JsonStringSchema.builder().description(schema.getDescription()).build();
        }
        if (schemaElement instanceof ToolIntegerSchema schema) {
            return JsonIntegerSchema.builder().description(schema.getDescription()).build();
        }
        if (schemaElement instanceof ToolNumberSchema schema) {
            return JsonNumberSchema.builder().description(schema.getDescription()).build();
        }
        if (schemaElement instanceof ToolBooleanSchema schema) {
            return JsonBooleanSchema.builder().description(schema.getDescription()).build();
        }
        if (schemaElement instanceof ToolEnumSchema schema) {
            return JsonEnumSchema.builder()
                .description(schema.getDescription())
                .enumValues(schema.getEnumValues())
                .build();
        }
        if (schemaElement instanceof ToolArraySchema schema) {
            JsonArraySchema.Builder builder = JsonArraySchema.builder()
                .description(schema.getDescription());
            if (schema.getItems() != null) {
                builder.items(toJsonSchemaElement(schema.getItems()));
            }
            return builder.build();
        }
        if (schemaElement instanceof ToolObjectSchema schema) {
            return toJsonObjectSchema(schema);
        }
        throw new IllegalArgumentException("unsupported tool schema element: " + schemaElement.getClass().getName());
    }

    /**
     * 将 object 属性表转换为 LangChain4j properties 定义。
     */
    private Map<String, JsonSchemaElement> toJsonObjectProperties(Map<String, ToolSchemaElement> properties) {
        Map<String, JsonSchemaElement> result = new LinkedHashMap<>();
        for (Map.Entry<String, ToolSchemaElement> entry : properties.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            result.put(entry.getKey(), toJsonSchemaElement(entry.getValue()));
        }
        return result;
    }

    /**
     * 填充所有供应商共通的模型请求参数。
     */
    private void applyCommonParameters(DefaultChatRequestParameters.Builder<?> builder,
                                       ModelInfo modelInfo,
                                       Variant variant,
                                       List<ToolSpecification> toolSpecifications) {
        builder
            .modelName(modelInfo.getName())
            .temperature(variant.getTemperature())
            .topP(variant.getTopP())
            .topK(variant.getTopK())
            .frequencyPenalty(variant.getFrequencyPenalty())
            .presencePenalty(variant.getPresencePenalty())
            .maxOutputTokens(variant.getMaxOutputTokens())
            .stopSequences(variant.getStopSequences())
            .toolSpecifications(toolSpecifications);
    }

    /**
     * 将 LangChain4j ToolExecutionRequest 转换为持久化 ToolCall。
     */
    private ToolCall toToolCall(ToolExecutionRequest request) {
        if (request == null) {
            return null;
        }
        ToolCall toolCall = new ToolCall();
        toolCall.setToolCallId(request.id());
        toolCall.setToolName(request.name());
        toolCall.setArguments(request.arguments());
        return toolCall;
    }

    /**
     * 对 LangChain4j 暴露出的 tool call 差异做 provider 层兼容归一。
     *
     * 兼容原则：
     * - toolName 缺失表示没有可执行工具意图，直接丢弃；
     * - toolCallId 缺失但 toolName 存在时，为上层事件流生成内部兼容 id。
     *
     * 生成 id 只能保证本次 agent 运行内的事件关联，不代表上游 provider 原生返回了该 id。
     * 如果对应 provider 在回放时严格要求原始 call id，兼容 id 可能导致下一轮请求失败或语义偏差，
     * 因此所有兼容分支都必须打印 warn 日志。
     */
    private final class ToolCallCompatibilityNormalizer {

        private final Map<Integer, String> canonicalToolCallIdsByIndex = new LinkedHashMap<>();
        private final List<String> canonicalToolCallIdsInCompleteOrder = new ArrayList<>();
        private int nextUnknownIndex = -1;

        /**
         * 归一完整响应中的 tool call 列表，并尽量复用流式 complete 回调中生成过的兼容 id。
         */
        private synchronized List<ToolCall> normalizeCompleteResponse(List<ToolExecutionRequest> requests) {
            if (requests == null || requests.isEmpty()) {
                return List.of();
            }
            List<ToolCall> result = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                ToolCall toolCall = normalize("onCompleteResponse", i, i, toToolCall(requests.get(i)));
                if (toolCall != null) {
                    result.add(toolCall);
                }
            }
            return result;
        }

        /**
         * 归一单个 tool call，返回 null 表示该调用无可执行语义且已被丢弃。
         */
        private synchronized ToolCall normalize(String source, Integer index, ToolCall toolCall) {
            return normalize(source, index, null, toolCall);
        }

        private ToolCall normalize(String source, Integer index, Integer responseIndex, ToolCall toolCall) {
            if (toolCall == null) {
                warnDropMalformedToolCall(source, index, null, null, null, "missing_tool_call");
                return null;
            }
            if (toolCall.getToolName() == null || toolCall.getToolName().isBlank()) {
                warnDropMalformedToolCall(source,
                    index,
                    toolCall.getToolCallId(),
                    toolCall.getToolName(),
                    toolCall.getArguments(),
                    "missing_tool_name");
                return null;
            }
            toolCall.setToolCallId(resolveCanonicalToolCallId(source, index, responseIndex, toolCall));
            return toolCall;
        }

        private String resolveCanonicalToolCallId(String source, Integer index, Integer responseIndex, ToolCall toolCall) {
            String rawToolCallId = normalizeBlank(toolCall.getToolCallId());
            Integer normalizedIndex = index == null ? nextUnknownIndex-- : index;
            String existingToolCallId = null;
            if (responseIndex != null && responseIndex < canonicalToolCallIdsInCompleteOrder.size()) {
                existingToolCallId = canonicalToolCallIdsInCompleteOrder.get(responseIndex);
            }
            if (existingToolCallId == null) {
                existingToolCallId = canonicalToolCallIdsByIndex.get(normalizedIndex);
            }
            if (existingToolCallId != null) {
                if (rawToolCallId != null && !rawToolCallId.equals(existingToolCallId)) {
                    log.warn("[provider] keep canonical toolCallId despite conflicting id: providerType={} "
                            + "source={} index={} canonicalToolCallId={} rawToolCallId={} toolName={} arguments={} "
                            + "reason=conflicting_tool_call_id; provider/langchain4j exposed different ids for "
                            + "the same logical tool call, canonical id is kept for internal event consistency",
                        getProviderType(),
                        source,
                        index,
                        existingToolCallId,
                        rawToolCallId,
                        toolCall.getToolName(),
                        toolCall.getArguments());
                }
                if (rawToolCallId == null) {
                    warnFillMissingToolCallId(source, index, existingToolCallId, toolCall, "reused_canonical_tool_call_id");
                }
                return existingToolCallId;
            }

            String canonicalToolCallId = rawToolCallId == null ? newCompatibilityToolCallId(normalizedIndex) : rawToolCallId;
            canonicalToolCallIdsByIndex.put(normalizedIndex, canonicalToolCallId);
            if (responseIndex == null) {
                canonicalToolCallIdsInCompleteOrder.add(canonicalToolCallId);
            }
            if (rawToolCallId == null) {
                warnFillMissingToolCallId(source, index, canonicalToolCallId, toolCall, "generated_compatibility_tool_call_id");
            }
            return canonicalToolCallId;
        }

        private String newCompatibilityToolCallId(Integer index) {
            String indexPart = index == null ? "unknown" : String.valueOf(index);
            return "compat_" + getProviderType() + "_" + indexPart + "_" + UUID.randomUUID().toString().replace("-", "");
        }

        private String normalizeBlank(String value) {
            return value == null || value.isBlank() ? null : value;
        }

        private void warnFillMissingToolCallId(String source,
                                               Integer index,
                                               String canonicalToolCallId,
                                               ToolCall toolCall,
                                               String reason) {
            log.warn("[provider] fill missing toolCallId: providerType={} source={} index={} "
                    + "canonicalToolCallId={} toolName={} arguments={} reason={}; provider/langchain4j returned "
                    + "a tool call without id, filled id is used only for internal correlation and may cause "
                    + "upstream replay issues if the provider requires its original id",
                getProviderType(),
                source,
                index,
                canonicalToolCallId,
                toolCall.getToolName(),
                toolCall.getArguments(),
                reason);
        }

        private void warnDropMalformedToolCall(String source,
                                               Integer index,
                                               String toolCallId,
                                               String toolName,
                                               String arguments,
                                               String reason) {
            log.warn("[provider] drop malformed tool call: providerType={} source={} index={} toolCallId={} "
                    + "toolName={} arguments={} reason={}; provider/langchain4j returned a tool call without "
                    + "executable tool intent, this tool call will not be executed and may indicate a provider compatibility issue",
                getProviderType(),
                source,
                index,
                toolCallId,
                toolName,
                arguments,
                reason);
        }

    }

    /**
     * 将 LangChain4j PartialToolCall 转换为持久化 ToolCallDelta。
     */
    private ToolCallDelta toToolCallDelta(PartialToolCall partialToolCall) {
        ToolCallDelta toolCallDelta = new ToolCallDelta();
        toolCallDelta.setToolCallId(partialToolCall.id());
        toolCallDelta.setToolName(partialToolCall.name());
        toolCallDelta.setArgumentsDelta(partialToolCall.partialArguments());
        return toolCallDelta;
    }

    protected static final class DefaultAssistantResponseHandle implements AssistantResponseHandle {

        private final AtomicReference<StreamingHandle> delegate = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /**
         * 绑定底层 StreamingHandle。
         *
         * 语义：
         * - 只绑定第一次出现的真实 handle
         * - 若上层已先调用 cancel()，则在绑定后立即向下游传播取消
         */
        void bind(StreamingHandle streamingHandle) {
            if (streamingHandle == null) {
                return;
            }
            delegate.compareAndSet(null, streamingHandle);
            if (cancelled.get()) {
                streamingHandle.cancel();
            }
        }

        /**
         * 向下游 StreamingHandle 传播取消信号。
         */
        @Override
        public void cancel() {
            cancelled.set(true);
            StreamingHandle streamingHandle = delegate.get();
            if (streamingHandle != null) {
                streamingHandle.cancel();
            }
        }

        /**
         * 判断当前 handle 是否已进入取消状态。
         */
        @Override
        public boolean isCancelled() {
            StreamingHandle streamingHandle = delegate.get();
            return cancelled.get() || (streamingHandle != null && streamingHandle.isCancelled());
        }

    }

}
