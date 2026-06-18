package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import fun.fengwk.kkstudio.agent.message.AgentAssistantMessage;
import fun.fengwk.kkstudio.agent.message.AgentMessage;
import fun.fengwk.kkstudio.agent.message.AgentSystemMessage;
import fun.fengwk.kkstudio.agent.message.AgentToolMessage;
import fun.fengwk.kkstudio.agent.message.AgentUserMessage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import fun.fengwk.kkstudio.agent.tool.schema.ToolObjectSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolStringSchema;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            List.of(new AgentUserMessage("hello")),
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

    /**
     * 校验自有 AgentMessage 会在 provider 边界转换为 LangChain4j ChatMessage。
     */
    @Test
    public void testBuildChatRequestMapsAgentMessagesToLangChainMessages() {
        TestProvider provider = new TestProvider();
        ToolCall toolCall = new ToolCall();
        toolCall.setToolCallId("call_1");
        toolCall.setToolName("echo");
        toolCall.setArguments("{\"text\":\"OK\"}");
        ToolContent textContent = new ToolContent();
        textContent.setType(ToolContentType.text);
        textContent.setText("done");
        ToolContent imageContent = new ToolContent();
        imageContent.setType(ToolContentType.image);
        imageContent.setData("aW1n");
        imageContent.setMime("image/png");

        ChatRequest request = provider.exposeBuildChatRequest(
            List.of(
                new AgentSystemMessage("sys"),
                new AgentUserMessage("hello"),
                new AgentAssistantMessage(null, "think", List.of(toolCall)),
                new AgentToolMessage("call_1", "echo", List.of(textContent, imageContent), true)),
            ModelInfo.builder().provider("openai").name("gpt-test").build(),
            Variant.builder().name("high").build(),
            List.of());

        assertEquals(4, request.messages().size());
        SystemMessage systemMessage = assertInstanceOf(SystemMessage.class, request.messages().get(0));
        assertEquals("sys", systemMessage.text());
        UserMessage userMessage = assertInstanceOf(UserMessage.class, request.messages().get(1));
        assertEquals("hello", userMessage.singleText());

        AiMessage aiMessage = assertInstanceOf(AiMessage.class, request.messages().get(2));
        assertEquals("think", aiMessage.thinking());
        assertEquals(1, aiMessage.toolExecutionRequests().size());
        ToolExecutionRequest toolExecutionRequest = aiMessage.toolExecutionRequests().get(0);
        assertEquals("call_1", toolExecutionRequest.id());
        assertEquals("echo", toolExecutionRequest.name());
        assertEquals("{\"text\":\"OK\"}", toolExecutionRequest.arguments());

        ToolExecutionResultMessage toolMessage = assertInstanceOf(ToolExecutionResultMessage.class, request.messages().get(3));
        assertEquals("call_1", toolMessage.id());
        assertEquals("echo", toolMessage.toolName());
        assertTrue(toolMessage.isError());
        assertEquals(2, toolMessage.contents().size());
        TextContent convertedTextContent = assertInstanceOf(TextContent.class, toolMessage.contents().get(0));
        assertEquals("done", convertedTextContent.text());
        ImageContent convertedImageContent = assertInstanceOf(ImageContent.class, toolMessage.contents().get(1));
        assertEquals("aW1n", convertedImageContent.image().base64Data());
        assertEquals("image/png", convertedImageContent.image().mimeType());
    }

    /**
     * 校验 assistant 文本消息、单文本工具结果以及音视频内容的转换分支。
     */
    @Test
    public void testBuildChatRequestMapsAdditionalAgentMessageBranches() {
        TestProvider provider = new TestProvider();
        ToolCall incompleteToolCall = new ToolCall();
        incompleteToolCall.setToolCallId(" ");
        incompleteToolCall.setToolName("echo");
        ToolContent noTypeContent = new ToolContent();
        ToolContent singleTextContent = new ToolContent();
        singleTextContent.setType(ToolContentType.text);
        singleTextContent.setText("done");
        ToolContent audioContent = new ToolContent();
        audioContent.setType(ToolContentType.audio);
        audioContent.setData("YXVkaW8=");
        audioContent.setMime("audio/wav");
        ToolContent videoContent = new ToolContent();
        videoContent.setType(ToolContentType.video);
        videoContent.setData("dmlkZW8=");
        videoContent.setMime("video/mp4");

        ChatRequest request = provider.exposeBuildChatRequest(
            List.of(
                new AgentAssistantMessage("plain", null, List.of()),
                new AgentAssistantMessage(null, null, List.of(incompleteToolCall)),
                new AgentToolMessage("call_text", "echo", List.of(noTypeContent, singleTextContent), false),
                new AgentToolMessage("call_media", "echo", List.of(audioContent, videoContent), false)),
            ModelInfo.builder().provider("openai").name("gpt-test").build(),
            Variant.builder().name("high").build(),
            List.of());

        AiMessage textMessage = assertInstanceOf(AiMessage.class, request.messages().get(0));
        assertEquals("plain", textMessage.text());
        AiMessage emptyToolCallMessage = assertInstanceOf(AiMessage.class, request.messages().get(1));
        assertEquals("", emptyToolCallMessage.text());
        assertTrue(emptyToolCallMessage.toolExecutionRequests().isEmpty());

        ToolExecutionResultMessage singleTextToolMessage = assertInstanceOf(ToolExecutionResultMessage.class, request.messages().get(2));
        assertEquals("done", singleTextToolMessage.text());
        ToolExecutionResultMessage mediaToolMessage = assertInstanceOf(ToolExecutionResultMessage.class, request.messages().get(3));
        AudioContent convertedAudioContent = assertInstanceOf(AudioContent.class, mediaToolMessage.contents().get(0));
        assertEquals("YXVkaW8=", convertedAudioContent.audio().base64Data());
        assertEquals("audio/wav", convertedAudioContent.audio().mimeType());
        VideoContent convertedVideoContent = assertInstanceOf(VideoContent.class, mediaToolMessage.contents().get(1));
        assertEquals("dmlkZW8=", convertedVideoContent.video().base64Data());
        assertEquals("video/mp4", convertedVideoContent.video().mimeType());
    }

    /**
     * 校验 provider 边界转换会拒绝 null message 与非法媒体内容。
     */
    @Test
    public void testBuildChatRequestRejectsInvalidAgentMessages() {
        TestProvider provider = new TestProvider();
        ModelInfo modelInfo = ModelInfo.builder().provider("openai").name("gpt-test").build();
        Variant variant = Variant.builder().name("high").build();
        ToolContent invalidImage = new ToolContent();
        invalidImage.setType(ToolContentType.image);
        invalidImage.setData("aW1n");
        invalidImage.setMime("text/plain");

        assertThrows(IllegalArgumentException.class,
            () -> provider.exposeBuildChatRequest(Collections.singletonList(null), modelInfo, variant, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> provider.exposeBuildChatRequest(List.of(new AgentToolMessage("call_1", "echo", List.of(invalidImage), false)), modelInfo, variant, List.of()));
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
        private ChatRequest exposeBuildChatRequest(List<AgentMessage> messages,
                                                    ModelInfo modelInfo,
                                                    Variant variant,
                                                    List<ToolInfo> toolInfos) {
            return buildChatRequest(messages, modelInfo, variant, resolveToolSpecifications(toolInfos));
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
