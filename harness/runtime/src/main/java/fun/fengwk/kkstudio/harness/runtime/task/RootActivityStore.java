package fun.fengwk.kkstudio.harness.runtime.task;

import java.util.List;

/** Database projection port for all RunEvents in a root Session tree. */
public interface RootActivityStore {
  List<RootActivity> list(long workspaceId, long rootSessionId, long afterEventId, int limit);
}
