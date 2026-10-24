package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.FakeRun;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.Fixture;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Stop 之后活动执行体尚未退出时，不得把子 Thread 投影成已空闲，也不得提前交付其 NOTIFY_PARENT Join。
 *
 * <p>真实 ToolProcessor 已进入工具 body；工具观察到 Stop 中断后，仍由 latch 挡住退出。执行体还在父 gate 内时必须保持 RUNNING 且 Join
 * 未交付。finally 无论断言成败都释放并等待执行体退出；释放后的 Studio 退出确认必须只交付一次。
 */
class HarnessRuntimeStopExitContractTest {

  @Test
  void stopKeepsRunningStatusAndWithholdsJoinUntilTheEnteredToolExits() throws Exception {
    Fixture fixture = ToolProcessorTestSupport.childFixture(false, 1);
    fixture.gateway.queuePreflightAllow();
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    FakeRun run =
        fixture.gateway.queueRun(
            progress -> {
              while (true) {
                try {
                  if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release the tool body");
                  }
                  return new ToolGateway.Cancelled(
                      new ToolInvocationError("CANCELLED", "cancelled"));
                } catch (InterruptedException expected) {
                  // 中断已送达，但执行体还没退出；用独立 gate 保持这个确定性窗口。
                  interrupted.countDown();
                }
              }
            });
    HarnessRuntime runtime =
        new HarnessRuntime(
            fixture.store,
            (threadId, candidatePath, preparation) -> null,
            () -> CompactionConfig.DEFAULT,
            null,
            mock(ModelProcessor.class),
            fixture.processor);
    UUID joinId = UUID.randomUUID();
    UUID parentId = insertSourceAndJoin(fixture, joinId);
    UUID[] executionSeen = new UUID[1];
    try {
      ClaimedWork claim =
          ToolProcessorTestSupport.claim(fixture.store, fixture.toolInvocationId, NOW);
      assertEquals(ProcessResult.STARTED, fixture.processor.process(claim));
      executionSeen[0] =
          fixture.store.transaction(
              tx -> tx.loadUnconfirmedExecutions(fixture.baseline.threadId()).get(0).executionId());
      assertTrue(run.awaitExecuting(), "tool body must be entered before stop");
      assertEquals(0, run.closes.get(), "body has not finished");
      ThreadSnapshot before = runtime.getThreadSnapshot(fixture.baseline.threadId());
      assertEquals(ThreadRuntimeStatus.RUNNING, before.runtimeStatus());
      assertFalse(runtime.findJoin(joinId).orElseThrow().matched());
      assertFalse(parentNotified(fixture, parentId));

      runtime.stop(
          new StopCommand(fixture.baseline.threadId(), TestIds.id(71), before.thread().version()));

      assertTrue(interrupted.await(5, TimeUnit.SECONDS), "Stop must reach the entered tool");
      assertEquals(
          1,
          run.exited.getCount(),
          "stop must be observed while the real tool body is still inside the parent gate");
      assertEquals(0, run.closes.get(), "close follows body exit");
      ThreadSnapshot child = runtime.getThreadSnapshot(fixture.baseline.threadId());
      ThreadJoin join = fixture.store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
      assertEquals(ThreadExecutionControl.STOPPED, child.thread().executionControl());
      boolean notified = parentNotified(fixture, parentId);
      assertAll(
          () -> assertEquals(ThreadRuntimeStatus.RUNNING, child.runtimeStatus()),
          () ->
              assertFalse(
                  join.matched(), "NOTIFY_PARENT join must stay unmatched until the body exits"),
          () -> assertFalse(notified, "parent must not receive an early notification"));
    } finally {
      release.countDown();
      try {
        if (run.executionThread != null) {
          assertTrue(
              run.executionThread.join(Duration.ofSeconds(5)),
              "released tool execution must exit, including its cleanup");
          assertEquals(0, run.exited.getCount(), "tool body really returned");
          assertEquals(1, run.closes.get(), "run closes after the real body returns");
          assertTrue(run.exitedBeforeClose);
        }
      } finally {
        fixture.processor.close();
        fixture.scheduler.shutdownNow();
        assertTrue(fixture.scheduler.awaitTermination(5, TimeUnit.SECONDS));
      }
    }
    // 真实 body 返回且 close 成功后，Studio 退出确认必须把 STOPPED 子线程收敛为 IDLE，并只交付一次 Join。
    ThreadSnapshot settled = runtime.getThreadSnapshot(fixture.baseline.threadId());
    ThreadJoin delivered = runtime.findJoin(joinId).orElseThrow();
    assertEquals(ThreadRuntimeStatus.IDLE, settled.runtimeStatus());
    assertEquals(ThreadExecutionControl.STOPPED, settled.thread().executionControl());
    assertTrue(delivered.matched(), "studio exit confirmation delivers the withheld join once");
    assertEquals(1, notificationCount(fixture, parentId));
    ExecutionExitFact confirmed =
        fixture.store.transaction(
            tx -> {
              assertTrue(
                  tx.loadUnconfirmedExecutions(fixture.baseline.threadId()).isEmpty(),
                  "the entered generation is confirmed after close");
              return tx.findExecution(executionSeen[0]).orElseThrow();
            });
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    assertEquals(ExecutionExitKind.TOOL, confirmed.kind());
    assertEquals(fixture.baseline.threadId(), confirmed.threadId());
  }

  @Test
  void remoteAttemptUsesItsOwnIdentityAndAMismatchedAcknowledgementDoesNotConfirmIt()
      throws Exception {
    // 测试意图：每次远端调用使用独立 UUID；身份不符的 ACK 抛出且不改行，匹配 ACK 幂等。
    Fixture fixture = ToolProcessorTestSupport.childFixture(false, 1);
    fixture.gateway.queuePreflightAllow();
    UUID remoteId = UUID.randomUUID();
    FakeRun run =
        fixture.gateway.queueRun(
            progress -> {
              ToolGateway.Execution execution = fixture.gateway.executions.get(0);
              assertTrue(execution.remoteExecutions().tryStart(remoteId));
              execution.remoteExecutions().confirmExit(remoteId);
              execution.remoteExecutions().confirmExit(remoteId);
              return new ToolGateway.Cancelled(new ToolInvocationError("CANCELLED", "cancelled"));
            });
    try {
      ClaimedWork claim =
          ToolProcessorTestSupport.claim(fixture.store, fixture.toolInvocationId, NOW);
      assertEquals(ProcessResult.STARTED, fixture.processor.process(claim));
      assertTrue(run.awaitExited());
      ExecutionExitFact remote =
          fixture.store.transaction(tx -> tx.findExecution(remoteId).orElseThrow());
      assertEquals(ExecutionExitKind.REMOTE, remote.kind());
      assertEquals(ExecutionExitState.REMOTE_ACKNOWLEDGED, remote.state());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.store.transaction(
                  tx -> {
                    tx.lockThread(remote.threadId());
                    return tx.confirmExecution(
                        new ExecutionExitFact(
                            remote.executionId(),
                            remote.threadId(),
                            remote.invocationId(),
                            remote.attempt(),
                            "other-lease",
                            remote.kind(),
                            remote.parentExecutionId(),
                            ExecutionExitState.UNCONFIRMED,
                            remote.registeredAt(),
                            null),
                        ExecutionExitState.NOT_STARTED);
                  }));
      assertEquals(
          ExecutionExitState.REMOTE_ACKNOWLEDGED,
          fixture.store.transaction(tx -> tx.findExecution(remoteId).orElseThrow().state()));
    } finally {
      fixture.processor.close();
      fixture.scheduler.shutdownNow();
      assertTrue(fixture.scheduler.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static UUID insertSourceAndJoin(Fixture fixture, UUID joinId) {
    return fixture.store.transaction(
        tx -> {
          ThreadState child = tx.lockThread(fixture.baseline.threadId()).orElseThrow();
          MessagePayload input =
              (MessagePayload) tx.findEntry(fixture.baseline.userEntryId()).orElseThrow().payload();
          ThreadCommand source =
              new ThreadCommand(
                  child.id(),
                  1L,
                  new UserMessageCommandPayload(input.message()),
                  tx.nextId(),
                  CREATION_REQUEST_HASH,
                  null,
                  null,
                  null,
                  NOW);
          tx.updateThread(child.reserveCommandSequences(1, NOW));
          tx.insertCommands(List.of(source));
          tx.updateCommands(List.of(source.markApplied(fixture.baseline.turnStartEntryId())));
          child = tx.lockThread(child.id()).orElseThrow();
          tx.updateThread(
              child.advanceHeadAndInputThroughSequence(
                  fixture.baseline.assistantEntryId(), 1L, NOW));
          tx.insertJoin(
              new ThreadJoin(
                  joinId,
                  CREATION_REQUEST_HASH,
                  child.parentThreadId(),
                  fixture.baseline.threadId(),
                  1L,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW,
                  JoinDelivery.NOTIFY_PARENT,
                  null));
          return child.parentThreadId();
        });
  }

  private static boolean parentNotified(Fixture fixture, UUID parentId) {
    return notificationCount(fixture, parentId) > 0;
  }

  /** 父线程上的通知命令与已物化通知各计一次，用来确认 Join 只交付一次。 */
  private static int notificationCount(Fixture fixture, UUID parentId) {
    return fixture.store.transaction(
        tx -> {
          int count = 0;
          for (ThreadCommand command : tx.loadCommandsByThread(parentId)) {
            if (command.type().isNotification()) {
              count++;
            }
          }
          ThreadState parent = tx.findThread(parentId).orElseThrow();
          EntryPath path = tx.loadEntryPath(parent.headEntryId());
          for (Entry entry : path.entries()) {
            if (entry.payload() instanceof NotificationPayload) {
              count++;
            }
          }
          return count;
        });
  }
}
