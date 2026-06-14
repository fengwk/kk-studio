package fun.fengwk.kkstudio.core.agent.runtime.provider;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Provider 请求构建测试。
 *
 * @author fengwk
 */
public class ProviderBuildChatRequestTest {

    @Test
    public void testOpenAiBuildsProviderSpecificParameters() {
        ToolSpecification toolSpecification = ToolSpecification.builder()
            .name("read_file")
            .description("Read file")
            .build();
        ModelRequestConfig modelRequestConfig = ModelRequestConfig.builder()
            .modelName("gpt-test")
            .temperature(0.1)
            .maxOutputTokens(1024)
            .toolSpecifications(List.of(toolSpecification))
            .build();

        OpenAiModelProvider provider = new OpenAiModelProvider(providerConfig(ProviderType.openai));
        ChatRequest chatRequest = provider.buildChatRequest(List.of(UserMessage.userMessage("hi")), modelRequestConfig);

        OpenAiChatRequestParameters parameters = assertInstanceOf(
            OpenAiChatRequestParameters.class, chatRequest.parameters());
        assertEquals("gpt-test", parameters.modelName());
        assertEquals(0.1, parameters.temperature());
        assertEquals(1024, parameters.maxOutputTokens());
        assertEquals(List.of(toolSpecification), parameters.toolSpecifications());
    }

    @Test
    public void testOpenAiResponsesBuildsProviderSpecificParameters() {
        ModelRequestConfig modelRequestConfig = ModelRequestConfig.builder()
            .modelName("gpt-responses-test")
            .topP(0.9)
            .build();

        OpenAiResponseModelProvider provider = new OpenAiResponseModelProvider(providerConfig(ProviderType.openai_response));
        ChatRequest chatRequest = provider.buildChatRequest(List.of(UserMessage.userMessage("hi")), modelRequestConfig);

        OpenAiOfficialResponsesChatRequestParameters parameters = assertInstanceOf(
            OpenAiOfficialResponsesChatRequestParameters.class, chatRequest.parameters());
        assertEquals("gpt-responses-test", parameters.modelName());
        assertEquals(0.9, parameters.topP());
    }

    private ProviderConfig providerConfig(ProviderType providerType) {
        return ProviderConfig.builder()
            .providerType(providerType)
            .baseUrl("https://example.com")
            .apiKey("test-key")
            .build();
    }

}
