package fun.fengwk.kkstudio.platform.harness.store;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtinRegistry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.ForkMode;
import fun.fengwk.kkstudio.harness.runtime.history.ForkPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

/**
 * 真实 PostgreSQL / Testcontainers 下的 fork 契约：持久化依赖 {@code
 * schema/src/main/resources/db/migration/V1__schema.sql} 中 {@code ck_harness_entry_type} 包含的 {@code
 * FORK} 约束。本测试验证 FORK 事实经真实 SQL / jsonb 往返保真，且会话 fork 的 initial creation replay 幂等。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li><b>往返保真</b>：session fork 的新 Session 只产生 ROOT + FORK，FORK 的 mode / sourceThreadId /
 *       sourceEntryId 与 branch settings 经 jsonb 读回一致；
 *   <li><b>同 Session 分支 fork</b>：FORK 事实节点挂在共享前缀边界上（parent = 切点，且不携带 sourceThreadId）；
 *   <li><b>幂等</b>：同 raw 请求 replay 不新增行。
 * </ul>
 */
class PostgresqlForkContractTest {

  private HarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    PostgresqlHarnessStoreFixture.reset();
    store = PostgresqlHarnessStoreFixture.create();
    runtime =
        new HarnessRuntime(
            store,
            builtinRegistry(store, (threadId, path, preparation) -> null),
            () -> CompactionConfig.DEFAULT);
  }

  /** 会话 fork：ROOT + FORK 往返保真，branch settings 从来源切点推导，且 initial creation replay 幂等。 */
  @Test
  void sessionForkRoundTripsForkFactAndReplaysIdempotently() {
    applyDocumentedForkEntryTypeConstraint();
    Baseline baseline = seedThreadBaseline(store);
    UUID newSessionId = UUID.randomUUID();
    UUID newThreadId = UUID.randomUUID();
    AcceptCommandsCommand command =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewForkedSession(
                baseline.threadId(), baseline.rootEntryId(), newSessionId, newThreadId, false),
            List.of(userMessage("fork")));

    AcceptedCommands accepted = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    assertFalse(accepted.replayed());
    assertEquals(newSessionId, accepted.session().id());

    EntryPath path =
        store.transaction(tx -> tx.entries().loadPath(accepted.thread().headEntryId()));
    assertEquals(2, path.entries().size());
    ForkPayload payload = assertInstanceOf(ForkPayload.class, path.head().payload());
    assertEquals(ForkMode.SESSION, payload.mode());
    assertEquals(baseline.threadId(), payload.sourceThreadId());
    assertEquals(baseline.rootEntryId(), payload.sourceEntryId());
    // 切点是来源 ROOT：不复制前缀，FORK 直接挂在新 Session 的 ROOT 上。
    assertEquals(path.root().id(), path.head().parentEntryId());
    // 新 Session 的 ROOT settings 从来源切点（ROOT）推导，经真实 SQL 读回一致。
    BranchSettings sourceSettings =
        store.transaction(tx -> tx.entries().loadBranchSettings(baseline.rootEntryId()));
    BranchSettings forkedSettings =
        store.transaction(tx -> tx.entries().loadBranchSettings(accepted.thread().headEntryId()));
    assertEquals(sourceSettings, forkedSettings);

    AcceptedCommands replay = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed());
    assertEquals(accepted.thread().id(), replay.thread().id());
    assertEquals(
        2,
        store
            .transaction(tx -> tx.entries().loadPath(accepted.thread().headEntryId()))
            .entries()
            .size());
  }

  /** 同 Session 分支 fork：FORK 事实节点挂在共享前缀边界上，不携带来源 Thread。 */
  @Test
  void branchForkAppendsForkFactOnSharedPrefix() {
    applyDocumentedForkEntryTypeConstraint();
    Baseline baseline = seedThreadBaseline(store);
    UUID threadId = UUID.randomUUID();

    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewThread(
                    baseline.sessionId(), baseline.rootEntryId(), threadId, "branch", false),
                List.of(userMessage("branch"))),
            AcceptancePreflight.IDENTITY);
    assertFalse(accepted.replayed());

    EntryPath path =
        store.transaction(tx -> tx.entries().loadPath(accepted.thread().headEntryId()));
    assertEquals(2, path.entries().size());
    Entry fork = path.head();
    ForkPayload payload = assertInstanceOf(ForkPayload.class, fork.payload());
    assertEquals(ForkMode.BRANCH, payload.mode());
    assertEquals(baseline.rootEntryId(), payload.sourceEntryId());
    assertNull(payload.sourceThreadId());
    assertEquals(baseline.rootEntryId(), fork.parentEntryId());
    assertEquals(baseline.sessionId(), accepted.session().id());
  }

  private static NewThreadCommand userMessage(String text) {
    return new NewThreadCommand(
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)))),
        UUID.randomUUID());
  }

  /**
   * 确保独占测试容器内生效与 {@code schema/src/main/resources/db/migration/V1__schema.sql} 一致的 {@code
   * ck_harness_entry_type} 约束。
   */
  private static void applyDocumentedForkEntryTypeConstraint() {
    DataSource dataSource = PostgresqlHarnessStoreFixture.dataSource();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("alter table harness_entry drop constraint ck_harness_entry_type");
      statement.execute(
          "alter table harness_entry add constraint ck_harness_entry_type check (entry_type in"
              + " ('ROOT','TURN_START','MESSAGE','CUSTOM','MODEL_ATTEMPT_FAILURE','CUSTOM_MESSAGE',"
              + "'ASSISTANT_ERROR','ASSISTANT_ABORTED','COMPACTION','TURN_END','NOTIFICATION',"
              + "'SETTINGS','FORK'))");
    } catch (SQLException error) {
      throw new IllegalStateException("cannot apply documented FORK entry type constraint", error);
    }
  }
}
