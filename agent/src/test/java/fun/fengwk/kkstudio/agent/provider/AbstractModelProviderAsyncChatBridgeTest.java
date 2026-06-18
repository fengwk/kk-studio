package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
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
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author fengwk
 */
public class AbstractModelProviderAsyncChatBridgeTest {

    /**
     * 校验 AbstractModelProvider 能把底层流式回调完整桥接到统一 handler。
     */
    @Test
    public void testBridgesStreamingCallbacks() {
        FakeStreamingChatModel model = new FakeStreamingChatModel();
        TestProvider provider = new TestProvider(model);
        List<String> textDeltas = new ArrayList<>();
        List<String> thinkingDeltas = new ArrayList<>();
        List<IndexedToolCallDelta> toolCallDeltas = new ArrayList<>();
        List<ToolCall> completeToolCalls = new ArrayList<>();
        AtomicReference<AssistantResponse> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        provider.asyncChat(
            List.<ChatMessage>of(UserMessage.from("hello")),
            ModelInfo.builder().provider("openai").name("gpt-test").build(),
            Variant.builder().name("high").build(),
            List.<ToolInfo>of(),
            new AssistantResponseHandler() {
                @Override
                public void onTextDelta(String textDelta, AssistantResponseHandle handle) {
                    textDeltas.add(textDelta);
                }

                @Override
                public void onThinkingDelta(String thinkingDelta, AssistantResponseHandle handle) {
                    thinkingDeltas.add(thinkingDelta);
                }

                @Override
                public void onToolCallDelta(IndexedToolCallDelta toolCallDelta, AssistantResponseHandle handle) {
                    toolCallDeltas.add(toolCallDelta);
                }

                @Override
                public void onToolCallComplete(Integer index, ToolCall toolCall, AssistantResponseHandle handle) {
                    completeToolCalls.add(toolCall);
                }

                @Override
                public void onComplete(AssistantResponse response, AssistantResponseHandle handle) {
                    responseRef.set(response);
                }

                @Override
                public void onError(Throwable error, AssistantResponseHandle handle) {
                    errorRef.set(error);
                }
            });

        assertEquals(List.of("Hel", "lo"), textDeltas);
        assertEquals(List.of("thi", "nk"), thinkingDeltas);
        assertEquals(2, toolCallDeltas.size());
        assertEquals(1, completeToolCalls.size());
        assertNotNull(responseRef.get());
        assertNull(errorRef.get());
        assertEquals("Hello", responseRef.get().getText());
        assertEquals("think", responseRef.get().getThinking());
        assertEquals(1, responseRef.get().getToolCalls().size());
        AssistantMetadata metadata = responseRef.get().getMetadata();
        assertNotNull(metadata);
        assertEquals("resp_1", metadata.getId());
        assertEquals("gpt-test", metadata.getModelName());
        assertEquals(30, metadata.getUsage().getTotalTokens());
    }

    /**
     * 校验 provider 层会为缺失 id 但存在 name 的 tool call 生成兼容 id，并在 complete/response 间保持一致。
     */
    @Test
    public void testGeneratesCompatibilityToolCallIdWhenMissing() {
        TestProvider provider = new TestProvider(new IdlessToolCallStreamingChatModel());
        CapturingHandler handler = new CapturingHandler();

        provider.asyncChat(
            List.<ChatMessage>of(UserMessage.from("hello")),
            ModelInfo.builder().provider("openai").name("gpt-test").build(),
            Variant.builder().name("high").build(),
            List.<ToolInfo>of(),
            handler);

        assertEquals(1, handler.completeToolCalls.size());
        assertNotNull(handler.response);
        assertEquals(1, handler.response.getToolCalls().size());
        String generatedId = handler.completeToolCalls.get(0).getToolCallId();
        assertNotNull(generatedId);
        assertTrue(generatedId.startsWith("compat_openai_0_"));
        assertEquals(generatedId, handler.response.getToolCalls().get(0).getToolCallId());
        assertEquals("echo", handler.response.getToolCalls().get(0).getToolName());
    }

    /**
     * 校验 complete 回调已有 provider id、最终 response 缺失 id 时会复用前者，避免同一 tool call 产生两个 id。
     */
    @Test
    public void testReusesCompleteToolCallIdWhenResponseMissesId() {
        TestProvider provider = new TestProvider(new IdfulCompleteIdlessResponseStreamingChatModel());
        CapturingHandler handler = new CapturingHandler();

        provider.asyncChat(
            List.<ChatMessage>of(UserMessage.from("hello")),
            ModelInfo.builder().provider("openai").name("gpt-test").build(),
            Variant.builder().name("high").build(),
            List.<ToolInfo>of(),
            handler);

        assertEquals(1, handler.completeToolCalls.size());
        assertNotNull(handler.response);
        assertEquals(1, handler.response.getToolCalls().size());
        assertEquals("call_1", handler.completeToolCalls.get(0).getToolCallId());
        assertEquals("call_1", handler.response.getToolCalls().get(0).getToolCallId());
    }

    /**
     * 校验 provider 层会丢弃缺失 name 的畸形 tool call，避免无执行意图的调用污染上层状态。
     */
    @Test
    public void testDropsMalformedToolCallWithoutName() {
        TestProvider provider = new TestProvider(new NamelessToolCallStreamingChatModel());
        CapturingHandler handler = new CapturingHandler();

        provider.asyncChat(
            List.<ChatMessage>of(UserMessage.from("hello")),
            ModelInfo.builder().provider("openai").name("gpt-test").build(),
            Variant.builder().name("high").build(),
            List.<ToolInfo>of(),
            handler);

        assertEquals(0, handler.completeToolCalls.size());
        assertNotNull(handler.response);
        assertEquals(0, handler.response.getToolCalls().size());
    }

    /**
     * 校验在真实 StreamingHandle 绑定前先 cancel()，取消信号仍会在绑定后传播。
     */
    @Test
    public void testCancelBeforeBindPropagatesToStreamingHandle() {
        DeferredStreamingChatModel model = new DeferredStreamingChatModel();
        TestProvider provider = new TestProvider(model);
        AssistantResponseHandle handle = provider.asyncChat(
            List.<ChatMessage>of(UserMessage.from("hello")),
            ModelInfo.builder().provider("openai").name("gpt-test").build(),
            Variant.builder().name("high").build(),
            List.<ToolInfo>of(),
            new NoopHandler());

        assertFalse(model.streamingHandle.isCancelled());
        handle.cancel();
        assertTrue(handle.isCancelled());
        model.emitPartialText("hi");
        assertTrue(model.streamingHandle.isCancelled());
    }

    /**
     * 校验 asyncChat(...) 的基础参数校验。
     */
    @Test
    public void testValidatesArguments() {
        TestProvider provider = new TestProvider(new DeferredStreamingChatModel());
        assertThrows(IllegalArgumentException.class, () -> provider.asyncChat(null, ModelInfo.builder().name("m").build(), Variant.builder().name("v").build(), List.of(), new NoopHandler()));
        assertThrows(IllegalArgumentException.class, () -> provider.asyncChat(List.of(UserMessage.from("hi")), null, Variant.builder().name("v").build(), List.of(), new NoopHandler()));
        assertThrows(IllegalArgumentException.class, () -> provider.asyncChat(List.of(UserMessage.from("hi")), ModelInfo.builder().name("m").build(), null, List.of(), new NoopHandler()));
        assertThrows(IllegalArgumentException.class, () -> provider.asyncChat(List.of(UserMessage.from("hi")), ModelInfo.builder().name("m").build(), Variant.builder().name("v").build(), List.of(), null));
    }

    private static final class TestProvider extends AbstractModelProvider {

        private final StreamingChatModel model;

        /**
         * 使用指定 fake model 构造测试 provider。
         */
        private TestProvider(StreamingChatModel model) {
            super(ProviderInfo.builder().providerType(ProviderType.openai).build());
            this.model = model;
        }

        /**
         * 返回当前测试注入的 fake StreamingChatModel。
         */
        @Override
        protected StreamingChatModel getChatModel(ModelInfo modelInfo, Variant variant) {
            return model;
        }

    }

    private static final class FakeStreamingChatModel implements StreamingChatModel {

        /**
         * 按固定顺序发出 partial / complete 回调，用于覆盖桥接主路径。
         */
        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            TestStreamingHandle streamingHandle = new TestStreamingHandle();
            handler.onPartialResponse(new PartialResponse("Hel"), new PartialResponseContext(streamingHandle));
            handler.onPartialResponse(new PartialResponse("lo"), new PartialResponseContext(streamingHandle));
            handler.onPartialThinking(new PartialThinking("thi"), new PartialThinkingContext(streamingHandle));
            handler.onPartialThinking(new PartialThinking("nk"), new PartialThinkingContext(streamingHandle));
            handler.onPartialToolCall(PartialToolCall.builder().index(0).id("call_1").name("echo").partialArguments("{\"text\":\"").build(), new PartialToolCallContext(streamingHandle));
            handler.onPartialToolCall(PartialToolCall.builder().index(0).id("call_1").name("echo").partialArguments("OK\"}").build(), new PartialToolCallContext(streamingHandle));
            handler.onCompleteToolCall(new CompleteToolCall(0, ToolExecutionRequest.builder().id("call_1").name("echo").arguments("{\"text\":\"OK\"}").build()));
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.builder()
                    .text("Hello")
                    .thinking("think")
                    .toolExecutionRequests(List.of(ToolExecutionRequest.builder().id("call_1").name("echo").arguments("{\"text\":\"OK\"}").build()))
                    .build())
                .metadata(ChatResponseMetadata.builder()
                    .id("resp_1")
                    .modelName("gpt-test")
                    .finishReason(FinishReason.STOP)
                    .tokenUsage(new TokenUsage(10, 20, 30))
                    .build())
                .build());
        }

    }

    private static final class IdlessToolCallStreamingChatModel implements StreamingChatModel {

        /**
         * 模拟底层 provider 返回 name/arguments 完整但 id 缺失的 tool call。
         */
        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onCompleteToolCall(new CompleteToolCall(0, ToolExecutionRequest.builder()
                .name("echo")
                .arguments("{\"text\":\"OK\"}")
                .build()));
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.builder()
                    .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .name("echo")
                        .arguments("{\"text\":\"OK\"}")
                        .build()))
                    .build())
                .build());
        }

    }

    private static final class IdfulCompleteIdlessResponseStreamingChatModel implements StreamingChatModel {

        /**
         * 模拟同一个 tool call 在流式 complete 中有 id，但最终 response 中缺失 id 的兼容性问题。
         */
        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onCompleteToolCall(new CompleteToolCall(0, ToolExecutionRequest.builder()
                .id("call_1")
                .name("echo")
                .arguments("{\"text\":\"OK\"}")
                .build()));
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.builder()
                    .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .name("echo")
                        .arguments("{\"text\":\"OK\"}")
                        .build()))
                    .build())
                .build());
        }

    }

    private static final class NamelessToolCallStreamingChatModel implements StreamingChatModel {

        /**
         * 模拟底层 provider 或 LangChain4j 误发没有 name 的占位 tool call。
         */
        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onCompleteToolCall(new CompleteToolCall(0, ToolExecutionRequest.builder()
                .arguments("{}")
                .build()));
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.builder()
                    .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .arguments("{}")
                        .build()))
                    .build())
                .build());
        }

    }

    private static final class DeferredStreamingChatModel implements StreamingChatModel {

        private StreamingChatResponseHandler handler;
        private final TestStreamingHandle streamingHandle = new TestStreamingHandle();

        /**
         * 保存 handler，等待测试在稍后手动触发回调。
         */
        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            this.handler = handler;
        }

        /**
         * 人工发出一条 partial text 回调。
         */
        private void emitPartialText(String text) {
            handler.onPartialResponse(new PartialResponse(text), new PartialResponseContext(streamingHandle));
        }

    }

    private static final class TestStreamingHandle implements StreamingHandle {

        private boolean cancelled;

        /**
         * 记录取消状态。
         */
        @Override
        public void cancel() {
            cancelled = true;
        }

        /**
         * 返回当前是否已取消。
         */
        @Override
        public boolean isCancelled() {
            return cancelled;
        }

    }

    private static final class CapturingHandler implements AssistantResponseHandler {

        private final List<ToolCall> completeToolCalls = new ArrayList<>();
        private AssistantResponse response;

        /**
         * 忽略文本增量。
         */
        @Override
        public void onTextDelta(String textDelta, AssistantResponseHandle handle) {
        }

        /**
         * 忽略 thinking 增量。
         */
        @Override
        public void onThinkingDelta(String thinkingDelta, AssistantResponseHandle handle) {
        }

        /**
         * 忽略 tool call 增量。
         */
        @Override
        public void onToolCallDelta(IndexedToolCallDelta toolCallDelta, AssistantResponseHandle handle) {
        }

        /**
         * 收集完整 tool call。
         */
        @Override
        public void onToolCallComplete(Integer index, ToolCall toolCall, AssistantResponseHandle handle) {
            completeToolCalls.add(toolCall);
        }

        /**
         * 收集最终 assistant 响应。
         */
        @Override
        public void onComplete(AssistantResponse response, AssistantResponseHandle handle) {
            this.response = response;
        }

        /**
         * 测试不期望错误回调。
         */
        @Override
        public void onError(Throwable error, AssistantResponseHandle handle) {
        }

    }

    private static final class NoopHandler implements AssistantResponseHandler {

        /**
         * 忽略文本增量。
         */
        @Override
        public void onTextDelta(String textDelta, AssistantResponseHandle handle) {
        }

        /**
         * 忽略 thinking 增量。
         */
        @Override
        public void onThinkingDelta(String thinkingDelta, AssistantResponseHandle handle) {
        }

        /**
         * 忽略 tool call 增量。
         */
        @Override
        public void onToolCallDelta(IndexedToolCallDelta toolCallDelta, AssistantResponseHandle handle) {
        }

        /**
         * 忽略 tool call 完整结果。
         */
        @Override
        public void onToolCallComplete(Integer index, ToolCall toolCall, AssistantResponseHandle handle) {
        }

        /**
         * 忽略完成结果。
         */
        @Override
        public void onComplete(AssistantResponse response, AssistantResponseHandle handle) {
        }

        /**
         * 忽略异常结果。
         */
        @Override
        public void onError(Throwable error, AssistantResponseHandle handle) {
        }

    }

}
