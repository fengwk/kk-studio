package fun.fengwk.kkstudio.agent.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import fun.fengwk.kkstudio.agent.message.AgentUserMessage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import fun.fengwk.kkstudio.agent.message.AgentUserMessage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * @author fengwk
 */
public class OpenAiModelProviderTest {

  /** 校验 MiniMax 兼容端点会附带 reasoning_split=true，避免 thinking 泄漏到正文。 */
  @Test
  public void testMiniMaxEndpointEnablesReasoningSplit() {
    OpenAiStreamingChatModel chatModel =
        assertInstanceOf(
            OpenAiStreamingChatModel.class,
            new TestOpenAiModelProvider(
                    ProviderInfo.builder()
                        .providerType(ProviderType.openai)
                        .baseUrl("https://api.minimax.io/v1")
                        .build())
                .exposeChatModel());

    assertEquals(
        Map.of("reasoning_split", true), chatModel.defaultRequestParameters().customParameters());
  }

  /** 校验普通 OpenAI 兼容端点不会注入 MiniMax 专有参数。 */
  @Test
  public void testNonMiniMaxEndpointDoesNotEnableReasoningSplit() {
    OpenAiStreamingChatModel chatModel =
        assertInstanceOf(
            OpenAiStreamingChatModel.class,
            new TestOpenAiModelProvider(
                    ProviderInfo.builder()
                        .providerType(ProviderType.openai)
                        .baseUrl("https://api.openai.com/v1")
                        .build())
                .exposeChatModel());

    assertTrue(chatModel.defaultRequestParameters().customParameters().isEmpty());
  }

  /** 校验 MiniMax 兼容端点即使把 thinking 混入 content，也会在 provider 层兜底拆出。 */
  @Test
  public void testMiniMaxEndpointExtractsThinkTagsFromContent() {
    TestOpenAiModelProvider provider =
        new TestOpenAiModelProvider(
            ProviderInfo.builder()
                .providerType(ProviderType.openai)
                .baseUrl("https://api.minimax.io/v1")
                .build(),
            new ThinkTagStreamingChatModel());
    StringBuilder text = new StringBuilder();
    List<String> thinkingDeltas = new ArrayList<>();
    AtomicReference<AssistantResponse> responseRef = new AtomicReference<>();

    provider.asyncChat(
        List.of(new AgentUserMessage("hi")),
        ModelInfo.builder().provider("openai").name("MiniMax-M2.7").build(),
        Variant.builder().name("default").build(),
        List.of(),
        new AssistantResponseHandler() {
          @Override
          public void onTextDelta(String textDelta, AssistantResponseHandle handle) {
            text.append(textDelta);
          }

          @Override
          public void onThinkingDelta(String thinkingDelta, AssistantResponseHandle handle) {
            thinkingDeltas.add(thinkingDelta);
          }

          @Override
          public void onToolCallDelta(
              IndexedToolCallDelta toolCallDelta, AssistantResponseHandle handle) {}

          @Override
          public void onToolCallComplete(
              Integer index, ToolCall toolCall, AssistantResponseHandle handle) {}

          @Override
          public void onComplete(AssistantResponse response, AssistantResponseHandle handle) {
            responseRef.set(response);
          }

          @Override
          public void onError(Throwable error, AssistantResponseHandle handle) {
            throw new AssertionError(error);
          }
        });

    assertEquals("真实联调成功", text.toString());
    assertEquals(List.of("用户要求我只回复固定短语。"), thinkingDeltas);
    assertNotNull(responseRef.get());
    assertEquals("真实联调成功", responseRef.get().getText());
    assertEquals("用户要求我只回复固定短语。", responseRef.get().getThinking());
  }

  private static final class TestOpenAiModelProvider extends OpenAiModelProvider {

    private final StreamingChatModel overrideChatModel;

    private TestOpenAiModelProvider(ProviderInfo providerInfo) {
      this(providerInfo, null);
    }

    private TestOpenAiModelProvider(
        ProviderInfo providerInfo, StreamingChatModel overrideChatModel) {
      super(providerInfo);
      this.overrideChatModel = overrideChatModel;
    }

    @Override
    protected StreamingChatModel getChatModel(ModelInfo modelInfo, Variant variant) {
      if (overrideChatModel != null) {
        return overrideChatModel;
      }
      return super.getChatModel(modelInfo, variant);
    }

    private StreamingChatModel exposeChatModel() {
      return getChatModel(
          ModelInfo.builder().provider("openai").name("MiniMax-M2.7").build(),
          Variant.builder().name("default").build());
    }
  }

  private static final class ThinkTagStreamingChatModel implements StreamingChatModel {

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
      TestStreamingHandle streamingHandle = new TestStreamingHandle();
      handler.onPartialResponse(
          new PartialResponse("<thi"), new PartialResponseContext(streamingHandle));
      handler.onPartialResponse(
          new PartialResponse("nk>用户要求我只回复固定短语。</think>\n\n真实"),
          new PartialResponseContext(streamingHandle));
      handler.onPartialResponse(
          new PartialResponse("联调成功"), new PartialResponseContext(streamingHandle));
      handler.onCompleteResponse(
          ChatResponse.builder()
              .aiMessage(AiMessage.builder().text("<think>用户要求我只回复固定短语。</think>\n\n真实联调成功").build())
              .build());
    }
  }

  private static final class TestStreamingHandle implements StreamingHandle {

    private boolean cancelled;

    @Override
    public void cancel() {
      cancelled = true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }
  }
}
