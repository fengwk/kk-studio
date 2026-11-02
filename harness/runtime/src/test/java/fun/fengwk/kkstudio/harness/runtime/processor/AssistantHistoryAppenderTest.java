package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnCapabilities;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class AssistantHistoryAppenderTest {

  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private InMemoryHarnessStore store;
  private AssistantHistoryAppender appender;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    appender = new AssistantHistoryAppender();
  }

  private ToolBinding toolBinding(String name) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            name,
            "tool description",
            name,
            new InputSchema("args", Map.of(), Set.of(), true),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30));
    return new ToolBinding(
        new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE),
        new ContributorBinding("core", name, List.of()),
        EnvironmentSupport.NONE,
        null,
        null);
  }

  private static BranchSettings settings() {
    return new BranchSettings("assistant", new ExecutionSelection("fake", "{}"), null);
  }

  private AssistantMessageMetadata metadata() {
    return new AssistantMessageMetadata(
        GenerationStopReason.COMPLETE, new ModelUsage(10L, 20L, 0L, 0L, Map.of(), 0L, 30L));
  }

  @Test
  void appendsAssistantAndWaitingRequestSlotAtomically() {
    TurnPlanTestSupport.Baseline base = TurnPlanTestSupport.seedBaseline(store);
    ToolBinding binding = toolBinding("bash");

    store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(base.threadId()).orElseThrow();
          UUID turnStartId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      turnStartId,
                      base.sessionId(),
                      thread.headEntryId(),
                      new TurnStartPayload(
                          TurnStartReason.INPUT,
                          settings(),
                          base.threadId(),
                          null,
                          null,
                          null,
                          null,
                          new TurnCapabilities(List.of(binding), List.of())),
                      NOW));
          ThreadState openThread = thread.advanceHead(turnStartId, NOW);
          tx.threads().update(openThread);

          UUID userMsgId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      userMsgId,
                      base.sessionId(),
                      turnStartId,
                      new MessagePayload(
                          new AgentMessage(
                              AgentMessageRole.USER,
                              List.of(new TextMessageContent("list files please"))),
                          null,
                          null),
                      NOW));
          ThreadState userThread = openThread.advanceHead(userMsgId, NOW);
          tx.threads().update(userThread);

          EntryPath path = tx.entries().loadPath(userThread.headEntryId());
          ToolCall call = new ToolCall("call-1", "bash", "{\"cmd\":\"ls\"}");
          AgentMessage msg =
              new AgentMessage(
                  AgentMessageRole.ASSISTANT,
                  List.of(
                      new TextMessageContent("let me list files"),
                      new ToolCallMessageContent("call-1", "bash", "bash", "{\"cmd\":\"ls\"}")));
          UUID invocationId = tx.nextId();
          AssistantHistoryAppender.ToolSlot slot =
              new AssistantHistoryAppender.ToolSlot(
                  invocationId, call, binding, ToolInvocationStatus.WAITING_REQUEST, null);

          AssistantHistoryAppender.Applied applied =
              appender.append(tx, userThread, path, msg, metadata(), List.of(slot), NOW);

          assertEquals(applied.assistantEntryId(), applied.thread().headEntryId());
          assertEquals(1, applied.toolInvocations().size());
          ToolInvocation invocation = applied.toolInvocations().getFirst();
          assertEquals(invocationId, invocation.id());
          assertEquals(ToolInvocationStatus.WAITING_REQUEST, invocation.status());
          assertEquals(0, invocation.attempt());
          assertNull(invocation.approval());
          assertEquals("call-1", invocation.call().id());

          // 验证通过 store 查询也是最新的
          ToolInvocation stored = tx.tools().find(invocationId).orElseThrow();
          assertEquals(ToolInvocationStatus.WAITING_REQUEST, stored.status());
          Entry assistantEntry = tx.entries().find(applied.assistantEntryId()).orElseThrow();
          assertTrue(assistantEntry.payload() instanceof MessagePayload);
          return null;
        });
  }

  @Test
  void slotValidationFailures() {
    TurnPlanTestSupport.Baseline base = TurnPlanTestSupport.seedBaseline(store);
    ToolBinding binding = toolBinding("bash");

    store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(base.threadId()).orElseThrow();
          UUID turnStartId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      turnStartId,
                      base.sessionId(),
                      thread.headEntryId(),
                      new TurnStartPayload(
                          TurnStartReason.INPUT,
                          settings(),
                          base.threadId(),
                          null,
                          null,
                          null,
                          null,
                          new TurnCapabilities(List.of(binding), List.of())),
                      NOW));
          ThreadState openThread = thread.advanceHead(turnStartId, NOW);
          tx.threads().update(openThread);

          UUID userMsgId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      userMsgId,
                      base.sessionId(),
                      turnStartId,
                      new MessagePayload(
                          new AgentMessage(
                              AgentMessageRole.USER,
                              List.of(new TextMessageContent("list files please"))),
                          null,
                          null),
                      NOW));
          ThreadState userThread = openThread.advanceHead(userMsgId, NOW);
          tx.threads().update(userThread);
          EntryPath path = tx.entries().loadPath(userThread.headEntryId());

          ToolCall call = new ToolCall("call-1", "bash", "{\"cmd\":\"ls\"}");
          AgentMessage validMsg =
              new AgentMessage(
                  AgentMessageRole.ASSISTANT,
                  List.of(
                      new ToolCallMessageContent("call-1", "bash", "bash", "{\"cmd\":\"ls\"}")));

          // 1. ToolSlot 构造时非法状态（如 RUNNING）
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  new AssistantHistoryAppender.ToolSlot(
                      tx.nextId(), call, binding, ToolInvocationStatus.RUNNING, null));

          // 2. FAILED 槽位无 error
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  new AssistantHistoryAppender.ToolSlot(
                      tx.nextId(), call, binding, ToolInvocationStatus.FAILED, null));

          // 3. 非 FAILED 槽位带 error
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  new AssistantHistoryAppender.ToolSlot(
                      tx.nextId(),
                      call,
                      binding,
                      ToolInvocationStatus.WAITING_REQUEST,
                      new ToolInvocationError("E", "msg")));

          // 4. 槽位数与 tool calls 数量不一致
          assertThrows(
              IllegalArgumentException.class,
              () -> appender.append(tx, userThread, path, validMsg, metadata(), List.of(), NOW));

          // 5. 槽位 call 与 message tool call 不一致
          ToolCall mismatchCall = new ToolCall("call-2", "bash", "{\"cmd\":\"ls\"}");
          AssistantHistoryAppender.ToolSlot mismatchSlot =
              new AssistantHistoryAppender.ToolSlot(
                  tx.nextId(), mismatchCall, binding, ToolInvocationStatus.WAITING_REQUEST, null);
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  appender.append(
                      tx, userThread, path, validMsg, metadata(), List.of(mismatchSlot), NOW));

          // 6. binding 不在 turn capabilities 内部
          ToolBinding otherBinding = toolBinding("other");
          ToolCall otherCall = new ToolCall("call-1", "other", "{}");
          AgentMessage otherMsg =
              new AgentMessage(
                  AgentMessageRole.ASSISTANT,
                  List.of(new ToolCallMessageContent("call-1", "other", "other", "{}")));
          AssistantHistoryAppender.ToolSlot notFrozenSlot =
              new AssistantHistoryAppender.ToolSlot(
                  tx.nextId(), otherCall, otherBinding, ToolInvocationStatus.WAITING_REQUEST, null);
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  appender.append(
                      tx, userThread, path, otherMsg, metadata(), List.of(notFrozenSlot), NOW));

          // 7. 非 ASSISTANT 角色消息
          AgentMessage userMsg =
              new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello")));
          assertThrows(
              IllegalArgumentException.class,
              () -> appender.append(tx, userThread, path, userMsg, metadata(), List.of(), NOW));
          return null;
        });
  }
}
