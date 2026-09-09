package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.resolvedTurnStartPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import javax.sql.DataSource;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 守护 PostgreSQL 适配下具有非 ROOT head（非空历史）Thread 的命令接受与 exact ordered replay 不再执行 recursive entry_path
 * CTE，且执行开销与 Entry 树深度无关。
 */
class PostgresqlAcceptCommandsRootQueryTest {

  private final AtomicInteger cteCounter = new AtomicInteger();
  private HarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    PostgresqlHarnessStoreFixture.reset();
    cteCounter.set(0);
    DataSource countingDs =
        PostgresqlEntryPathCteCounter.countingDataSource(
            PostgresqlHarnessStoreFixture.dataSource(), cteCounter);
    store = PostgresqlHarnessStoreFixture.create(countingDs);
    runtime =
        new HarnessRuntime(
            store,
            Clock.fixed(T0, ZoneOffset.UTC),
            (threadId, path, prep) -> null,
            () -> CompactionConfig.DEFAULT);
  }

  /** 测试意图：具有非 ROOT head 的 Thread 接受全新 batch 或 exact ordered replay 时只查询 ROOT，不回溯完整 EntryPath。 */
  @Test
  void threadWithNonRootHeadAcceptNewBatchAndOrderedReplayExecuteZeroCte() {
    Baseline baseline = seedThreadBaseline(store);
    UUID turnStartId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            resolvedTurnStartPayload(baseline.threadId()));
    UUID userMsgId =
        insertChildEntry(store, baseline.sessionId(), turnStartId, userMessagePayload());
    UUID assistantMsgId =
        insertChildEntry(store, baseline.sessionId(), userMsgId, assistantPayload());
    UUID turnEndId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            assistantMsgId,
            new TurnEndPayload(turnStartId, TurnEndOutcome.COMPLETED, false, null, null));

    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          ThreadState current = tx.findThread(baseline.threadId()).orElseThrow();
          tx.updateThread(
              new ThreadState(
                  current.id(),
                  current.sessionId(),
                  turnEndId,
                  current.creationRequestHash(),
                  current.name(),
                  current.yoloEnabled(),
                  current.nextCommandSequence(),
                  current.version() + 1,
                  current.createdAt(),
                  T2));
          return null;
        });

    cteCounter.set(0);
    List<NewThreadCommand> batch = List.of(userMessageCommand(TestIds.id(50), "next message"));
    AcceptCommandsCommand command =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(baseline.threadId(), turnEndId, 1), batch);

    AcceptedCommands accepted = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    assertFalse(accepted.replayed());
    assertEquals(baseline.rootEntryId(), accepted.rootEntry().id());
    assertEquals(
        0, cteCounter.get(), "非 ROOT head Thread 上的全新 batch 接受不执行 with recursive entry_path CTE");

    cteCounter.set(0);
    AcceptedCommands replay = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed());
    assertEquals(baseline.rootEntryId(), replay.rootEntry().id());
    assertEquals(
        0,
        cteCounter.get(),
        "非 ROOT head Thread 上的 exact ordered replay 不执行 with recursive entry_path CTE");
  }

  private static NewThreadCommand userMessageCommand(UUID idempotencyKey, String text) {
    return new NewThreadCommand(
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)))),
        idempotencyKey);
  }
}
