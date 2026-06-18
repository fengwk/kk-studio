package fun.fengwk.kkstudio.agent.provider.support;

import fun.fengwk.kkstudio.agent.message.AgentUserMessage;
import fun.fengwk.kkstudio.agent.provider.AssistantResponse;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandle;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandler;
import fun.fengwk.kkstudio.agent.provider.Provider;
import fun.fengwk.kkstudio.agent.provider.fixtures.ProviderTestFixtures;
import fun.fengwk.kkstudio.agent.provider.fixtures.ProviderTestFixtures.ProviderLiveCase;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provider live test 公共基座。
 *
 * 测试场景从 JSON fixture 中加载，避免把 prompt/期望散落在测试代码中。
 *
 * @author fengwk
 */
@Slf4j
@Tag("provider-live")
public abstract class AbstractProviderLiveTestSupport extends AbstractProviderTestSupport {

    /**
     * 校验 provider 在最小文本请求下可以稳定返回流式文本与 metadata。
     */
    @Test
    @Timeout(120)
    public void shouldStreamPlainTextAndMetadata() throws Exception {
        assertLiveCase(ProviderTestFixtures.liveCase("plain_text_ok"));
    }

    /**
     * 校验 provider 能接受结构化工具 schema，而不要求模型实际触发工具调用。
     */
    @Test
    @Timeout(120)
    public void shouldAcceptStructuredToolSchemaWithoutCallingTool() throws Exception {
        assertLiveCase(ProviderTestFixtures.liveCase("tool_schema_without_call"));
    }

    /**
     * 校验 provider 在真实 live 环境下能够让模型产出 echo 工具调用。
     */
    @Test
    @Timeout(120)
    public void shouldGenerateEchoToolCall() throws Exception {
        assertLiveCase(ProviderTestFixtures.liveCase("echo_tool_call"));
    }

    /**
     * 执行一个 JSON live fixture，并按 fixture 断言文本或工具调用结果。
     */
    private void assertLiveCase(ProviderTestFixtures.ProviderLiveCase liveCase) throws Exception {
        List<ToolInfo> toolInfos = liveCase.requiresTool() ? List.of(echoTool()) : List.of();
        log.info("[{} live] prompt={}", providerName(), liveCase.prompt());
        log.info("[{} live] requiresTool={}", providerName(), liveCase.requiresTool());
        LiveResult result = invoke(liveCase.prompt(), toolInfos);

        log.info("[{} live] textDeltas={}", providerName(), result.textDeltas());
        if (result.thinkingDeltas() != null && !result.thinkingDeltas().isBlank()) {
            log.info("[{} live] thinkingDeltas={}", providerName(), result.thinkingDeltas());
        }
        log.info("[{} live] completedToolCalls={}", providerName(), result.completedToolCalls());
        if (result.response() != null) {
            log.info("[{} live] response.text={}", providerName(), result.response().getText());
            log.info("[{} live] response.thinking={}", providerName(), result.response().getThinking());
            log.info("[{} live] response.metadata={}", providerName(), result.response().getMetadata());
            log.info("[{} live] response.toolCalls={}", providerName(), result.response().getToolCalls());
        }

        assertNotNull(result.response());
        assertCommonMetadata(result.response().getMetadata());
        if (liveCase.expectedTextContains() != null) {
            assertNotNull(result.response().getText());
            assertTrue(result.response().getText().contains(liveCase.expectedTextContains()),
                () -> providerName() + " text response should contain '" + liveCase.expectedTextContains() + "', actual: " + result.response().getText());
        }
        if (liveCase.expectToolCall()) {
            assertNotNull(result.response().getToolCalls());
            assertTrue(!result.response().getToolCalls().isEmpty(), () -> providerName() + " should return at least one tool call");
            ToolCall toolCall = result.response().getToolCalls().get(0);
            assertEquals(liveCase.expectedToolName(), toolCall.getToolName());
            assertTrue(toolCall.getArguments() != null && toolCall.getArguments().contains(liveCase.expectedToolArgumentsContains()),
                () -> providerName() + " tool arguments should contain '" + liveCase.expectedToolArgumentsContains() + "', actual: " + toolCall.getArguments());
        } else {
            assertTrue(result.response().getToolCalls() == null || result.response().getToolCalls().isEmpty(),
                () -> providerName() + " should not call tool in non-tool live case");
        }
    }

    /**
     * 对真实 provider 发起一次 live 调用，并收集全部流式回调结果。
     */
    protected LiveResult invoke(String prompt, List<ToolInfo> toolInfos) throws Exception {
        Provider provider = provider();
        CountDownLatch done = new CountDownLatch(1);
        StringBuilder textDeltas = new StringBuilder();
        StringBuilder thinkingDeltas = new StringBuilder();
        List<IndexedToolCallDelta> partialToolCalls = new ArrayList<>();
        List<ToolCall> completedToolCalls = new ArrayList<>();
        AtomicReference<AssistantResponse> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        provider.asyncChat(
            List.of(new AgentUserMessage(prompt)),
            modelInfo(),
            variant(),
            toolInfos,
            new AssistantResponseHandler() {
                @Override
                public void onTextDelta(String textDelta, AssistantResponseHandle handle) {
                    textDeltas.append(textDelta);
                }

                @Override
                public void onThinkingDelta(String thinkingDelta, AssistantResponseHandle handle) {
                    thinkingDeltas.append(thinkingDelta);
                }

                @Override
                public void onToolCallDelta(IndexedToolCallDelta toolCallDelta, AssistantResponseHandle handle) {
                    partialToolCalls.add(toolCallDelta);
                }

                @Override
                public void onToolCallComplete(Integer index, ToolCall toolCall, AssistantResponseHandle handle) {
                    completedToolCalls.add(toolCall);
                }

                @Override
                public void onComplete(AssistantResponse response, AssistantResponseHandle handle) {
                    responseRef.set(response);
                    done.countDown();
                }

                @Override
                public void onError(Throwable error, AssistantResponseHandle handle) {
                    errorRef.set(error);
                    done.countDown();
                }
            });

        assertTrue(done.await(90, TimeUnit.SECONDS), () -> providerName() + " live test timed out");
        if (errorRef.get() != null) {
            fail(providerName() + " live call failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        return new LiveResult(textDeltas.toString(), thinkingDeltas.toString(), partialToolCalls, completedToolCalls, responseRef.get());
    }

    /**
     * LiveResult 保存一次 live 调用过程中收集到的全部结果。
     */
    protected record LiveResult(String textDeltas,
                                String thinkingDeltas,
                                List<IndexedToolCallDelta> partialToolCalls,
                                List<ToolCall> completedToolCalls,
                                AssistantResponse response) {
    }

}
