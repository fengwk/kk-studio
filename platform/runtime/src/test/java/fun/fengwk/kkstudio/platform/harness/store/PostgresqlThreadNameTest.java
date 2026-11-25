package fun.fengwk.kkstudio.platform.harness.store;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtinRegistry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.loadCommandsByThread;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.util.List;
import java.util.UUID;

/** 真实 PG 验证 idle branch 上的 command 重放及名称冲突零写入。 */
class PostgresqlThreadNameTest {
  @Test
  void commandReplayAfterRenameIsIdempotentAndNameConflictHasNoWrites() {
    var store = PostgresqlHarnessStoreFixture.resetAndCreate();
    var baseline = seedThreadBaseline(store);
    var runtime =
        new HarnessRuntime(
            store,
            builtinRegistry(store, (threadId, rootSessionId, path, prep) -> null),
            () -> new CompactionConfig(20_000));
    UUID createdId = UUID.randomUUID();
    UUID cut =
        runtime
            .stop(new StopCommand(baseline.threadId(), UUID.randomUUID(), 0))
            .thread()
            .headEntryId();
    var created = runtime.createIdleBranch(baseline.threadId(), cut, createdId, "branch");
    var command =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(createdId),
            List.of(
                new NewThreadCommand(
                    new UserMessageCommandPayload(AgentMessage.user("hello")), UUID.randomUUID())));
    var accepted = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    var renamed = runtime.renameThread(new RenameThreadCommand(createdId, "renamed"));
    var replay = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed());
    assertEquals(renamed, replay.thread());
    assertEquals(1, store.transaction(tx -> loadCommandsByThread(tx, createdId)).size());
    assertEquals(created.id(), accepted.thread().id());
    assertEquals(created.id(), renamed.id());
    UUID rejectedId = UUID.randomUUID();
    var conflict =
        assertThrows(
            DomainException.class,
            () -> runtime.createIdleBranch(baseline.threadId(), cut, rejectedId, "renamed"));
    assertEquals(DomainErrorCode.CONFLICT, conflict.code());
    assertEquals(HarnessConflictReason.THREAD_NAME_CONFLICT.name(), conflict.reason());
    assertNotNull(conflict.getCause());
    assertTrue(store.transaction(tx -> tx.threads().find(rejectedId)).isEmpty());
    assertTrue(store.transaction(tx -> loadCommandsByThread(tx, rejectedId)).isEmpty());
    assertTrue(
        store
            .transaction(tx -> tx.work().find(new WorkTarget(WorkTargetType.THREAD, rejectedId)))
            .isEmpty());
    assertEquals(
        2, store.transaction(tx -> tx.threads().listBySession(baseline.sessionId())).size());
  }
}
