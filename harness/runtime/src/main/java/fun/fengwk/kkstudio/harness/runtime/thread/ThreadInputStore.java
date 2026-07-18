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

  List<ThreadInput> listPending(long threadId);

  /** 按 sequence 升序返回下一条未应用输入。 */
  Optional<ThreadInput> findNextPending(long threadId);

  /** Exactly-once 标记 applied；已 applied 或缺失返回 false。 */
  boolean markApplied(long inputId, long appliedEntryId, Instant appliedAt);
}
