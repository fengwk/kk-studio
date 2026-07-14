package fun.fengwk.kkstudio.core.harness.task.store;

import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunEventMapper;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.task.RootActivity;
import fun.fengwk.kkstudio.harness.runtime.task.RootActivityStore;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** SQL-backed root tree activity cursor; event id is globally ordered across descendant Runs. */
@Repository
public class DatabaseRootActivityStore implements RootActivityStore {
  private final HarnessRunEventMapper eventMapper;

  public DatabaseRootActivityStore(HarnessRunEventMapper eventMapper) {
    this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
  }

  @Override
  public List<RootActivity> list(long workspaceId, long rootSessionId, long afterEventId, int limit) {
    if (workspaceId <= 0 || rootSessionId <= 0 || afterEventId < 0 || limit <= 0) {
      throw new IllegalArgumentException("invalid root activity query");
    }
    return eventMapper.listRootActivity(workspaceId, rootSessionId, afterEventId, limit).stream()
        .map(
            event ->
                new RootActivity(
                    workspaceId,
                    rootSessionId,
                    event.getSessionId(),
                    event.getRunId(),
                    event.getId(),
                    event.getSequence(),
                    RunEventType.fromValue(event.getEventType()),
                    event.getPayloadJson(),
                    event.getCreateTime().toInstant(ZoneOffset.UTC)))
        .toList();
  }
}
