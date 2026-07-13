package fun.fengwk.kkstudio.harness.runtime.run;

import java.time.Instant;
import java.util.List;

/** Run Event Journal 端口；append 必须与 Run.eventSequence 分配处于同一事务。 */
public interface RunEventStore {
  RunEvent append(long runId, RunEventType type, String payloadJson, Instant createdAt);

  List<RunEvent> listAfter(long runId, long afterSequence, int limit);
}
