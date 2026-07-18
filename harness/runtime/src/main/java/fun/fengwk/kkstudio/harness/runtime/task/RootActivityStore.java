package fun.fengwk.kkstudio.harness.runtime.task;

import java.util.List;

/** Database projection port for all ThreadEvents in a root Session tree. */
public interface RootActivityStore {
  List<RootActivity> list(long rootSessionId, long afterEventId, int limit);
}
