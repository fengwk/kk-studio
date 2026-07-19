package fun.fengwk.kkstudio.harness.runtime.thread;

import java.util.Optional;

/** ThreadStop 回执端口。 */
public interface ThreadStopStore {
  void insert(ThreadStop stop);

  Optional<ThreadStop> findByClientRequestId(long threadId, String clientRequestId);
}
