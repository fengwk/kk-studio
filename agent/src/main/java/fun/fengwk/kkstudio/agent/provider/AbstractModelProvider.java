package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
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
import fun.fengwk.kkstudio.agent.message.AgentAssistantMessage;
import fun.fengwk.kkstudio.agent.message.AgentMessage;
import fun.fengwk.kkstudio.agent.message.AgentSystemMessage;
import fun.fengwk.kkstudio.agent.message.AgentToolMessage;
import fun.fengwk.kkstudio.agent.message.AgentUserMessage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantUsage;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
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

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
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
import fun.fengwk.kkstudio.agent.message.AgentAssistantMessage;
import fun.fengwk.kkstudio.agent.message.AgentMessage;
import fun.fengwk.kkstudio.agent.message.AgentSystemMessage;
import fun.fengwk.kkstudio.agent.message.AgentToolMessage;
import fun.fengwk.kkstudio.agent.message.AgentUserMessage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantUsage;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
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
 * <p>接入新 provider 的建议步骤： 1. 继承本类。 2. 实现 getChatModel(ModelInfo, Variant)，返回该供应商对应的
 * StreamingChatModel。 3. 若该供应商需要额外请求参数，覆盖 setParameters(...)。 4. 若该供应商需要特殊的 tool schema 转换，覆盖
 * resolveToolSpecifications(...)。 5. 若该供应商的 metadata / token usage 需要额外提取，覆盖
 * toAssistantMetadata(...)。
 *
 * <p>第一版边界： - 通用模型参数直接来自 Variant。 - ProviderInfo 只承载连接与超时配置。 - LangChain4j SDK 类型只允许停留在 provider
 * 包内。
 *
 * @author fengwk
 */
@Slf4j
public abstract class AbstractModelProvider implements Provider {

  private final ProviderInfo providerInfo;

  /** 使用稳定的 ProviderInfo 创建 provider 基座实例。 */
  protected AbstractModelProvider(ProviderInfo providerInfo) {
    this.providerInfo = Objects.requireNonNull(providerInfo, "providerInfo must not be null");
  }

  /** 返回当前 provider 绑定的连接配置。 */
  protected ProviderInfo getProviderInfo() {
    return providerInfo;
  }

  /**
   * 返回供应商底层的 StreamingChatModel。
   *
   * <p>实现要求： - 绑定 ProviderInfo 中的 baseUrl / apiKey / timeout - 若底层 SDK 在构造时就要求 modelName，应使用
   * modelInfo.getName() - 若供应商支持 thinking，按当前产品语义显式开启
   */
  protected abstract StreamingChatModel getChatModel(ModelInfo modelInfo, Variant variant);

  /**
   * 创建 LangChain4j 参数 builder。
   *
   * <p>默认使用 DefaultChatRequestParameters，可在个别供应商中覆盖。
   */
  protected DefaultChatRequestParameters.Builder<?> newParametersBuilder() {
    return DefaultChatRequestParameters.builder();
  }

  /**
   * 追加供应商专有参数。
   *
   * <p>调用时机： - 通用参数已由 applyCommonParameters(...) 填充 - 子类只补充该供应商独有的字段
   */
  protected DefaultChatRequestParameters.Builder<?> setParameters(
      DefaultChatRequestParameters.Builder<?> parametersBuilder,
      ModelInfo modelInfo,
      Variant variant,
      List<ToolSpecification> toolSpecifications) {
    return parametersBuilder;
  }

  /** 将重放后的上下文、模型信息、变体参数与工具描述翻译为 LangChain4j ChatRequest。 */
  protected ChatRequest buildChatRequest(
      List<AgentMessage> messages,
      ModelInfo modelInfo,
      Variant variant,
      List<ToolSpecification> toolSpecifications) {
    ChatRequest.Builder builder = ChatRequest.builder().messages(toLangChainMessages(messages));

    DefaultChatRequestParameters.Builder<?> parametersBuilder = newParametersBuilder();
    applyCommonParameters(parametersBuilder, modelInfo, variant, toolSpecifications);
    setParameters(parametersBuilder, modelInfo, variant, toolSpecifications);
    builder.parameters(parametersBuilder.build());

    return builder.build();
  }

  private List<ChatMessage> toLangChainMessages(List<AgentMessage> messages) {
    List<ChatMessage> chatMessages = new ArrayList<>();
    for (AgentMessage message : messages) {
      if (message == null) {
        throw new IllegalArgumentException("messages must not contain null");
      }
      chatMessages.add(toLangChainMessage(message));
    }
    return chatMessages;
  }

  private ChatMessage toLangChainMessage(AgentMessage message) {
    if (message instanceof AgentSystemMessage systemMessage) {
      return SystemMessage.from(emptyIfNull(systemMessage.text()));
    }
    if (message instanceof AgentUserMessage userMessage) {
      return UserMessage.userMessage(emptyIfNull(userMessage.text()));
    }
    if (message instanceof AgentAssistantMessage assistantMessage) {
      return toLangChainAiMessage(assistantMessage);
    }
    if (message instanceof AgentToolMessage toolMessage) {
      return toLangChainToolMessage(toolMessage);
    }
    throw new IllegalArgumentException(
        "unsupported agent message: " + message.getClass().getName());
  }

  private AiMessage toLangChainAiMessage(AgentAssistantMessage message) {
    List<ToolExecutionRequest> toolExecutionRequests = new ArrayList<>();
    for (ToolCall toolCall : message.toolCalls()) {
      ToolExecutionRequest request = toToolExecutionRequest(toolCall);
      if (request != null) {
        toolExecutionRequests.add(request);
      }
    }

    AiMessage.Builder builder = AiMessage.builder().toolExecutionRequests(toolExecutionRequests);
    if (message.text() != null || toolExecutionRequests.isEmpty()) {
      builder.text(emptyIfNull(message.text()));
    }
    if (message.thinking() != null) {
      builder.thinking(message.thinking());
    }
    return builder.build();
  }

  private ToolExecutionRequest toToolExecutionRequest(ToolCall toolCall) {
    if (toolCall == null || isBlank(toolCall.getToolCallId()) || isBlank(toolCall.getToolName())) {
      return null;
    }
    return ToolExecutionRequest.builder()
        .id(toolCall.getToolCallId())
        .name(toolCall.getToolName())
        .arguments(emptyIfNull(toolCall.getArguments()))
        .build();
  }

  private ToolExecutionResultMessage toLangChainToolMessage(AgentToolMessage message) {
    List<Content> contents = new ArrayList<>();
    for (ToolContent toolContent : message.contents()) {
      Content content = toLangChainContent(toolContent);
      if (content != null) {
        contents.add(content);
      }
    }

    ToolExecutionResultMessage.Builder builder =
        ToolExecutionResultMessage.builder().id(message.id()).toolName(message.toolName());
    if (message.error()) {
      builder.isError(true);
    }
    if (contents.isEmpty()) {
      builder.text("");
    } else if (contents.size() == 1 && contents.get(0) instanceof TextContent textContent) {
      builder.text(textContent.text());
    } else {
      builder.contents(contents);
    }
    return builder.build();
  }

  private Content toLangChainContent(ToolContent toolContent) {
    if (toolContent == null || toolContent.getType() == null) {
      return null;
    }
    if (toolContent.getType() == ToolContentType.text) {
      return TextContent.from(emptyIfNull(toolContent.getText()));
    }
    if (isInvalidMedia(toolContent)) {
      throw new IllegalArgumentException(
          "invalid media content: type="
              + toolContent.getType()
              + ", mime="
              + toolContent.getMime());
    }
    if (toolContent.getType() == ToolContentType.image) {
      return ImageContent.from(toolContent.getData(), toolContent.getMime());
    }
    if (toolContent.getType() == ToolContentType.audio) {
      return AudioContent.from(toolContent.getData(), toolContent.getMime());
    }
    if (toolContent.getType() == ToolContentType.video) {
      return VideoContent.from(toolContent.getData(), toolContent.getMime());
    }
    return null;
  }

  private boolean isInvalidMedia(ToolContent toolContent) {
    if (toolContent.getData() == null
        || toolContent.getData().isBlank()
        || toolContent.getMime() == null
        || toolContent.getMime().isBlank()) {
      return true;
    }
    if (toolContent.getType() == ToolContentType.image) {
      return !toolContent.getMime().startsWith("image/");
    }
    if (toolContent.getType() == ToolContentType.audio) {
      return !toolContent.getMime().startsWith("audio/");
    }
    if (toolContent.getType() == ToolContentType.video) {
      return !toolContent.getMime().startsWith("video/");
    }
    return true;
  }

  private String emptyIfNull(String value) {
    return value == null ? "" : value;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /**
   * 对外执行一次统一的异步 assistant 调用。
   *
   * <p>该方法负责： - 校验调用参数 - 构造底层 ChatRequest - 绑定 StreamingHandle - 把底层 SDK 回调桥接为统一的
   * AssistantResponseHandler 事件
   */
  @Override
  public AssistantResponseHandle asyncChat(
      List<AgentMessage> messages,
      ModelInfo modelInfo,
      Variant variant,
      List<ToolInfo> toolInfos,
      AssistantResponseHandler handler) {
    if (messages == null) {
      throw new IllegalArgumentException("messages must not be null");
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

    ChatRequest request =
        buildChatRequest(messages, modelInfo, variant, resolveToolSpecifications(toolInfos));
    DefaultAssistantResponseHandle responseHandle = new DefaultAssistantResponseHandle();
    ToolCallCompatibilityNormalizer toolCallNormalizer = new ToolCallCompatibilityNormalizer();
    AtomicBoolean providerCompatibilityFailed = new AtomicBoolean();
    getChatModel(modelInfo, variant)
        .chat(
            request,
            new StreamingChatResponseHandler() {
              @Override
              public void onPartialResponse(
                  PartialResponse partialResponse, PartialResponseContext context) {
                if (providerCompatibilityFailed.get()) {
                  return;
                }
                responseHandle.bind(context.streamingHandle());
                if (partialResponse != null && partialResponse.text() != null) {
                  handler.onTextDelta(partialResponse.text(), responseHandle);
                }
              }

              @Override
              public void onPartialThinking(
                  PartialThinking partialThinking, PartialThinkingContext context) {
                if (providerCompatibilityFailed.get()) {
                  return;
                }
                responseHandle.bind(context.streamingHandle());
                if (partialThinking != null && partialThinking.text() != null) {
                  handler.onThinkingDelta(partialThinking.text(), responseHandle);
                }
              }

              @Override
              public void onPartialToolCall(
                  PartialToolCall partialToolCall, PartialToolCallContext context) {
                if (providerCompatibilityFailed.get()) {
                  return;
                }
                responseHandle.bind(context.streamingHandle());
                IndexedToolCallDelta indexedToolCallDelta;
                try {
                  indexedToolCallDelta =
                      toolCallNormalizer.normalizePartialToolCall(partialToolCall);
                } catch (ProviderCompatibilityException error) {
                  failProviderCompatibility(error);
                  return;
                }
                if (indexedToolCallDelta == null) {
                  return;
                }
                handler.onToolCallDelta(indexedToolCallDelta, responseHandle);
              }

              @Override
              public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                if (providerCompatibilityFailed.get()) {
                  return;
                }
                if (completeToolCall == null) {
                  return;
                }
                NormalizedToolCall normalizedToolCall;
                try {
                  normalizedToolCall =
                      toolCallNormalizer.normalize(
                          "onCompleteToolCall",
                          completeToolCall.index(),
                          null,
                          toToolCall(completeToolCall.toolExecutionRequest()));
                } catch (ProviderCompatibilityException error) {
                  failProviderCompatibility(error);
                  return;
                }
                if (normalizedToolCall == null) {
                  return;
                }
                ToolCall toolCall = normalizedToolCall.toolCall();
                if (toolCall == null) {
                  return;
                }
                handler.onToolCallComplete(normalizedToolCall.index(), toolCall, responseHandle);
              }

              @Override
              public void onCompleteResponse(ChatResponse completeResponse) {
                if (providerCompatibilityFailed.get()) {
                  return;
                }
                List<ToolExecutionRequest> toolExecutionRequests =
                    completeResponse == null || completeResponse.aiMessage() == null
                        ? List.of()
                        : completeResponse.aiMessage().toolExecutionRequests();
                List<ToolCall> toolCalls;
                try {
                  toolCalls = toolCallNormalizer.normalizeCompleteResponse(toolExecutionRequests);
                } catch (ProviderCompatibilityException error) {
                  failProviderCompatibility(error);
                  return;
                }
                AssistantResponse response =
                    AssistantResponse.builder()
                        .text(
                            completeResponse == null || completeResponse.aiMessage() == null
                                ? null
                                : completeResponse.aiMessage().text())
                        .thinking(
                            completeResponse == null || completeResponse.aiMessage() == null
                                ? null
                                : completeResponse.aiMessage().thinking())
                        .toolCalls(toolCalls)
                        .metadata(
                            completeResponse == null
                                ? null
                                : toAssistantMetadata(completeResponse.metadata()))
                        .build();
                handler.onComplete(response, responseHandle);
              }

              @Override
              public void onError(Throwable error) {
                if (providerCompatibilityFailed.get()) {
                  return;
                }
                handler.onError(error, responseHandle);
              }

              private void failProviderCompatibility(ProviderCompatibilityException error) {
                if (!providerCompatibilityFailed.compareAndSet(false, true)) {
                  return;
                }
                responseHandle.cancel();
                handler.onError(error, responseHandle);
              }
            });
    return responseHandle;
  }

  /** 返回当前 provider 的类型标识。 */
  @Override
  public ProviderType getProviderType() {
    return getProviderInfo().getProviderType();
  }

  /**
   * 将供应商返回的 metadata 映射为持久化 AssistantMetadata。
   *
   * <p>默认仅提取通用字段；若供应商存在 cache token 等专有字段，应在子类覆盖后补充。
   */
  protected AssistantMetadata toAssistantMetadata(ChatResponseMetadata metadata) {
    return toCommonAssistantMetadata(metadata);
  }

  /** 提取各供应商共通的 metadata 字段。 */
  protected AssistantMetadata toCommonAssistantMetadata(ChatResponseMetadata metadata) {
    if (metadata == null) {
      return null;
    }
    AssistantMetadata assistantMetadata = new AssistantMetadata();
    assistantMetadata.setId(metadata.id());
    assistantMetadata.setModelName(metadata.modelName());
    assistantMetadata.setFinishReason(
        metadata.finishReason() == null ? null : metadata.finishReason().name());
    assistantMetadata.setUsage(toCommonAssistantUsage(metadata.tokenUsage()));
    return assistantMetadata;
  }

  /** 提取各供应商共通的 token usage 字段。 */
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
   * <p>默认映射 name/description/inputSchema。 若后续某个 provider 需要消费额外 schema 能力，可在子类覆盖。
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
      ToolSpecification.Builder builder =
          ToolSpecification.builder()
              .name(toolInfo.getName())
              .description(toolInfo.getDescription());
      if (toolInfo.getInputSchema() != null) {
        builder.parameters(toJsonParamsSchema(toolInfo.getInputSchema()));
      }
      result.add(builder.build());
    }
    return result;
  }

  /** 将顶层 ToolParamsSchema 转换为 LangChain4j JsonObjectSchema。 */
  protected JsonObjectSchema toJsonParamsSchema(ToolParamsSchema schema) {
    return buildJsonObjectSchema(
        schema.getDescription(),
        schema.getProperties(),
        schema.getRequired(),
        schema.getAdditionalProperties());
  }

  /** 将嵌套 ToolObjectSchema 转换为 LangChain4j JsonObjectSchema。 */
  protected JsonObjectSchema toJsonObjectSchema(ToolObjectSchema schema) {
    return buildJsonObjectSchema(
        schema.getDescription(),
        schema.getProperties(),
        schema.getRequired(),
        schema.getAdditionalProperties());
  }

  /** 复用顶层参数对象与嵌套对象节点的 JsonObjectSchema 构造逻辑。 */
  private JsonObjectSchema buildJsonObjectSchema(
      String description,
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

  /** 将自有 ToolSchemaElement 转换为 LangChain4j JsonSchemaElement。 */
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
      JsonArraySchema.Builder builder =
          JsonArraySchema.builder().description(schema.getDescription());
      if (schema.getItems() != null) {
        builder.items(toJsonSchemaElement(schema.getItems()));
      }
      return builder.build();
    }
    if (schemaElement instanceof ToolObjectSchema schema) {
      return toJsonObjectSchema(schema);
    }
    throw new IllegalArgumentException(
        "unsupported tool schema element: " + schemaElement.getClass().getName());
  }

  /** 将 object 属性表转换为 LangChain4j properties 定义。 */
  private Map<String, JsonSchemaElement> toJsonObjectProperties(
      Map<String, ToolSchemaElement> properties) {
    Map<String, JsonSchemaElement> result = new LinkedHashMap<>();
    for (Map.Entry<String, ToolSchemaElement> entry : properties.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        continue;
      }
      result.put(entry.getKey(), toJsonSchemaElement(entry.getValue()));
    }
    return result;
  }

  /** 填充所有供应商共通的模型请求参数。 */
  private void applyCommonParameters(
      DefaultChatRequestParameters.Builder<?> builder,
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

  /** 将 LangChain4j ToolExecutionRequest 转换为持久化 ToolCall。 */
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
   * <p>兼容原则： - toolName 缺失表示没有可执行工具意图，直接丢弃； - toolCallId 缺失但 toolName 存在时，为上层事件流生成内部兼容 id。
   *
   * <p>生成 id 只能保证本次 agent 运行内的事件关联，不代表上游 provider 原生返回了该 id。 如果对应 provider 在回放时严格要求原始 call id，兼容 id
   * 可能导致下一轮请求失败或语义偏差， 因此所有兼容分支都必须打印 warn 日志。
   */
  private record NormalizedToolCall(Integer index, ToolCall toolCall) {}

  private final class ToolCallCompatibilityNormalizer {

    private final Map<Integer, String> canonicalToolCallIdsByIndex = new LinkedHashMap<>();
    private final Map<String, Integer> indexesByRawToolCallId = new LinkedHashMap<>();
    private final Map<Integer, String> toolNamesByIndex = new LinkedHashMap<>();
    private final List<String> canonicalToolCallIdsInCompleteOrder = new ArrayList<>();
    private int nextSyntheticIndex = -1;

    /** 归一局部 tool call 增量；缺少稳定关联键时直接失败，避免静默拼错 arguments。 */
    private synchronized IndexedToolCallDelta normalizePartialToolCall(
        PartialToolCall partialToolCall) {
      if (partialToolCall == null) {
        return null;
      }
      String rawToolCallId = normalizeBlank(partialToolCall.id());
      String toolName = normalizeBlank(partialToolCall.name());
      String argumentsDelta = partialToolCall.partialArguments();
      if (toolName == null && isEmptyToolCallSentinel(rawToolCallId, argumentsDelta)) {
        warnDropEmptyToolCallSentinel(
            "onPartialToolCall", partialToolCall.index(), rawToolCallId, argumentsDelta);
        return null;
      }
      Integer index =
          resolveDeltaIndex(
              "onPartialToolCall",
              partialToolCall.index(),
              null,
              rawToolCallId,
              toolName,
              argumentsDelta);
      if (index == null) {
        return null;
      }
      if (toolName != null) {
        toolName =
            registerToolName("onPartialToolCall", index, toolName, rawToolCallId, argumentsDelta);
      }

      ToolCallDelta toolCallDelta = new ToolCallDelta();
      if (rawToolCallId != null) {
        toolCallDelta.setToolCallId(
            registerRawToolCallId(
                "onPartialToolCall", index, rawToolCallId, toolName, argumentsDelta));
      }
      toolCallDelta.setToolName(toolName);
      toolCallDelta.setArgumentsDelta(argumentsDelta);
      if (toolCallDelta.getToolCallId() == null
          && toolCallDelta.getToolName() == null
          && toolCallDelta.getArgumentsDelta() == null) {
        return null;
      }

      IndexedToolCallDelta indexedToolCallDelta = new IndexedToolCallDelta();
      indexedToolCallDelta.setIndex(index);
      indexedToolCallDelta.setToolCallDelta(toolCallDelta);
      return indexedToolCallDelta;
    }

    /** 归一完整响应中的 tool call 列表，并尽量复用流式 complete 回调中确定的 canonical id。 */
    private synchronized List<ToolCall> normalizeCompleteResponse(
        List<ToolExecutionRequest> requests) {
      if (requests == null || requests.isEmpty()) {
        return List.of();
      }
      List<ToolCall> result = new ArrayList<>();
      for (int i = 0; i < requests.size(); i++) {
        NormalizedToolCall normalizedToolCall =
            normalize("onCompleteResponse", i, i, toToolCall(requests.get(i)));
        if (normalizedToolCall != null) {
          result.add(normalizedToolCall.toolCall());
        }
      }
      return result;
    }

    /** 归一单个完整 tool call，返回 null 表示该调用是可安全丢弃的空 sentinel。 */
    private synchronized NormalizedToolCall normalize(
        String source, Integer index, Integer responseIndex, ToolCall toolCall) {
      if (toolCall == null) {
        throw incompatible(source, index, null, null, null, "missing_tool_call");
      }

      String rawToolCallId = normalizeBlank(toolCall.getToolCallId());
      String toolName = normalizeBlank(toolCall.getToolName());
      String arguments = toolCall.getArguments();
      if (toolName == null) {
        if (isEmptyToolCallSentinel(rawToolCallId, arguments)) {
          warnDropEmptyToolCallSentinel(source, index, rawToolCallId, arguments);
          return null;
        }
        throw incompatible(source, index, rawToolCallId, toolName, arguments, "missing_tool_name");
      }

      Integer normalizedIndex =
          resolveDeltaIndex(source, index, responseIndex, rawToolCallId, toolName, arguments);
      if (normalizedIndex == null) {
        throw incompatible(
            source,
            index,
            rawToolCallId,
            toolName,
            arguments,
            "missing_both_index_and_tool_call_id");
      }
      toolName = registerToolName(source, normalizedIndex, toolName, rawToolCallId, arguments);
      toolCall.setToolCallId(
          resolveCanonicalToolCallId(
              source, normalizedIndex, responseIndex, rawToolCallId, toolName, arguments));
      toolCall.setToolName(toolName);
      return new NormalizedToolCall(normalizedIndex, toolCall);
    }

    private Integer resolveDeltaIndex(
        String source,
        Integer index,
        Integer responseIndex,
        String rawToolCallId,
        String toolName,
        String arguments) {
      if (rawToolCallId != null) {
        Integer existingIndex = indexesByRawToolCallId.get(rawToolCallId);
        if (existingIndex != null) {
          if (index != null && !existingIndex.equals(index)) {
            log.warn(
                "[provider] keep canonical toolCall index despite conflicting index: providerType={} "
                    + "source={} canonicalIndex={} rawIndex={} toolCallId={} toolName={} arguments={} "
                    + "reason=conflicting_tool_call_index",
                getProviderType(),
                source,
                existingIndex,
                index,
                rawToolCallId,
                toolName,
                arguments);
          }
          return existingIndex;
        }
      }
      if (index != null) {
        if (rawToolCallId != null) {
          indexesByRawToolCallId.putIfAbsent(rawToolCallId, index);
        }
        return index;
      }
      if (responseIndex != null) {
        return responseIndex;
      }
      if (rawToolCallId == null) {
        throw incompatible(
            source, null, null, toolName, arguments, "missing_both_index_and_tool_call_id");
      }
      Integer existingIndex = indexesByRawToolCallId.get(rawToolCallId);
      if (existingIndex != null) {
        return existingIndex;
      }
      Integer syntheticIndex = nextSyntheticIndex--;
      indexesByRawToolCallId.put(rawToolCallId, syntheticIndex);
      log.warn(
          "[provider] generated compatibility toolCall index: providerType={} source={} "
              + "syntheticIndex={} toolCallId={} toolName={} arguments={} reason=missing_tool_call_index; "
              + "provider/langchain4j returned a tool call delta without index, raw toolCallId is used to keep deltas correlated",
          getProviderType(),
          source,
          syntheticIndex,
          rawToolCallId,
          toolName,
          arguments);
      return syntheticIndex;
    }

    private String registerToolName(
        String source, Integer index, String toolName, String toolCallId, String arguments) {
      String existingToolName = toolNamesByIndex.get(index);
      if (existingToolName == null) {
        toolNamesByIndex.put(index, toolName);
        return toolName;
      }
      if (!existingToolName.equals(toolName)) {
        log.warn(
            "[provider] keep canonical toolName despite conflicting name: providerType={} source={} "
                + "index={} canonicalToolName={} rawToolName={} toolCallId={} arguments={} reason=conflicting_tool_name",
            getProviderType(),
            source,
            index,
            existingToolName,
            toolName,
            toolCallId,
            arguments);
      }
      return existingToolName;
    }

    private String resolveCanonicalToolCallId(
        String source,
        Integer index,
        Integer responseIndex,
        String rawToolCallId,
        String toolName,
        String arguments) {
      String existingToolCallId = null;
      if (responseIndex != null && responseIndex < canonicalToolCallIdsInCompleteOrder.size()) {
        existingToolCallId = canonicalToolCallIdsInCompleteOrder.get(responseIndex);
      }
      if (existingToolCallId == null) {
        existingToolCallId = canonicalToolCallIdsByIndex.get(index);
      }
      if (existingToolCallId != null) {
        if (rawToolCallId != null && !rawToolCallId.equals(existingToolCallId)) {
          log.warn(
              "[provider] keep canonical toolCallId despite conflicting id: providerType={} "
                  + "source={} index={} canonicalToolCallId={} rawToolCallId={} toolName={} arguments={} "
                  + "reason=conflicting_tool_call_id; provider/langchain4j exposed different ids for "
                  + "the same logical tool call, canonical id is kept for internal event consistency",
              getProviderType(),
              source,
              index,
              existingToolCallId,
              rawToolCallId,
              toolName,
              arguments);
        }
        if (rawToolCallId == null) {
          warnFillMissingToolCallId(
              source,
              index,
              existingToolCallId,
              toolName,
              arguments,
              "reused_canonical_tool_call_id");
        }
        return existingToolCallId;
      }

      String canonicalToolCallId =
          rawToolCallId == null ? newCompatibilityToolCallId(index) : rawToolCallId;
      canonicalToolCallIdsByIndex.put(index, canonicalToolCallId);
      if (rawToolCallId != null) {
        indexesByRawToolCallId.putIfAbsent(rawToolCallId, index);
      }
      if (responseIndex == null) {
        canonicalToolCallIdsInCompleteOrder.add(canonicalToolCallId);
      }
      if (rawToolCallId == null) {
        warnFillMissingToolCallId(
            source,
            index,
            canonicalToolCallId,
            toolName,
            arguments,
            "generated_compatibility_tool_call_id");
      }
      return canonicalToolCallId;
    }

    private String registerRawToolCallId(
        String source, Integer index, String rawToolCallId, String toolName, String arguments) {
      String existingToolCallId = canonicalToolCallIdsByIndex.get(index);
      if (existingToolCallId != null && !existingToolCallId.equals(rawToolCallId)) {
        log.warn(
            "[provider] keep canonical toolCallId despite conflicting partial id: providerType={} "
                + "source={} index={} canonicalToolCallId={} rawToolCallId={} toolName={} arguments={} "
                + "reason=conflicting_tool_call_id",
            getProviderType(),
            source,
            index,
            existingToolCallId,
            rawToolCallId,
            toolName,
            arguments);
        return existingToolCallId;
      }
      canonicalToolCallIdsByIndex.putIfAbsent(index, rawToolCallId);
      indexesByRawToolCallId.putIfAbsent(rawToolCallId, index);
      return canonicalToolCallIdsByIndex.get(index);
    }

    private boolean isEmptyToolCallSentinel(String rawToolCallId, String arguments) {
      return rawToolCallId == null
          && (arguments == null || arguments.isBlank() || "{}".equals(arguments));
    }

    private String newCompatibilityToolCallId(Integer index) {
      return "compat_"
          + getProviderType()
          + "_"
          + index
          + "_"
          + UUID.randomUUID().toString().replace("-", "");
    }

    private String normalizeBlank(String value) {
      return value == null || value.isBlank() ? null : value;
    }

    private void warnFillMissingToolCallId(
        String source,
        Integer index,
        String canonicalToolCallId,
        String toolName,
        String arguments,
        String reason) {
      log.warn(
          "[provider] fill missing toolCallId: providerType={} source={} index={} "
              + "canonicalToolCallId={} toolName={} arguments={} reason={}; provider/langchain4j returned "
              + "a tool call without id, filled id is used only for internal correlation and may cause "
              + "upstream replay issues if the provider requires its original id",
          getProviderType(),
          source,
          index,
          canonicalToolCallId,
          toolName,
          arguments,
          reason);
    }

    private void warnDropEmptyToolCallSentinel(
        String source, Integer index, String toolCallId, String arguments) {
      log.warn(
          "[provider] drop empty tool call sentinel: providerType={} source={} index={} "
              + "toolCallId={} toolName=null arguments={} reason=empty_tool_call_sentinel; "
              + "provider/langchain4j returned a non-executable placeholder tool call",
          getProviderType(),
          source,
          index,
          toolCallId,
          arguments);
    }

    private ProviderCompatibilityException incompatible(
        String source,
        Integer index,
        String toolCallId,
        String toolName,
        String arguments,
        String reason) {
      String message =
          "incompatible provider tool call: providerType="
              + getProviderType()
              + ", source="
              + source
              + ", index="
              + index
              + ", toolCallId="
              + toolCallId
              + ", toolName="
              + toolName
              + ", arguments="
              + arguments
              + ", reason="
              + reason;
      log.warn("[provider] {}", message);
      return new ProviderCompatibilityException(message);
    }
  }

  private static final class ProviderCompatibilityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private ProviderCompatibilityException(String message) {
      super(message);
    }
  }

  protected static final class DefaultAssistantResponseHandle implements AssistantResponseHandle {

    private final AtomicReference<StreamingHandle> delegate = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /**
     * 绑定底层 StreamingHandle。
     *
     * <p>语义： - 只绑定第一次出现的真实 handle - 若上层已先调用 cancel()，则在绑定后立即向下游传播取消
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

    /** 向下游 StreamingHandle 传播取消信号。 */
    @Override
    public void cancel() {
      cancelled.set(true);
      StreamingHandle streamingHandle = delegate.get();
      if (streamingHandle != null) {
        streamingHandle.cancel();
      }
    }

    /** 判断当前 handle 是否已进入取消状态。 */
    @Override
    public boolean isCancelled() {
      StreamingHandle streamingHandle = delegate.get();
      return cancelled.get() || (streamingHandle != null && streamingHandle.isCancelled());
    }
  }
}
