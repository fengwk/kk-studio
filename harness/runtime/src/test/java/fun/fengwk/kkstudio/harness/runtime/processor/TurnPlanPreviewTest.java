package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@link TurnPlanPreview} 的只读候选历史契约。
 *
 * <p>测试意图：请求预览必须复用 ThreadProcessor 的同一纯 {@code TurnPlanBuilder}——源头 path 只追加 normalization 与
 * TURN_START(INPUT)/Message，SET_* 只冻结进 TURN_START.settings 快照（不产生模型可见消息），且命令 batch 以调用方给定的
 * nextCommandSequence 起点获得合法 sequence。任何「另起一套 turn 历史组装」的实现都会在这里结构上暴露。
 */
class TurnPlanPreviewTest {

  private static final UUID IDEMPOTENCY_KEY = TestIds.id(77_001L);
  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

  /**
   * 测试意图：SET_EXECUTION/SET_AGENT 前缀只更新快照，末尾 USER_MESSAGE 生成唯一模型可见消息，且候选 path 直接从 source head 续接。
   */
  @Test
  void inputCandidatePathMergesSettingsSnapshotAndUserMessage() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath source = path(store, baseline.threadId());
    ExecutionSelection execution2 =
        new ExecutionSelection("builtin", "{\"modelName\":\"model-2\",\"variant\":\"v2\"}");
    NewThreadCommand setExecution = command(new SetExecutionCommandPayload(execution2));
    NewThreadCommand setAgent = command(new SetAgentCommandPayload("agent-2"));
    NewThreadCommand userMessage = command(userMessage("hello preview"));

    EntryPath candidate =
        TurnPlanPreview.inputCandidatePath(
            baseline.threadId(),
            source,
            1L,
            List.of(setExecution, setAgent, userMessage),
            NOW,
            false,
            null);

    // source 只有一个 ROOT；候选 = ROOT + TURN_START(INPUT) + 一条 USER 消息（SET_* 不产生消息）。
    assertEquals(3, candidate.entries().size());
    assertTrue(candidate.entries().get(0).payload().type().isRoot());
    TurnStartPayload start = (TurnStartPayload) candidate.entries().get(1).payload();
    assertEquals(TurnStartReason.INPUT, start.reason());
    assertEquals(baseline.threadId(), start.ownerThreadId());
    assertEquals(new BranchSettings("agent-2", execution2, null), start.settings());
    MessagePayload message = (MessagePayload) candidate.entries().get(2).payload();
    assertEquals(AgentMessageRole.USER, message.message().role());
    assertEquals(userMessage("hello preview").message(), message.message());
    // 快照与消息同属本 turn：baseSettings 已经是合并后的选择，parent 链从 source head 连续续接。
    assertEquals(start.settings(), candidate.baseSettings());
    assertEquals(baseline.rootEntryId(), candidate.entries().get(1).parentEntryId());
    assertEquals(candidate.entries().get(1).id(), candidate.head().parentEntryId());
    assertEquals(baseline.rootEntryId(), candidate.root().id());
  }

  /** 测试意图：candidate Entry 只是内存对象，绝不写入 Store（source path 上的 Entry/Thread 保持不变）。 */
  @Test
  void inputCandidatePathNeverWritesStore() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath source = path(store, baseline.threadId());

    EntryPath candidate =
        TurnPlanPreview.inputCandidatePath(
            baseline.threadId(),
            source,
            1L,
            List.of(command(userMessage("do not persist"))),
            NOW,
            false,
            null);

    assertEquals(1, source.entries().size());
    assertEquals(1, path(store, baseline.threadId()).entries().size());
    Entry head = candidate.head();
    assertThrows(RuntimeException.class, () -> entry(store, head.id()));
  }

  /** 测试意图：批次缺少末尾 user-like 输入时与正式规划一致地确定性拒绝，绝不构造半截候选历史。 */
  @Test
  void inputCandidatePathRejectsBatchWithoutUserInput() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath source = path(store, baseline.threadId());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            TurnPlanPreview.inputCandidatePath(
                baseline.threadId(),
                source,
                1L,
                List.of(command(new SetAgentCommandPayload("agent-2"))),
                NOW,
                false,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TurnPlanPreview.inputCandidatePath(
                baseline.threadId(), source, 1L, List.of(), NOW, false, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TurnPlanPreview.inputCandidatePath(
                baseline.threadId(),
                source,
                0L,
                List.of(command(userMessage("x"))),
                NOW,
                false,
                null));
  }

  /** 测试意图：临时 ThreadCommand 的合法 seq/hash 由共享命令契约保证（预览不放松任何一条），且 seq 起点由调用方给定。 */
  @Test
  void inputCandidatePathRequiresLegalSequenceAndHash() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath source = path(store, baseline.threadId());
    NewThreadCommand userMessage = command(userMessage("x"));

    assertThrows(
        IllegalArgumentException.class,
        () -> new NewThreadCommand(userMessage.payload(), IDEMPOTENCY_KEY, "not-a-hash"));
    // 起点不是硬编码 1：任意正 sequence 起点都能构造出合法候选历史。
    assertInstanceOf(
        EntryPath.class,
        TurnPlanPreview.inputCandidatePath(
            baseline.threadId(), source, 5L, List.of(userMessage), NOW, false, null));
  }

  @Test
  void childUsesRootEnvironmentAndCannotSetItsOwn() {
    // 子线程冻结当前根环境；只有执行根可在预览中设置环境。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath source = path(store, baseline.threadId());
    NewThreadCommand message = command(userMessage("hello"));
    NewThreadCommand setEnvironment =
        new NewThreadCommand(new SetEnvironmentCommandPayload("new-env"), IDEMPOTENCY_KEY);
    EntryPath child =
        TurnPlanPreview.inputCandidatePath(
            baseline.threadId(), source, 1L, List.of(message), NOW, true, "root-env");
    assertEquals("root-env", child.baseSettings().environmentName());
    assertNull(
        TurnPlanPreview.inputCandidatePath(
                baseline.threadId(), source, 1L, List.of(message), NOW, true, null)
            .baseSettings()
            .environmentName());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TurnPlanPreview.inputCandidatePath(
                baseline.threadId(),
                source,
                1L,
                List.of(setEnvironment, message),
                NOW,
                true,
                "root-env"));
    EntryPath root =
        TurnPlanPreview.inputCandidatePath(
            baseline.threadId(), source, 1L, List.of(setEnvironment, message), NOW, false, null);
    assertEquals("new-env", root.baseSettings().environmentName());
  }

  private static EntryPath path(InMemoryHarnessStore store, UUID threadId) {
    return store.transaction(
        tx -> tx.entries().loadPath(tx.threads().find(threadId).orElseThrow().headEntryId()));
  }

  private static Entry entry(InMemoryHarnessStore store, UUID entryId) {
    return store.transaction(tx -> tx.entries().find(entryId)).orElseThrow();
  }

  private static NewThreadCommand command(SetExecutionCommandPayload payload) {
    return new NewThreadCommand(payload, IDEMPOTENCY_KEY);
  }

  private static NewThreadCommand command(SetAgentCommandPayload payload) {
    return new NewThreadCommand(payload, IDEMPOTENCY_KEY);
  }

  private static NewThreadCommand command(UserMessageCommandPayload payload) {
    return new NewThreadCommand(payload, IDEMPOTENCY_KEY);
  }

  private static UserMessageCommandPayload userMessage(String text) {
    return new UserMessageCommandPayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))));
  }
}
