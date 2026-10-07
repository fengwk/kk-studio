package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** 真实 PG 验证创建身份重放优先于名称唯一写入，冲突不产生命令或 Work。 */
class PostgresqlThreadNameTest {
  @Test
  void creationReplayAfterRenameDoesNotInsertOrAdvanceAnyFacts() {
    var store = PostgresqlHarnessStoreFixture.resetAndCreate();
    var baseline = seedThreadBaseline(store);
    var runtime =
        new HarnessRuntime(
            store,
            Clock.fixed(T0, ZoneOffset.UTC),
            (threadId, path, prep) -> null,
            () -> CompactionConfig.DEFAULT);
    UUID createdId = UUID.randomUUID();
    var commands =
        List.of(
            new NewThreadCommand(
                new UserMessageCommandPayload(AgentMessage.user("hello")), UUID.randomUUID()));
    var request =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(
                baseline.sessionId(), baseline.rootEntryId(), createdId, "branch", false),
            commands);
    runtime.acceptCommands(request, AcceptancePreflight.IDENTITY);
    var renamed = runtime.renameThread(new RenameThreadCommand(createdId, "renamed"));
    var replay = runtime.acceptCommands(request, AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed());
    assertEquals(renamed, replay.thread());
    assertEquals(1, store.transaction(tx -> tx.loadCommandsByThread(createdId)).size());
    UUID rejectedId = UUID.randomUUID();
    var conflictRequest =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(
                baseline.sessionId(), baseline.rootEntryId(), rejectedId, "renamed", false),
            commands);
    var conflict =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.acceptCommands(conflictRequest, AcceptancePreflight.IDENTITY));
    assertEquals(HarnessRuntimeConflictException.Reason.THREAD_NAME_CONFLICT, conflict.reason());
    assertTrue(store.transaction(tx -> tx.findThread(rejectedId)).isEmpty());
    assertTrue(store.transaction(tx -> tx.loadCommandsByThread(rejectedId)).isEmpty());
    assertTrue(
        store
            .transaction(tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, rejectedId)))
            .isEmpty());
    assertEquals(2, store.transaction(tx -> tx.listThreadsBySession(baseline.sessionId())).size());
    var renameConflict =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.renameThread(new RenameThreadCommand(createdId, "main")));
    assertEquals(
        HarnessRuntimeConflictException.Reason.THREAD_NAME_CONFLICT, renameConflict.reason());
    assertEquals(renamed, store.transaction(tx -> tx.findThread(createdId).orElseThrow()));
  }
}
