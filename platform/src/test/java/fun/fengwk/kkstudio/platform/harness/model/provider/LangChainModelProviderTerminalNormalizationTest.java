package fun.fengwk.kkstudio.platform.harness.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * terminal completion 归一化（onCompleteResponse -&gt; toResponse）的失败语义与 canonical 形状。
 *
 * <p>覆盖两条回归：FILTERED 完成丢弃残余 tool calls（canonical 约束，绝不创建 ToolInvocation）；terminal 归一化 （tool call
 * 归一化等）抛出的运行时失败是 invalid provider terminal shape，必须映射 INVALID_RESPONSE 而非 INVALID_REQUEST。
 */
class LangChainModelProviderTerminalNormalizationTest {

  @Test
  void filteredCompletionDropsToolCallsBeforeConstructingResponse() {
    AtomicReference<ProviderResponse> completed = new AtomicReference<>();
    LangChainModelProvider provider =
        stubProvider(openAiChatResponse(FinishReason.CONTENT_FILTER).build());
    provider.stream(
        request(),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderResponse response, ProviderStream stream) {
            completed.set(response);
          }

          @Override
          public void onError(ProviderException providerError, ProviderStream stream) {}
        });

    ProviderResponse response = completed.get();
    assertEquals(GenerationStopReason.FILTERED, response.stopReason());
    assertTrue(response.toolCalls().isEmpty(), "FILTERED responses must not carry tool calls");
  }

  @Test
  void malformedTerminalCompletionIsInvalidResponse() {
    AtomicReference<ProviderException> failure = new AtomicReference<>();
    // ToolCallNormalizer 对 name 缺失且 arguments 非空的可执行调用抛 IAE（malformed terminal shape）。
    ChatResponse malformed =
        openAiChatResponse(FinishReason.STOP)
            .aiMessage(
                AiMessage.builder()
                    .text("text")
                    .toolExecutionRequests(
                        List.of(
                            ToolExecutionRequest.builder()
                                .id("call-1")
                                .arguments("{\"x\":1}")
                                .build()))
                    .build())
            .build();
    LangChainModelProvider provider = stubProvider(malformed);
    provider.stream(
        request(),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderResponse response, ProviderStream stream) {}

          @Override
          public void onError(ProviderException providerError, ProviderStream stream) {
            failure.set(providerError);
          }
        });

    assertEquals(ProviderErrorKind.INVALID_RESPONSE, failure.get().kind());
  }

  private static LangChainModelProvider stubProvider(ChatResponse response) {
    StreamingChatModel chatModel = mock(StreamingChatModel.class);
    doAnswer(
            (Answer<Void>)
                invocation -> {
                  StreamingChatResponseHandler handler = invocation.getArgument(1);
                  handler.onCompleteResponse(response);
                  return null;
                })
        .when(chatModel)
        .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    return new LangChainModelProvider(ProviderType.OPENAI) {
      @Override
      protected StreamingChatModel chatModel(ProviderRequest request) {
        return chatModel;
      }

      @Override
      protected void validateRequest(ProviderRequest request) {}
    };
  }

  private static ChatResponse.Builder openAiChatResponse(FinishReason finishReason) {
    OpenAiChatResponseMetadata metadata =
        OpenAiChatResponseMetadata.builder()
            .id("chatcmpl-req-1")
            .modelName("m")
            .tokenUsage(OpenAiTokenUsage.builder().inputTokenCount(10).outputTokenCount(5).build())
            .finishReason(finishReason)
            .build();
    return ChatResponse.builder()
        .aiMessage(
            AiMessage.builder()
                .text("text")
                .toolExecutionRequests(
                    List.of(
                        ToolExecutionRequest.builder()
                            .id("call-1")
                            .name("bash")
                            .arguments("{}")
                            .build()))
                .build())
        .metadata(metadata);
  }

  private static ProviderRequest request() {
    return new ProviderRequest(
        new ModelDescriptor(
            "provider",
            "model",
            Set.of(ModelInputModality.TEXT),
            false,
            false,
            new ModelPricing(
                "USD",
                "default",
                "default",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO)),
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null),
        List.of(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("hello")))),
        List.of(),
        ProviderCacheControl.none());
  }
}
