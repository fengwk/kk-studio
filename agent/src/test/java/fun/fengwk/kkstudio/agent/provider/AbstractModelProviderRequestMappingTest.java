package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import fun.fengwk.kkstudio.agent.tool.schema.ToolObjectSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolStringSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author fengwk
 */
public class AbstractModelProviderRequestMappingTest {

    /**
     * 校验通用参数与工具 schema 能正确进入底层 ChatRequest。
     */
    @Test
    public void testBuildChatRequestMapsCommonParametersAndToolSchema() {
        TestProvider provider = new TestProvider();
        ModelInfo modelInfo = ModelInfo.builder().provider("openai").name("gpt-test").build();
        Variant variant = Variant.builder()
            .name("high")
            .temperature(0.1D)
            .topP(0.9D)
            .topK(32)
            .frequencyPenalty(0.2D)
            .presencePenalty(0.3D)
            .maxOutputTokens(256)
            .stopSequences(List.of("STOP"))
            .build();
        ToolInfo toolInfo = ToolInfo.builder()
            .name("echo")
            .description("Echo text")
            .inputSchema(ToolParamsSchema.builder()
                .description("echo params")
                .properties(Map.of(
                    "text", ToolStringSchema.builder().description("text").build(),
                    "meta", ToolObjectSchema.builder()
                        .description("meta")
                        .properties(Map.of("traceId", ToolStringSchema.builder().description("trace").build()))
                        .build()))
                .required(List.of("text"))
                .additionalProperties(false)
                .build())
            .build();

        ChatRequest request = provider.exposeBuildChatRequest(
            List.<ChatMessage>of(UserMessage.from("hello")),
            modelInfo,
            variant,
            List.of(toolInfo));

        assertEquals("gpt-test", request.modelName());
        assertEquals(0.1D, request.temperature());
        assertEquals(0.9D, request.topP());
        assertEquals(32, request.topK());
        assertEquals(0.2D, request.frequencyPenalty());
        assertEquals(0.3D, request.presencePenalty());
        assertEquals(256, request.maxOutputTokens());
        assertEquals(List.of("STOP"), request.stopSequences());
        assertEquals(ToolChoice.REQUIRED, request.toolChoice());
        assertEquals(1, request.toolSpecifications().size());
        JsonObjectSchema params = request.toolSpecifications().get(0).parameters();
        assertNotNull(params);
        assertEquals("echo params", params.description());
        assertEquals(List.of("text"), params.required());
        assertEquals(false, params.additionalProperties());
        assertNotNull(params.properties().get("text"));
        assertNotNull(params.properties().get("meta"));
    }

    private static final class TestProvider extends AbstractModelProvider {

        /**
         * 构造最小 request-mapping 测试 provider。
         */
        private TestProvider() {
            super(ProviderInfo.builder().providerType(ProviderType.openai).build());
        }

        /**
         * 暴露受保护的 buildChatRequest(...) 供测试调用。
         */
        private ChatRequest exposeBuildChatRequest(List<ChatMessage> chatMessages,
                                                   ModelInfo modelInfo,
                                                   Variant variant,
                                                   List<ToolInfo> toolInfos) {
            return buildChatRequest(chatMessages, modelInfo, variant, resolveToolSpecifications(toolInfos));
        }

        /**
         * 为测试额外注入 toolChoice，验证 setParameters(...) 的扩展点确实生效。
         */
        @Override
        protected DefaultChatRequestParameters.Builder<?> setParameters(DefaultChatRequestParameters.Builder<?> parametersBuilder,
                                                                        ModelInfo modelInfo,
                                                                        Variant variant,
                                                                        List<ToolSpecification> toolSpecifications) {
            return parametersBuilder.toolChoice(ToolChoice.REQUIRED);
        }

        /**
         * 本测试不需要真实 StreamingChatModel，实现直接抛异常。
         */
        @Override
        protected StreamingChatModel getChatModel(ModelInfo modelInfo,
                                                  Variant variant) {
            throw new UnsupportedOperationException();
        }

    }

}
