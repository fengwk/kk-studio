package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread command 用例的原子持久化端口。
 *
 * <p>实现只把 {@link ExecutionTarget} 当作提交后的 best-effort activation 信号。Redis/dispatcher 失败绝不回滚已提交的
 * durable mutation。
 */
public interface ThreadCommandTransactions {
  HarnessThread createThread(Instant now);

  SessionCreation createSession(String title, RuntimeConfigSnapshot initialConfig, Instant now);

  BootstrapResult bootstrapThread(
      long threadId,
      long expectedExecutionEpoch,
      String title,
      RuntimeConfigSnapshot initialConfig,
      Instant now);

  HarnessThread updateHead(
      long threadId, long expectedExecutionEpoch, Long headEntryId, Instant now);

  Optional<RuntimeConfigSnapshot> lockAndFindCurrentConfig(
      long threadId, long expectedExecutionEpoch);

  Optional<EnqueueResult> findExistingInput(long threadId, String idempotencyKey);

  EnqueueResult enqueue(
      long threadId,
      ThreadInputPayload payload,
      String idempotencyKey,
      long expectedExecutionEpoch,
      Instant now);

  StopResult stop(long threadId, long expectedExecutionEpoch, Instant now);

  record SessionCreation(Session session, SessionEntry rootEntry, SessionEntry configEntry) {
    public SessionCreation {
      Objects.requireNonNull(session, "session");
      Objects.requireNonNull(rootEntry, "rootEntry");
      Objects.requireNonNull(configEntry, "configEntry");
    }
  }

  record BootstrapResult(
      Session session, SessionEntry rootEntry, SessionEntry configEntry, HarnessThread thread) {
    public BootstrapResult {
      Objects.requireNonNull(session, "session");
      Objects.requireNonNull(rootEntry, "rootEntry");
      Objects.requireNonNull(configEntry, "configEntry");
      Objects.requireNonNull(thread, "thread");
    }
  }

  record EnqueueResult(ThreadInput input, ExecutionTarget target) {}

  record StopResult(
      long executionEpoch, List<ThreadInput> cancelledInputs, ExecutionTarget target) {}
}
