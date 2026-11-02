package fun.fengwk.kkstudio.harness.runtime.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverApplied;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverStopResult;
import fun.fengwk.kkstudio.harness.runtime.driver.TurnResolution;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnCapabilities;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.processor.AssistantHistoryAppender;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
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

/** {@link FakeAgentDriver} 的可配置中立投影与默认 no-op 边界。 */
class FakeAgentDriverTest {

  @Test
  void defaultsRemainInactiveAndAllowNullResolution() {
    FakeAgentDriver driver = new FakeAgentDriver();

    assertNull(driver.resolveTurn(null, null, null, null));
    assertEquals(DriverActivity.NONE, driver.readState(null, null, null).activity());
    assertTrue(driver.stop(null, null, null, null, null, id(1)).executionIds().isEmpty());
  }

  @Test
  void exposesConfiguredStateAppliedFactsAndStopResult() {
    TurnResolution resolution =
        new TurnResolution.Rejected(new AssistantError("FAILED", "provider failed"));
    DriverApplied applied =
        new DriverApplied(id(2), null, false, true, true, TurnEndOutcome.COMPLETED, List.of());
    DriverStopResult stop = new DriverStopResult(id(3), List.of(id(4)));
    FakeAgentDriver.FakeLockedDriverState state =
        new FakeAgentDriver.FakeLockedDriverState(DriverActivity.ACTIVE, id(5), null, null, id(4));

    FakeAgentDriver driver =
        new FakeAgentDriver(() -> resolution)
            .withState(state)
            .withApplied(applied)
            .withStopResult(stop);

    assertSame(resolution, driver.resolveTurn(null, null, null, null));
    assertSame(state, driver.lockState(null, null, null));
    assertSame(applied, driver.applyResponse(null, null, null, state, null));
    assertSame(stop, driver.stop(null, null, null, state, null, id(6)));
    assertEquals(DriverActivity.ACTIVE, state.activity());
    assertEquals(id(4), state.executionId());
    assertFalse(state.cancelsToolBatch());
    assertTrue(driver.allowsQueuedInputHarvest(null, null, null));

    driver.completeStop(null, null, null);
    assertEquals(1, driver.completeStopInvocations());

    driver.withAllowsQueuedInputHarvest(false);
    assertFalse(driver.allowsQueuedInputHarvest(null, null, null));

    FakeAgentDriver.FakeLockedDriverState cancellingState =
        new FakeAgentDriver.FakeLockedDriverState(
            DriverActivity.RESPONSE_PENDING, id(5), null, null, id(4), true);
    assertTrue(cancellingState.cancelsToolBatch());
  }

  @Test
  void outOfProcessToolBridgeFlowAndStopCancellation() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Baseline base = seedThreadBaseline(store);
    Instant now = Instant.ofEpochMilli(1_000L);
    AssistantHistoryAppender appender = new AssistantHistoryAppender();
    FakeAgentDriver driver = new FakeAgentDriver();

    ToolBinding binding =
        new ToolBinding(
            new AgentToolDefinition(
                new ToolDescriptor(
                    "bash",
                    "bash tool",
                    "bash",
                    new InputSchema("args", Map.of(), Set.of(), true),
                    ToolSideEffect.READ_ONLY,
                    Duration.ofSeconds(30)),
                ToolVisibility.SELECTABLE),
            new ContributorBinding("core", "bash", List.of()),
            EnvironmentSupport.NONE,
            null,
            null);

    store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(base.threadId()).orElseThrow();
          var rootPayload =
              (RootPayload) tx.entries().loadPath(base.rootEntryId()).root().payload();
          UUID turnStartId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      turnStartId,
                      base.sessionId(),
                      base.rootEntryId(),
                      new TurnStartPayload(
                          TurnStartReason.INPUT,
                          rootPayload.settings(),
                          base.threadId(),
                          null,
                          null,
                          null,
                          null,
                          new TurnCapabilities(List.of(binding), List.of())),
                      now));
          ThreadState openThread = thread.advanceHead(turnStartId, now);
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
                              AgentMessageRole.USER, List.of(new TextMessageContent("run cmd"))),
                          null,
                          null),
                      now));
          ThreadState userThread = openThread.advanceHead(userMsgId, now);
          tx.threads().update(userThread);
          EntryPath path = tx.entries().loadPath(userThread.headEntryId());

          ToolCall call = new ToolCall("call-1", "bash", "{\"cmd\":\"ls\"}");
          AgentMessage assistantMsg =
              new AgentMessage(
                  AgentMessageRole.ASSISTANT,
                  List.of(
                      new ToolCallMessageContent("call-1", "bash", "bash", "{\"cmd\":\"ls\"}")));
          AssistantMessageMetadata metadata =
              new AssistantMessageMetadata(
                  GenerationStopReason.COMPLETE,
                  new ModelUsage(10L, 20L, 0L, 0L, Map.of(), 0L, 30L));

          UUID toolId = tx.nextId();
          AssistantHistoryAppender.ToolSlot slot =
              new AssistantHistoryAppender.ToolSlot(
                  toolId, call, binding, ToolInvocationStatus.WAITING_REQUEST, null);

          // 1. 进程外追加 Assistant 消息与 WAITING_REQUEST 槽位
          AssistantHistoryAppender.Applied result =
              appender.append(tx, userThread, path, assistantMsg, metadata, List.of(slot), now);
          tx.threads().update(result.thread());

          // 2. 验证工具持久化状态为 WAITING_REQUEST
          ToolInvocation storedTool = tx.tools().find(toolId).orElseThrow();
          assertEquals(ToolInvocationStatus.WAITING_REQUEST, storedTool.status());

          // 3. 外部激活请求（activateRequest）转为 READY
          ToolInvocation activated = storedTool.activateRequest(now.plusMillis(100));
          assertEquals(ToolInvocationStatus.READY, activated.status());
          tx.tools().update(List.of(activated));

          // 4. 停止取消：cancel 将未收尾工具转为 CANCELLED
          ToolInvocation cancelled =
              tx.tools()
                  .find(toolId)
                  .orElseThrow()
                  .cancel(
                      new ToolInvocationError("STOPPED", "cancelled by user"), now.plusMillis(200));
          assertEquals(ToolInvocationStatus.CANCELLED, cancelled.status());
          tx.tools().update(List.of(cancelled));

          // 5. completeStop 回调
          driver.completeStop(tx, result.thread(), null);
          assertEquals(1, driver.completeStopInvocations());
          return null;
        });
  }
}
