package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.List;

/** ThreadEvent journal 端口；事件 id 为全局 cursor。 */
public interface ThreadEventStore {
  ThreadEvent append(
      long threadId, Long subjectEntryId, ThreadEventType type, String payloadJson, Instant now);

  List<ThreadEvent> listAfter(long threadId, long afterEventId, int limit);
}
