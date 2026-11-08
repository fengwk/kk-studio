package fun.fengwk.kkstudio.platform.daemon.server;

import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.ENVIRONMENT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.FakeControlChannel;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.Fixture;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 确定性任务队列回归：通知/查询期间只允许一个同步任务，拒绝提交保留 dirty 到下一条通知。 */
class EnvironmentDaemonServerConfigSyncTest {

  @Test
  void notificationsDuringQueuedAndQueryingTaskSingleFlight() {
    ManualExecutor executor = new ManualExecutor();
    Fixture fixture = new Fixture(executor);
    FakeControlChannel channel = fixture.openSession("config-single-flight");
    assertEquals(1, channel.countOf(MessageType.ENVIRONMENT_CONFIG));

    fixture.server.environmentChanged(ENVIRONMENT_ID);
    assertEquals(1, executor.size(), "first notification must submit exactly one task");
    fixture.server.environmentChanged(ENVIRONMENT_ID);
    fixture.server.environmentChanged(ENVIRONMENT_ID);
    assertEquals(1, executor.size(), "notifications while queued must not create another task");

    fixture.configs.put(
        ENVIRONMENT_ID,
        EnvironmentDaemonServerTestSupport.Fixture.configSnapshot(ENVIRONMENT_ID, 3L));
    AtomicBoolean notifiedDuringQuery = new AtomicBoolean();
    fixture.configLookupHook =
        environmentId -> {
          if (notifiedDuringQuery.compareAndSet(false, true)) {
            fixture.server.environmentChanged(environmentId);
          }
        };
    executor.runNext();
    fixture.configLookupHook = null;

    assertEquals(2, channel.countOf(MessageType.ENVIRONMENT_CONFIG));
    assertEquals(3L, channel.lastEnvelope().getEnvironmentConfig().getVersion());
    assertEquals(0, executor.size(), "dirty during query must be consumed by the same worker");

    fixture.server.environmentChanged(ENVIRONMENT_ID);
    assertEquals(1, executor.size(), "a later notification starts a fresh state");
    executor.runNext();
    assertEquals(0, executor.size());
  }

  @Test
  void rejectedSubmissionKeepsDirtyForTheNextNotification() {
    RejectOnceExecutor executor = new RejectOnceExecutor();
    Fixture fixture = new Fixture(executor);
    FakeControlChannel channel = fixture.openSession("config-rejected-submit");

    fixture.server.environmentChanged(ENVIRONMENT_ID);
    assertEquals(0, executor.size(), "rejected submission must not queue a task");

    fixture.configs.put(
        ENVIRONMENT_ID,
        EnvironmentDaemonServerTestSupport.Fixture.configSnapshot(ENVIRONMENT_ID, 2L));
    fixture.server.environmentChanged(ENVIRONMENT_ID);
    assertEquals(1, executor.size(), "dirty intent must retry on the next notification");
    executor.runNext();

    assertEquals(0, executor.size());
    assertEquals(2, channel.countOf(MessageType.ENVIRONMENT_CONFIG));
    assertEquals(2L, channel.lastEnvelope().getEnvironmentConfig().getVersion());
  }

  /** 单线程确定性队列；测试显式控制任务开始，不依赖线程调度时序。 */
  private static class ManualExecutor implements Executor {
    private final Deque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    int size() {
      return tasks.size();
    }

    void runNext() {
      Runnable task = tasks.pollFirst();
      assertNotNull(task, "expected a queued config sync task");
      task.run();
    }
  }

  /** 首次提交抛出拒绝，后续提交正常入队，用于验证 dirty 保留而非立即重试框架。 */
  private static final class RejectOnceExecutor extends ManualExecutor {
    private boolean rejected;

    @Override
    public void execute(Runnable command) {
      if (!rejected) {
        rejected = true;
        throw new RejectedExecutionException("test rejection");
      }
      super.execute(command);
    }
  }
}
