package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** ThreadInput 队列端口。 */
public interface ThreadInputStore {
  void insert(ThreadInput input);

  Optional<ThreadInput> findById(long inputId);

  Optional<ThreadInput> findByClientMessageId(long threadId, String clientMessageId);

  List<ThreadInput> listByThread(long threadId);

  List<ThreadInput> listQueued(long threadId);

  /** 按 sequence 升序返回 cutoff 内全部 QUEUED 输入。 */
  List<ThreadInput> listQueuedUpTo(long threadId, long cutoffSequence);

  /** Exactly-once 标记 APPLIED；非 QUEUED 返回 false。 */
  boolean markApplied(long inputId, long appliedEntryId, Instant resolvedAt);

  /** Exactly-once 标记 CANCELLED；非 QUEUED 返回 false。 */
  boolean markCancelled(long inputId, long stopId, Instant resolvedAt);

  List<ThreadInput> listCancelledByStop(long threadId, long stopId);
}
