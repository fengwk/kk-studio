package fun.fengwk.kkstudio.agent.runtime;

import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author fengwk
 */
public class AssistantMetadataMapperTest {

    private final AssistantMetadataMapper mapper = new AssistantMetadataMapper();

    @Test
    public void testMapsCommonAndCacheUsageFields() {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
            .id("resp_1")
            .modelName("gpt-test")
            .finishReason(FinishReason.STOP)
            .tokenUsage(new CacheAwareTokenUsage(10, 20, 30, 4, 5))
            .build();

        AssistantMetadata assistantMetadata = mapper.from(metadata);

        assertNotNull(assistantMetadata);
        assertEquals("resp_1", assistantMetadata.getId());
        assertEquals("gpt-test", assistantMetadata.getModelName());
        assertEquals("STOP", assistantMetadata.getFinishReason());
        assertEquals(10, assistantMetadata.getUsage().getInputTokens());
        assertEquals(20, assistantMetadata.getUsage().getOutputTokens());
        assertEquals(30, assistantMetadata.getUsage().getTotalTokens());
        assertEquals(4, assistantMetadata.getUsage().getCacheReadTokens());
        assertEquals(5, assistantMetadata.getUsage().getCacheWriteTokens());
    }

    private static class CacheAwareTokenUsage extends TokenUsage {

        private final Integer cacheReadInputTokens;
        private final Integer cacheCreationInputTokens;

        private CacheAwareTokenUsage(Integer input, Integer output, Integer total, Integer cacheReadInputTokens, Integer cacheCreationInputTokens) {
            super(input, output, total);
            this.cacheReadInputTokens = cacheReadInputTokens;
            this.cacheCreationInputTokens = cacheCreationInputTokens;
        }

        public Integer cacheReadInputTokens() {
            return cacheReadInputTokens;
        }

        public Integer cacheCreationInputTokens() {
            return cacheCreationInputTokens;
        }
    }

}
