package fun.fengwk.kkstudio.agent.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;

/**
 * @author fengwk
 */
public class ProviderMetadataMappingTest {

  /** 校验 Anthropic provider 能补齐 cacheRead/cacheWrite token 映射。 */
  @Test
  public void testAnthropicProviderMapsCacheUsage() {
    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            ProviderInfo.builder().providerType(ProviderType.anthropic).build());
    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder()
            .id("resp_1")
            .modelName("MiniMax-M2.7")
            .finishReason(FinishReason.STOP)
            .tokenUsage(
                AnthropicTokenUsage.builder()
                    .inputTokenCount(10)
                    .outputTokenCount(20)
                    .cacheReadInputTokens(4)
                    .cacheCreationInputTokens(5)
                    .build())
            .build();

    AssistantMetadata assistantMetadata = provider.toAssistantMetadata(metadata);

    assertNotNull(assistantMetadata);
    assertEquals("resp_1", assistantMetadata.getId());
    assertEquals("MiniMax-M2.7", assistantMetadata.getModelName());
    assertEquals(10, assistantMetadata.getUsage().getInputTokens());
    assertEquals(20, assistantMetadata.getUsage().getOutputTokens());
    assertEquals(30, assistantMetadata.getUsage().getTotalTokens());
    assertEquals(4, assistantMetadata.getUsage().getCacheReadTokens());
    assertEquals(5, assistantMetadata.getUsage().getCacheWriteTokens());
  }

  /** 校验 OpenAI provider 能补齐 cachedTokens -> cacheReadTokens 映射。 */
  @Test
  public void testOpenAiProviderMapsCachedReadTokens() {
    OpenAiModelProvider provider =
        new OpenAiModelProvider(ProviderInfo.builder().providerType(ProviderType.openai).build());
    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder()
            .id("resp_1")
            .modelName("MiniMax-M2.7")
            .finishReason(FinishReason.STOP)
            .tokenUsage(
                OpenAiTokenUsage.builder()
                    .inputTokenCount(10)
                    .outputTokenCount(20)
                    .inputTokensDetails(
                        OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(3).build())
                    .build())
            .build();

    AssistantMetadata assistantMetadata = provider.toAssistantMetadata(metadata);

    assertNotNull(assistantMetadata);
    assertEquals(10, assistantMetadata.getUsage().getInputTokens());
    assertEquals(20, assistantMetadata.getUsage().getOutputTokens());
    assertEquals(null, assistantMetadata.getUsage().getTotalTokens());
    assertEquals(3, assistantMetadata.getUsage().getCacheReadTokens());
    assertEquals(null, assistantMetadata.getUsage().getCacheWriteTokens());
  }
}
