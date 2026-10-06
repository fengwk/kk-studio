package fun.fengwk.kkstudio.harness.runtime.history;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryNormalization.StopClose;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * open Turn 前缀的两条判定：history cut suffix 的形状，以及该前缀能否按 STOPPED 关闭。
 *
 * <p>测试意图：Stop 只能复用 Turn 内既有的唯一 assistant 结果（ASSISTANT MESSAGE 需 tool 结果完整，ASSISTANT_ERROR /
 * ASSISTANT_ABORTED / COMPACTION 本身就是完整结果），绝不追加第二个 assistant 结果，也不伪造模型完成。
 */
class HistoryNormalizationTest {

  private static final UUID SESSION_ID = new UUID(0L, 1L);
  private static final UUID OWNER_THREAD_ID = new UUID(0L, 2L);
  private static final Instant NOW = Instant.ofEpochMilli(1_000);

  @Test
  void compactionResultPrefixReusesTheExistingResultInsteadOfAppendingAnAssistant() {
    // 已有 compaction 成功结果、尚未 TURN_END 的历史前缀：它已经是唯一 assistant 结果，可直接按 STOPPED 关闭。
    PathBuilder path = new PathBuilder();
    Entry root = path.root();
    Entry turn = path.compactionStart(root.id());
    path.append(turn.id(), new CompactionPayload("summary"));
    EntryPath entryPath = path.build();

    assertEquals(
        StopClose.REUSE_ASSISTANT_RESULT,
        HistoryNormalization.stopClose(entryPath, turnOf(entryPath)));

    // history cut suffix 只补 TURN_END，不写第二个 assistant 结果。
    List<Entry> suffix = HistoryNormalization.suffix(entryPath, path.ids(), NOW);
    assertEquals(1, suffix.size());
    assertEquals(
        TurnEndOutcome.CANCELLED, ((TurnEndPayload) suffix.getFirst().payload()).outcome());
  }

  @Test
  void errorAndAbortedResultPrefixesReuseTheExistingResult() {
    // ASSISTANT_ERROR / ASSISTANT_ABORTED 同样是该 Turn 唯一且完整的 assistant 结果。
    for (EntryPayload result :
        List.of(
            new AssistantErrorPayload(new AssistantError("MODEL_FAILED", "down"), null),
            new AssistantAbortedPayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("partial")))))) {
      PathBuilder path = new PathBuilder();
      Entry root = path.root();
      Entry turn = path.turnStart(TurnStartReason.INPUT, root.id());
      Entry user = path.append(turn.id(), new MessagePayload(AgentMessage.user("hi"), null, null));
      path.append(user.id(), result);
      EntryPath entryPath = path.build();

      assertEquals(
          StopClose.REUSE_ASSISTANT_RESULT,
          HistoryNormalization.stopClose(entryPath, turnOf(entryPath)));
    }
  }

  @Test
  void assistantMessageIsReusableOnlyWhenEveryToolCallHasItsResult() {
    PathBuilder path = new PathBuilder();
    Entry root = path.root();
    Entry turn = path.turnStart(TurnStartReason.INPUT, root.id());
    Entry user = path.append(turn.id(), new MessagePayload(AgentMessage.user("hi"), null, null));
    Entry assistant = path.append(user.id(), assistantWithToolCall());

    EntryPath incomplete = path.build();
    assertEquals(
        StopClose.HISTORY_CUT, HistoryNormalization.stopClose(incomplete, turnOf(incomplete)));

    path.append(assistant.id(), toolResult(assistant));
    EntryPath complete = path.build();
    assertEquals(
        StopClose.REUSE_ASSISTANT_RESULT,
        HistoryNormalization.stopClose(complete, turnOf(complete)));
  }

  @Test
  void inputTurnWithoutAnyAssistantResultNeedsInputToAppendTheCancelBarrier() {
    PathBuilder withoutInput = new PathBuilder();
    Entry root = withoutInput.root();
    withoutInput.turnStart(TurnStartReason.INPUT, root.id());
    EntryPath empty = withoutInput.build();
    assertEquals(StopClose.HISTORY_CUT, HistoryNormalization.stopClose(empty, turnOf(empty)));

    PathBuilder withInput = new PathBuilder();
    Entry inputRoot = withInput.root();
    Entry turn = withInput.turnStart(TurnStartReason.INPUT, inputRoot.id());
    withInput.append(turn.id(), new MessagePayload(AgentMessage.user("hi"), null, null));
    EntryPath ready = withInput.build();
    assertEquals(
        StopClose.APPEND_CANCEL_BARRIER, HistoryNormalization.stopClose(ready, turnOf(ready)));
  }

  private static Entry turnOf(EntryPath path) {
    return path.openTurnStart().orElseThrow();
  }

  private static MessagePayload assistantWithToolCall() {
    List<AgentMessageContent> contents = new ArrayList<>();
    contents.add(new ToolCallMessageContent("call-1", "read", "read", "{}"));
    contents.add(new TextMessageContent("answer"));
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage()),
        null);
  }

  private static MessagePayload toolResult(Entry assistantEntry) {
    MessagePayload assistant = (MessagePayload) assistantEntry.payload();
    ToolCallMessageContent call =
        (ToolCallMessageContent) assistant.message().contents().getFirst();
    return new MessagePayload(
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    call.toolCallId(),
                    call.toolName(),
                    call.rendererKey(),
                    List.of(new TextMessageContent("ok")),
                    false,
                    "{}"))),
        null,
        new ToolResultMetadata(
            UUID.randomUUID(),
            assistantEntry.id(),
            call.toolCallId(),
            0,
            ToolResultStatus.SUCCEEDED,
            false,
            null,
            null));
  }

  private static ModelUsage usage() {
    return new ModelUsage(1L, 1L, 0L, 0L, 0L, 0L, 2L);
  }

  private static ModelCost cost() {
    return new ModelCost(
        "USD",
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.valueOf(2));
  }

  private static BranchSettings settings() {
    return new BranchSettings(
        "assistant", new ModelSelection("anthropic", "claude-sonnet", "default"), null);
  }

  /** 逐条追加 Entry 的 builder：保证 Session / 父链 / id 唯一性与 EntryPath 不变式一致。 */
  private static final class PathBuilder {

    private final List<Entry> entries = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong(100L);

    Supplier<UUID> ids() {
      return () -> new UUID(0L, sequence.incrementAndGet());
    }

    Entry root() {
      return append(null, new RootPayload(settings()));
    }

    Entry turnStart(TurnStartReason reason, UUID parentId) {
      return append(parentId, new TurnStartPayload(reason, settings(), OWNER_THREAD_ID));
    }

    Entry compactionStart(UUID parentId) {
      return append(
          parentId,
          new TurnStartPayload(
              TurnStartReason.COMPACTION,
              settings(),
              OWNER_THREAD_ID,
              null,
              null,
              new CompactionStart(
                  CompactionPhase.FULL,
                  CompactionTrigger.THRESHOLD,
                  settings().model(),
                  new UUID(0L, 3L),
                  null,
                  null)));
    }

    Entry append(UUID parentId, EntryPayload payload) {
      Entry entry =
          new Entry(new UUID(0L, sequence.incrementAndGet()), SESSION_ID, parentId, payload, NOW);
      entries.add(entry);
      return entry;
    }

    EntryPath build() {
      return new EntryPath(List.copyOf(entries));
    }
  }
}
