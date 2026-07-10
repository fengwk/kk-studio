package fun.fengwk.kkstudio.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.message.AgentAssistantMessage;
import fun.fengwk.kkstudio.agent.message.AgentMessage;
import fun.fengwk.kkstudio.agent.message.AgentSystemMessage;
import fun.fengwk.kkstudio.agent.message.AgentUserMessage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.ModelRegistry;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.AssistantResponse;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandle;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandler;
import fun.fengwk.kkstudio.agent.provider.Provider;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderRegistry;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.agent.session.Branch;
import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.SessionManager;
import fun.fengwk.kkstudio.agent.session.SessionManagerImpl;
import fun.fengwk.kkstudio.agent.session.payload.AssistantDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.session.payload.ToolDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolErrorPayload;
import fun.fengwk.kkstudio.agent.session.projection.DefaultSessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventProjection;
import fun.fengwk.kkstudio.agent.session.repo.SessionEventRepository;
import fun.fengwk.kkstudio.agent.session.repo.SessionRepository;
import fun.fengwk.kkstudio.agent.tool.DefaultToolRegistry;
import fun.fengwk.kkstudio.agent.tool.NoopToolExecutionHandle;
import fun.fengwk.kkstudio.agent.tool.Tool;
import fun.fengwk.kkstudio.agent.tool.ToolCallRequest;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandle;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandler;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import fun.fengwk.kkstudio.agent.tool.execution.ToolCallExecutor;
import fun.fengwk.kkstudio.agent.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolStringSchema;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * @author fengwk
 */
public class AgentMainLoopTest {

  @Test
  public void testRetryWritesNewAssistantLifecycle() {
    RecordingContext context = new RecordingContext();
    context.provider.enqueue(
        handler -> handler.onError(new RuntimeException("timeout"), noopHandle()));
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder().text("done").metadata(new AssistantMetadata()).build(),
                noopHandle()));

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));

    assertEquals(
        List.of(
            SessionEventType.set_agent_info,
            SessionEventType.set_model_info,
            SessionEventType.assistant_start,
            SessionEventType.assistant_error,
            SessionEventType.assistant_start,
            SessionEventType.assistant_delta,
            SessionEventType.assistant_end),
        context.eventTypes());
    AssistantStartPayload firstStart = (AssistantStartPayload) context.events.get(2).getPayload();
    AssistantStartPayload secondStart = (AssistantStartPayload) context.events.get(4).getPayload();
    assertEquals(List.of("hello"), firstStart.getUserMessages());
    assertEquals(List.of(), secondStart.getUserMessages());
    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  @Test
  public void testAssistantStreamingDeltasAndCompleteGap() {
    RecordingContext context = new RecordingContext();
    ToolCall completeToolCall = new ToolCall();
    completeToolCall.setToolCallId("call_1");
    completeToolCall.setToolName("echo");
    completeToolCall.setArguments("{\"text\":\"OK\"}");
    context.provider.enqueue(
        handler -> {
          AssistantResponseHandle handle = noopHandle();
          handler.onTextDelta("hel", handle);
          handler.onThinkingDelta("thin", handle);

          ToolCallDelta toolCallDelta = new ToolCallDelta();
          toolCallDelta.setToolCallId("call_1");
          toolCallDelta.setToolName("echo");
          toolCallDelta.setArgumentsDelta("{\"text\":");
          IndexedToolCallDelta indexedToolCallDelta = new IndexedToolCallDelta();
          indexedToolCallDelta.setIndex(0);
          indexedToolCallDelta.setToolCallDelta(toolCallDelta);
          handler.onToolCallDelta(indexedToolCallDelta, handle);
          handler.onToolCallComplete(0, completeToolCall, handle);
          handler.onComplete(
              AssistantResponse.builder()
                  .text("hello")
                  .thinking("thinking")
                  .metadata(new AssistantMetadata())
                  .build(),
              handle);
        });

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));

    assertEquals(
        List.of(
            SessionEventType.set_agent_info,
            SessionEventType.set_model_info,
            SessionEventType.assistant_start,
            SessionEventType.assistant_delta,
            SessionEventType.assistant_delta,
            SessionEventType.assistant_delta,
            SessionEventType.assistant_delta,
            SessionEventType.assistant_delta,
            SessionEventType.assistant_end),
        context.eventTypes());
    AssistantDeltaPayload textDelta = (AssistantDeltaPayload) context.events.get(3).getPayload();
    AssistantDeltaPayload thinkingDelta =
        (AssistantDeltaPayload) context.events.get(4).getPayload();
    AssistantDeltaPayload toolCallGap = (AssistantDeltaPayload) context.events.get(6).getPayload();
    AssistantDeltaPayload completionGap =
        (AssistantDeltaPayload) context.events.get(7).getPayload();
    assertEquals("hel", textDelta.getTextDelta());
    assertEquals("thin", thinkingDelta.getThinkingDelta());
    assertEquals(
        "\"OK\"}", toolCallGap.getToolCallsDelta().get(0).getToolCallDelta().getArgumentsDelta());
    assertEquals("lo", completionGap.getTextDelta());
    assertEquals("king", completionGap.getThinkingDelta());
    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  @Test
  public void testAssistantErrorStopsAfterRetryLimit() {
    RecordingContext context = new RecordingContext();
    context.modelRetryConfig =
        ModelRetryConfig.builder()
            .maxRetries(1)
            .baseDelay(Duration.ZERO)
            .maxDelay(Duration.ZERO)
            .multiplier(2D)
            .build();
    context.provider.enqueue(
        handler -> handler.onError(new RuntimeException("first"), noopHandle()));
    context.provider.enqueue(
        handler -> handler.onError(new RuntimeException("second"), noopHandle()));

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));

    assertEquals(
        List.of(
            SessionEventType.set_agent_info,
            SessionEventType.set_model_info,
            SessionEventType.assistant_start,
            SessionEventType.assistant_error,
            SessionEventType.assistant_start,
            SessionEventType.assistant_error),
        context.eventTypes());
    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  @Test
  public void testToolLoopStartsNextAssistant() {
    RecordingContext context = new RecordingContext();
    ToolCall toolCall = new ToolCall();
    toolCall.setToolCallId("call_1");
    toolCall.setToolName("bash");
    toolCall.setArguments("{}");
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder()
                    .toolCalls(List.of(toolCall))
                    .metadata(new AssistantMetadata())
                    .build(),
                noopHandle()));
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder().text("final").metadata(new AssistantMetadata()).build(),
                noopHandle()));
    context.toolRegistry.registerTool(
        "bash",
        ToolInfo.builder()
            .name("bash")
            .description("bash")
            .inputSchema(ToolParamsSchema.builder().build())
            .build(),
        new Tool() {
          @Override
          public ToolExecutionHandle asyncExecute(
              ToolCallRequest request, ToolExecutionHandler handler) {
            ToolContentDelta delta = new ToolContentDelta();
            delta.setType(ToolContentType.text);
            delta.setText("ok");
            IndexedToolContentDelta indexed = new IndexedToolContentDelta();
            indexed.setIndex(0);
            indexed.setContentDelta(delta);
            handler.onPartial(List.of(indexed));

            ToolContent content = new ToolContent();
            content.setType(ToolContentType.text);
            content.setText("ok");
            handler.onComplete(List.of(content));
            return noopToolHandle();
          }
        });

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));

    assertEquals(
        List.of(
            SessionEventType.set_agent_info,
            SessionEventType.set_model_info,
            SessionEventType.assistant_start,
            SessionEventType.assistant_delta,
            SessionEventType.assistant_end,
            SessionEventType.tool_start,
            SessionEventType.tool_delta,
            SessionEventType.tool_end,
            SessionEventType.assistant_start,
            SessionEventType.assistant_delta,
            SessionEventType.assistant_end),
        context.eventTypes());
    AssistantStartPayload secondStart = (AssistantStartPayload) context.events.get(8).getPayload();
    assertEquals(List.of(), secondStart.getUserMessages());
    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  @Test
  public void testSecondUserRequestUsesProjectedConversationContext() {
    RecordingContext context = new RecordingContext();
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder()
                    .text("first answer")
                    .metadata(new AssistantMetadata())
                    .build(),
                noopHandle()));
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder()
                    .text("second answer")
                    .metadata(new AssistantMetadata())
                    .build(),
                noopHandle()));

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("first question"));
    agent.submit(UserRequest.userRequest("second question"));

    assertEquals(2, context.provider.receivedMessages.size());
    List<AgentMessage> firstCallMessages = context.provider.receivedMessages.get(0);
    assertEquals(2, firstCallMessages.size());
    AgentSystemMessage firstSystem =
        assertInstanceOf(AgentSystemMessage.class, firstCallMessages.get(0));
    AgentUserMessage firstUser = assertInstanceOf(AgentUserMessage.class, firstCallMessages.get(1));
    assertEquals("sys", firstSystem.text());
    assertEquals("first question", firstUser.text());

    List<AgentMessage> secondCallMessages = context.provider.receivedMessages.get(1);
    assertEquals(4, secondCallMessages.size());
    AgentSystemMessage secondSystem =
        assertInstanceOf(AgentSystemMessage.class, secondCallMessages.get(0));
    AgentUserMessage replayedUser =
        assertInstanceOf(AgentUserMessage.class, secondCallMessages.get(1));
    AgentAssistantMessage replayedAssistant =
        assertInstanceOf(AgentAssistantMessage.class, secondCallMessages.get(2));
    AgentUserMessage secondUser =
        assertInstanceOf(AgentUserMessage.class, secondCallMessages.get(3));
    assertEquals("sys", secondSystem.text());
    assertEquals("first question", replayedUser.text());
    assertEquals("first answer", replayedAssistant.text());
    assertEquals("second question", secondUser.text());
    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  /** 校验重新装载同一 session 后，下一次调用会重放已持久化的完整上下文。 */
  @Test
  public void testReloadedAgentReplaysPersistedConversation() {
    RecordingContext context = new RecordingContext();
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder()
                    .text("first answer")
                    .metadata(new AssistantMetadata())
                    .build(),
                noopHandle()));
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder()
                    .text("second answer")
                    .metadata(new AssistantMetadata())
                    .build(),
                noopHandle()));

    Agent firstAgent = context.newAgent();
    firstAgent.submit(UserRequest.userRequest("first question"));

    Agent reloadedAgent = context.newAgent();
    reloadedAgent.submit(UserRequest.userRequest("second question"));

    assertEquals(2, context.provider.receivedMessages.size());
    List<AgentMessage> reloadedMessages = context.provider.receivedMessages.get(1);
    assertEquals(4, reloadedMessages.size());
    assertEquals("sys", assertInstanceOf(AgentSystemMessage.class, reloadedMessages.get(0)).text());
    assertEquals("first question", assertInstanceOf(AgentUserMessage.class, reloadedMessages.get(1)).text());
    assertEquals(
        "first answer",
        assertInstanceOf(AgentAssistantMessage.class, reloadedMessages.get(2)).text());
    assertEquals("second question", assertInstanceOf(AgentUserMessage.class, reloadedMessages.get(3)).text());
    assertEquals(AgentStatus.idle, reloadedAgent.getStatus());
  }

  /** 校验 provider 未返回取消 handle 时，当前 attempt 会以 assistant_error 闭合。 */
  @Test
  public void testNullAssistantHandleClosesAttemptWithError() {
    RecordingContext context = new RecordingContext();
    context.modelRetryConfig =
        ModelRetryConfig.builder()
            .maxRetries(0)
            .baseDelay(Duration.ZERO)
            .maxDelay(Duration.ZERO)
            .multiplier(2D)
            .build();
    context.provider.nextHandle(null);
    context.provider.enqueue(handler -> {});

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));

    assertEquals(
        List.of(
            SessionEventType.set_agent_info,
            SessionEventType.set_model_info,
            SessionEventType.assistant_start,
            SessionEventType.assistant_error),
        context.eventTypes());
    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  /** 校验同一 attempt 的迟到 error 不会重复调度 retry。 */
  @Test
  public void testLateAssistantErrorDoesNotRescheduleRetry() {
    RecordingContext context = new RecordingContext();
    context.modelRetryConfig =
        ModelRetryConfig.builder()
            .maxRetries(2)
            .baseDelay(Duration.ofSeconds(1))
            .maxDelay(Duration.ofSeconds(1))
            .multiplier(2D)
            .build();
    List<Runnable> scheduledTasks = new ArrayList<>();
    context.scheduler =
        (delay, task) -> {
          scheduledTasks.add(task);
          return () -> scheduledTasks.remove(task);
        };
    context.provider.enqueue(handler -> {});
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder().text("done").metadata(new AssistantMetadata()).build(),
                noopHandle()));

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));
    AssistantResponseHandler firstHandler = context.provider.lastHandler;

    firstHandler.onError(new RuntimeException("first"), noopHandle());
    firstHandler.onError(new RuntimeException("late"), noopHandle());

    assertEquals(1, scheduledTasks.size());
    assertEquals(AgentStatus.busy, agent.getStatus());
    assertEquals(
        1,
        context.events.stream()
            .filter(event -> event.getEventType() == SessionEventType.assistant_error)
            .count());

    scheduledTasks.get(0).run();

    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  /** 校验 provider 返回缺失 tool call identity 时，当前 attempt 以 assistant_error 闭合。 */
  @Test
  public void testMalformedToolCallClosesAssistantWithError() {
    RecordingContext context = new RecordingContext();
    context.modelRetryConfig =
        ModelRetryConfig.builder()
            .maxRetries(0)
            .baseDelay(Duration.ZERO)
            .maxDelay(Duration.ZERO)
            .multiplier(2D)
            .build();
    ToolCall toolCall = new ToolCall();
    toolCall.setToolName("bash");
    toolCall.setArguments("{}");
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder()
                    .toolCalls(List.of(toolCall))
                    .metadata(new AssistantMetadata())
                    .build(),
                noopHandle()));

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));

    assertEquals(
        List.of(
            SessionEventType.set_agent_info,
            SessionEventType.set_model_info,
            SessionEventType.assistant_start,
            SessionEventType.assistant_error),
        context.eventTypes());
    AssistantErrorPayload errorPayload = (AssistantErrorPayload) context.events.get(3).getPayload();
    assertEquals("assistant tool call id must not be blank: index=0", errorPayload.getMessage());
    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  /** 校验非当前 session 的 branch 不会取消或污染当前 Agent。 */
  @Test
  public void testSwitchBranchRejectsForeignSession() {
    RecordingContext context = new RecordingContext();
    Agent agent = context.newAgent();
    Branch foreignBranch = Branch.newBranch("se_foreign", SessionEvent.ROOT_EVENT_ID);

    assertThrows(IllegalArgumentException.class, () -> agent.switchBranch(foreignBranch, List.of()));
    SessionEvent foreignEvent =
        SessionEvent.newEvent(
            "se_foreign",
            SessionEventType.assistant_start,
            SessionEvent.ROOT_EVENT_ID,
            new AssistantStartPayload());
    assertThrows(
        IllegalArgumentException.class,
        () -> agent.switchBranch(agent.getBranch(), List.of(foreignEvent)));

    assertEquals(context.session.getSessionId(), agent.getBranch().sessionId());
    assertEquals(List.of(), context.eventTypes());
    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  /** 校验 abort 会取消等待中的 retry，并忽略已入队的迟到 retry callback。 */
  @Test
  public void testAbortDuringRetryDelayCancelsRetryAndIgnoresLateCallback() {
    RecordingContext context = new RecordingContext();
    context.modelRetryConfig =
        ModelRetryConfig.builder()
            .maxRetries(1)
            .baseDelay(Duration.ofSeconds(1))
            .maxDelay(Duration.ofSeconds(1))
            .multiplier(2D)
            .build();
    AtomicReference<Runnable> retryTask = new AtomicReference<>();
    AtomicBoolean retryCancelled = new AtomicBoolean();
    context.scheduler =
        (delay, task) -> {
          retryTask.set(task);
          return () -> retryCancelled.set(true);
        };
    context.provider.enqueue(handler -> handler.onError(new RuntimeException("retry"), noopHandle()));

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));
    agent.abort("stop");

    assertTrue(retryCancelled.get());
    assertEquals(AgentStatus.idle, agent.getStatus());
    List<SessionEventType> eventTypesAfterAbort = context.eventTypes();
    assertEquals(SessionEventType.abort, eventTypesAfterAbort.get(eventTypesAfterAbort.size() - 1));

    retryTask.get().run();

    assertEquals(eventTypesAfterAbort, context.eventTypes());
    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  /** 校验 streaming delta 只使投影缓存变 dirty，不会逐 event 反复重放整个 branch。 */
  @Test
  public void testProjectionRefreshesOnDemandAfterStreamingDeltas() {
    RecordingContext context = new RecordingContext();
    CountingProjector projector = new CountingProjector();
    context.projector = projector;
    context.provider.enqueue(
        handler -> {
          AssistantResponseHandle handle = noopHandle();
          handler.onTextDelta("a", handle);
          handler.onTextDelta("b", handle);
          handler.onComplete(
              AssistantResponse.builder().text("ab").metadata(new AssistantMetadata()).build(),
              handle);
        });

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));

    assertEquals(6, context.events.size());
    assertTrue(projector.runtimeProjectionCount < context.events.size());

    int projectionCountBeforeRead = projector.runtimeProjectionCount;
    assertEquals(3, agent.getProjectedMessages().size());
    assertEquals(projectionCountBeforeRead + 1, projector.runtimeProjectionCount);
  }

  /** 校验正常完成但未发送 delta 的 tool result 会以 gap 写入 tool_delta。 */
  @Test
  public void testToolCompleteWritesMissingContentGap() {
    RecordingContext context = new RecordingContext();
    ToolCall toolCall = new ToolCall();
    toolCall.setToolCallId("call_1");
    toolCall.setToolName("bash");
    toolCall.setArguments("{}");
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder()
                    .toolCalls(List.of(toolCall))
                    .metadata(new AssistantMetadata())
                    .build(),
                noopHandle()));
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder().text("final").metadata(new AssistantMetadata()).build(),
                noopHandle()));
    context.toolRegistry.registerTool(
        "bash",
        ToolInfo.builder()
            .name("bash")
            .description("bash")
            .inputSchema(ToolParamsSchema.builder().build())
            .build(),
        (request, handler) -> {
          ToolContent content = new ToolContent();
          content.setType(ToolContentType.text);
          content.setText("result");
          handler.onComplete(List.of(content));
          return noopToolHandle();
        });

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));

    assertEquals(SessionEventType.tool_delta, context.events.get(6).getEventType());
    ToolDeltaPayload toolDeltaPayload = (ToolDeltaPayload) context.events.get(6).getPayload();
    assertEquals("result", toolDeltaPayload.getContentDeltas().get(0).getContentDelta().getText());
    assertEquals(SessionEventType.tool_end, context.events.get(7).getEventType());
    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  /** 校验 tool callback error 会关闭指定 tool，并继续后续 assistant attempt。 */
  @Test
  public void testToolCallbackErrorClosesToolAndContinuesAssistant() {
    RecordingContext context = new RecordingContext();
    ToolCall toolCall = new ToolCall();
    toolCall.setToolCallId("call_1");
    toolCall.setToolName("bash");
    toolCall.setArguments("{}");
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder()
                    .toolCalls(List.of(toolCall))
                    .metadata(new AssistantMetadata())
                    .build(),
                noopHandle()));
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder().text("final").metadata(new AssistantMetadata()).build(),
                noopHandle()));
    context.toolRegistry.registerTool(
        "bash",
        ToolInfo.builder()
            .name("bash")
            .description("bash")
            .inputSchema(ToolParamsSchema.builder().build())
            .build(),
        (request, handler) -> {
          handler.onError(new IllegalStateException("tool failed"));
          return noopToolHandle();
        });

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));

    assertEquals(SessionEventType.tool_error, context.events.get(6).getEventType());
    ToolErrorPayload errorPayload = (ToolErrorPayload) context.events.get(6).getPayload();
    assertEquals("tool failed", errorPayload.getMessage());
    assertEquals(SessionEventType.assistant_start, context.events.get(7).getEventType());
    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  /** 校验 unknown tool 仍写完整 tool 生命周期，并将稳定错误上下文回流给 assistant。 */
  @Test
  public void testToolNotFoundWritesErrorAndContinuesAssistant() {
    RecordingContext context = new RecordingContext();
    ToolCall toolCall = new ToolCall();
    toolCall.setToolCallId("call_1");
    toolCall.setToolName("missing");
    toolCall.setArguments("{}");
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder()
                    .toolCalls(List.of(toolCall))
                    .metadata(new AssistantMetadata())
                    .build(),
                noopHandle()));
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder().text("final").metadata(new AssistantMetadata()).build(),
                noopHandle()));

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));

    assertEquals(SessionEventType.tool_start, context.events.get(5).getEventType());
    assertEquals(SessionEventType.tool_error, context.events.get(6).getEventType());
    ToolErrorPayload errorPayload = (ToolErrorPayload) context.events.get(6).getPayload();
    assertEquals("tool not found: missing. Available tools: none.", errorPayload.getMessage());
    assertEquals(SessionEventType.assistant_start, context.events.get(7).getEventType());
    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  /** 校验公开运行时配置与视图访问器会通过同一 branch projection 返回当前事实。 */
  @Test
  public void testPublicConfigurationAndProjectionAccessors() {
    RecordingContext context = new RecordingContext();
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder().text("done").metadata(new AssistantMetadata()).build(),
                noopHandle()));
    Agent agent = context.newAgent();

    assertEquals("assistant", agent.getAgentName());
    assertEquals(List.of(), agent.getBranchEvents());
    assertEquals(List.of(), agent.projection().messages());
    assertThrows(IllegalArgumentException.class, () -> agent.setAgentName(" "));
    assertThrows(IllegalArgumentException.class, () -> agent.submit(null));
    assertThrows(IllegalArgumentException.class, () -> agent.switchBranch(null, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> agent.switchBranch(agent.getBranch(), null));

    agent.setAgentName("renamed");
    agent.setModelSelection("custom", "model", "high");
    agent.submit(UserRequest.userRequest("hello"));

    assertEquals("renamed", agent.getCurrentAgentInfo().getAgentName());
    assertEquals("custom", agent.getCurrentModelInfo().getProvider());
    assertTrue(!agent.getBranchEvents().isEmpty());
    assertEquals(3, agent.projection().messages().size());
  }

  @Test
  public void testInvalidToolArgumentsFailBeforeToolExecution() {
    RecordingContext context = new RecordingContext();
    ToolCall toolCall = new ToolCall();
    toolCall.setToolCallId("call_1");
    toolCall.setToolName("bash");
    toolCall.setArguments("{}");
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder()
                    .toolCalls(List.of(toolCall))
                    .metadata(new AssistantMetadata())
                    .build(),
                noopHandle()));
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder().text("final").metadata(new AssistantMetadata()).build(),
                noopHandle()));
    AtomicInteger toolInvocations = new AtomicInteger();
    context.toolRegistry.registerTool(
        "bash",
        ToolInfo.builder()
            .name("bash")
            .description("bash")
            .inputSchema(
                ToolParamsSchema.builder()
                    .properties(Map.of("text", ToolStringSchema.builder().build()))
                    .required(List.of("text"))
                    .additionalProperties(false)
                    .build())
            .build(),
        (request, handler) -> {
          toolInvocations.incrementAndGet();
          return noopToolHandle();
        });

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));

    assertEquals(0, toolInvocations.get());
    assertEquals(
        List.of(
            SessionEventType.set_agent_info,
            SessionEventType.set_model_info,
            SessionEventType.assistant_start,
            SessionEventType.assistant_delta,
            SessionEventType.assistant_end,
            SessionEventType.tool_start,
            SessionEventType.tool_error,
            SessionEventType.assistant_start,
            SessionEventType.assistant_delta,
            SessionEventType.assistant_end),
        context.eventTypes());
    ToolErrorPayload errorPayload = (ToolErrorPayload) context.events.get(6).getPayload();
    assertEquals(
        "argumentsJson does not match inputSchema: $.text is required", errorPayload.getMessage());
    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  @Test
  public void testSwitchBranchCancelsActiveAssistantAndIgnoresLateCallback() {
    RecordingContext context = new RecordingContext();
    CancellableAssistantHandle handle = new CancellableAssistantHandle();
    context.provider.nextHandle(handle);
    context.provider.enqueue(handler -> {});

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));

    assertEquals(AgentStatus.busy, agent.getStatus());
    agent.switchBranch(
        Branch.newBranch(agent.getSession().getSessionId(), SessionEvent.ROOT_EVENT_ID), List.of());

    assertTrue(handle.isCancelled());
    assertEquals(AgentStatus.idle, agent.getStatus());
    assertNull(agent.getCurrentAgentInfo());
    assertNull(agent.getCurrentModelInfo());
    List<SessionEventType> eventTypesAfterSwitch = context.eventTypes();
    context.provider.lastHandler.onComplete(
        AssistantResponse.builder().text("late").metadata(new AssistantMetadata()).build(), handle);

    assertEquals(eventTypesAfterSwitch, context.eventTypes());
  }

  @Test
  public void testAbortCancelsActiveToolAndIgnoresLateCallback() {
    RecordingContext context = new RecordingContext();
    ToolCall toolCall = new ToolCall();
    toolCall.setToolCallId("call_1");
    toolCall.setToolName("bash");
    toolCall.setArguments("{}");
    context.provider.enqueue(
        handler ->
            handler.onComplete(
                AssistantResponse.builder()
                    .toolCalls(List.of(toolCall))
                    .metadata(new AssistantMetadata())
                    .build(),
                noopHandle()));
    AtomicReference<ToolExecutionHandler> capturedToolHandler = new AtomicReference<>();
    CancellableToolHandle toolHandle = new CancellableToolHandle();
    context.toolRegistry.registerTool(
        "bash",
        ToolInfo.builder()
            .name("bash")
            .description("bash")
            .inputSchema(ToolParamsSchema.builder().build())
            .build(),
        (request, handler) -> {
          capturedToolHandler.set(handler);
          return toolHandle;
        });

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));
    agent.abort("stop");

    assertTrue(toolHandle.isCancelled());
    assertEquals(AgentStatus.idle, agent.getStatus());
    assertEquals(
        List.of(
            SessionEventType.set_agent_info,
            SessionEventType.set_model_info,
            SessionEventType.assistant_start,
            SessionEventType.assistant_delta,
            SessionEventType.assistant_end,
            SessionEventType.tool_start,
            SessionEventType.abort),
        context.eventTypes());
    List<SessionEventType> eventTypesAfterAbort = context.eventTypes();
    ToolContent content = new ToolContent();
    content.setType(ToolContentType.text);
    content.setText("late");
    capturedToolHandler.get().onComplete(List.of(content));

    assertEquals(eventTypesAfterAbort, context.eventTypes());
  }

  @Test
  public void testRuntimeConfigFailureReleasesLoop() {
    RecordingContext context = new RecordingContext();
    context.agentRegistry.fail = true;

    Agent agent = context.newAgent();
    agent.submit(UserRequest.userRequest("hello"));

    assertEquals(List.of(), context.eventTypes());
    assertTrue(context.failure.get() instanceof IllegalStateException);
    assertEquals(AgentStatus.idle, agent.getStatus());
  }

  private static AssistantResponseHandle noopHandle() {
    return new AssistantResponseHandle() {
      @Override
      public void cancel() {}

      @Override
      public boolean isCancelled() {
        return false;
      }
    };
  }

  private static ToolExecutionHandle noopToolHandle() {
    return new NoopToolExecutionHandle();
  }

  private static final class RecordingContext {
    private final List<SessionEvent> events = new ArrayList<>();
    private final InMemoryUserRequestQueue userRequestQueue = new InMemoryUserRequestQueue();
    private final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
    private final StubProvider provider = new StubProvider();
    private final StubAgentRegistry agentRegistry = new StubAgentRegistry();
    private final InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
    private final InMemorySessionEventRepository sessionEventRepository =
        new InMemorySessionEventRepository();
    private final SessionManager sessionManager =
        new SessionManagerImpl(sessionRepository, sessionEventRepository);
    private final Session session = Session.newSession();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private SessionEventMessageProjector projector = new DefaultSessionEventMessageProjector();
    private AgentScheduler scheduler =
        (delay, task) -> {
          task.run();
          return () -> {};
        };
    private ModelRetryConfig modelRetryConfig =
        ModelRetryConfig.builder()
            .maxRetries(1)
            .baseDelay(Duration.ZERO)
            .maxDelay(Duration.ZERO)
            .multiplier(2D)
            .build();

    private RecordingContext() {
      sessionRepository.save(session);
    }

    private Agent newAgent() {
      AgentFactory agentFactory = new AgentFactory();
      ToolCallExecutor toolCallExecutor =
          new ToolCallExecutor(new DirectExecutorService(), scheduler);
      AgentRuntimeConfigResolver resolver = new AgentRuntimeConfigResolver(
          agentRegistry,
          new StubModelRegistry(),
          new StubProviderRegistry(),
          providerInfo -> provider,
          toolRegistry);
      AgentFactory.Dependencies deps = new AgentFactory.Dependencies(
          toolRegistry,
          toolCallExecutor,
          sessionManager,
          projector,
          new AgentEventHandler() {
            @Override
            public void onEvent(SessionEvent event) {
              events.add(event);
            }

            @Override
            public void onFailure(Throwable error) {
              failure.set(error);
            }
          },
          resolver);
      return agentFactory.load(
          session.getSessionId(),
          "assistant",
          "openai",
          "gpt-test",
          "high",
          userRequestQueue,
          scheduler,
          modelRetryConfig,
          deps);
    }

    private List<SessionEventType> eventTypes() {
      return events.stream().map(SessionEvent::getEventType).toList();
    }
  }

  private static final class CountingProjector implements SessionEventMessageProjector {
    private final DefaultSessionEventMessageProjector delegate =
        new DefaultSessionEventMessageProjector();
    private int runtimeProjectionCount;

    @Override
    public SessionEventProjection project(List<SessionEvent> branchEvents) {
      return delegate.project(branchEvents);
    }

    @Override
    public SessionEventProjection projectForRuntime(List<SessionEvent> branchEvents) {
      runtimeProjectionCount++;
      return delegate.projectForRuntime(branchEvents);
    }
  }

  private static final class StubProvider implements Provider {
    private final Queue<Consumer<AssistantResponseHandler>> scripts = new ArrayDeque<>();
    private final List<List<AgentMessage>> receivedMessages = new ArrayList<>();
    private AssistantResponseHandle nextHandle = noopHandle();
    private AssistantResponseHandler lastHandler;

    private void enqueue(Consumer<AssistantResponseHandler> script) {
      scripts.offer(script);
    }

    private void nextHandle(AssistantResponseHandle handle) {
      nextHandle = handle;
    }

    @Override
    public ProviderType getProviderType() {
      return ProviderType.openai;
    }

    @Override
    public AssistantResponseHandle asyncChat(
        List<AgentMessage> messages,
        ModelInfo modelInfo,
        Variant variant,
        List<ToolInfo> toolInfos,
        AssistantResponseHandler handler) {
      receivedMessages.add(List.copyOf(messages));
      lastHandler = handler;
      Consumer<AssistantResponseHandler> script = scripts.poll();
      if (script != null) {
        script.accept(handler);
      }
      AssistantResponseHandle handle = nextHandle;
      nextHandle = noopHandle();
      return handle;
    }
  }

  private static final class CancellableAssistantHandle implements AssistantResponseHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public void cancel() {
      cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }
  }

  private static final class CancellableToolHandle implements ToolExecutionHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public void cancel() {
      cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }
  }

  private static final class InMemoryUserRequestQueue implements UserRequestQueue {
    private final List<UserRequest> requests = new ArrayList<>();

    @Override
    public void submit(UserRequest userRequest) {
      requests.add(userRequest);
    }

    @Override
    public List<UserRequest> pollAll() {
      List<UserRequest> copied = List.copyOf(requests);
      requests.clear();
      return copied;
    }

    @Override
    public boolean isEmpty() {
      return requests.isEmpty();
    }
  }

  private static final class DirectExecutorService extends AbstractExecutorService {

    private boolean shutdown;

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }

    @Override
    public void execute(Runnable command) {
      command.run();
    }
  }

  private static final class StubAgentRegistry implements AgentRegistry {
    private boolean fail;

    @Override
    public void registerAgent(AgentInfo agentInfo) {}

    @Override
    public AgentInfo getAgent(String name) {
      if (fail) {
        throw new IllegalStateException("agent registry unavailable");
      }
      return AgentInfo.builder()
          .name(name)
          .systemPrompt("sys")
          .defaultProvider("openai")
          .defaultModel("gpt-test")
          .defaultVariant("high")
          .tools(List.of("bash"))
          .build();
    }
  }

  private static final class StubModelRegistry implements ModelRegistry {
    @Override
    public void registerModel(ModelInfo modelInfo) {}

    @Override
    public ModelInfo getModel(String provider, String model) {
      return ModelInfo.builder()
          .provider(provider)
          .name(model)
          .defaultVariant("high")
          .variants(List.of(Variant.builder().name("high").build()))
          .build();
    }
  }

  private static final class StubProviderRegistry implements ProviderRegistry {
    @Override
    public void registerProvider(String provider, ProviderInfo providerInfo) {}

    @Override
    public ProviderInfo getProviderInfo(String provider) {
      return ProviderInfo.builder()
          .providerType(ProviderType.openai)
          .baseUrl("http://localhost")
          .apiKey("test")
          .timeout(Duration.ofSeconds(30))
          .streamIdleTimeout(Duration.ofSeconds(30))
          .build();
    }
  }

  private static final class InMemorySessionRepository implements SessionRepository {
    private final Map<String, Session> sessionById = new HashMap<>();

    @Override
    public Session getSession(String sessionId) {
      return sessionById.get(sessionId);
    }

    @Override
    public boolean compareAndSetCurrentHeadEventId(
        String sessionId, String expectedHeadEventId, String newHeadEventId) {
      Session session = sessionById.get(sessionId);
      if (session == null || !expectedHeadEventId.equals(session.getCurrentHeadEventId())) {
        return false;
      }
      session.setCurrentHeadEventId(newHeadEventId);
      return true;
    }

    private void save(Session session) {
      sessionById.put(session.getSessionId(), session);
    }
  }

  private static final class InMemorySessionEventRepository implements SessionEventRepository {
    private final List<SessionEvent> events = new ArrayList<>();

    @Override
    public List<SessionEvent> listBySessionId(String sessionId) {
      return events.stream().filter(event -> sessionId.equals(event.getSessionId())).toList();
    }

    @Override
    public void appendEvent(SessionEvent event) {
      events.add(event);
    }
  }
}
