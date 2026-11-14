package fun.fengwk.kkstudio.harness.runtime.execution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 退出确认失败的重试退避与日志去重。
 *
 * <p>同一 failure 连续同一原因时只累加 attempts 并推进下次可尝试时刻，不重复告警；原因变化或首次失败时记录一次。不变量错误 （事实缺失 / 身份冲突 / 目标冲突 /
 * 冲突）退避到最长一档，但仍保留后续核对机会，避免人工修复或合法深删后被永久跳过。
 */
final class FailureBackoff {

  private static final int[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 30};

  private final ConcurrentMap<UUID, Failure> failures = new ConcurrentHashMap<>();
  private final Set<String> reportedFileFailures = ConcurrentHashMap.newKeySet();
  private final AtomicReference<DirectoryFailure> directoryFailure = new AtomicReference<>();

  /** 该执行现在是否允许再次尝试。 */
  boolean shouldAttempt(UUID executionId, Instant now) {
    Failure failure = failures.get(executionId);
    return failure == null || !now.isBefore(failure.nextAttemptAt());
  }

  /** journal 目录级读取现在是否允许再次尝试。 */
  boolean shouldLoadDirectory(Instant now) {
    DirectoryFailure failure = directoryFailure.get();
    return failure == null || !now.isBefore(failure.nextAttemptAt());
  }

  /**
   * 记录一次失败并推进下次可尝试时刻。
   *
   * @return true 表示这是连续同一原因之外的首次失败，调用方应告警一次
   */
  boolean record(UUID executionId, FailureReason reason, Instant now) {
    Failure previous = failures.get(executionId);
    boolean sameReason = previous != null && previous.reason() == reason;
    int attempts = sameReason ? previous.attempts() + 1 : 1;
    int backoffStep = reason.isInvariant() ? BACKOFF_SECONDS.length : attempts;
    failures.put(
        executionId, new Failure(reason, attempts, now.plusSeconds(backoffSeconds(backoffStep))));
    return !sameReason;
  }

  void forget(UUID executionId) {
    failures.remove(executionId);
  }

  /** 目录恢复可读后清除目录级退避。 */
  void resetDirectoryFailure() {
    directoryFailure.set(null);
  }

  /** 该执行是否已有待重试的失败记录。 */
  boolean hasFailure(UUID executionId) {
    return failures.containsKey(executionId);
  }

  /**
   * 首次出现、且当前仍存在的文件问题。
   *
   * @return 本次需要告警的失败（首次出现的文件）
   */
  List<ExecutionExitJournalFailure> newFileFailures(
      List<ExecutionExitJournalFailure> fileFailures) {
    Set<String> current = new HashSet<>();
    List<ExecutionExitJournalFailure> fresh = new ArrayList<>();
    for (ExecutionExitJournalFailure failure : fileFailures) {
      current.add(failure.fileName());
      if (reportedFileFailures.add(failure.fileName())) {
        fresh.add(failure);
      }
    }
    reportedFileFailures.retainAll(current);
    return fresh;
  }

  /**
   * 目录读取失败：累计次数并推进下次尝试时刻。
   *
   * @return true 表示首次失败，调用方应告警一次
   */
  boolean recordDirectoryFailure(Instant now) {
    DirectoryFailure previous = directoryFailure.get();
    int attempts = previous == null ? 1 : previous.attempts() + 1;
    directoryFailure.set(new DirectoryFailure(attempts, now.plusSeconds(backoffSeconds(attempts))));
    return previous == null;
  }

  private static long backoffSeconds(int attempts) {
    return BACKOFF_SECONDS[Math.min(attempts - 1, BACKOFF_SECONDS.length - 1)];
  }

  /** 一次确认失败的原因；不变量错误使用最长退避。 */
  enum FailureReason {
    FACT_MISSING,
    IDENTITY_CONFLICT,
    TARGET_CONFLICT,
    CONFLICT,
    JOURNAL_WRITE,
    JOURNAL_DELETE,
    DATABASE,
    REPLAY;

    boolean isInvariant() {
      return this == FACT_MISSING
          || this == IDENTITY_CONFLICT
          || this == TARGET_CONFLICT
          || this == CONFLICT;
    }
  }

  private record DirectoryFailure(int attempts, Instant nextAttemptAt) {}

  private record Failure(FailureReason reason, int attempts, Instant nextAttemptAt) {}
}
