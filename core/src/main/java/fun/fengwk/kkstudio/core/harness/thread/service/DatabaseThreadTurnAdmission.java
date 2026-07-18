package fun.fengwk.kkstudio.core.harness.thread.service;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTurnAdmission;

import java.util.Objects;
import java.util.Optional;

/**
 * DB-backed turn admission for durable child Threads.
 *
 * <p>Root / non-child Threads always admit. RUNNING children admit while {@code TURN_STARTED} count
 * is below persisted {@code maxTurns} (exactly {@code maxTurns} model requests are allowed).
 * Terminal CANCELLED/FAILED/SUCCEEDED tasks reject any new model Turn.
 *
 * <p>Task lookup uses {@code FOR UPDATE} so evaluation inside {@code beginTurn} serializes with
 * {@code cancelTree} after both paths lock the child Thread row.
 */
@Component
public class DatabaseThreadTurnAdmission implements ThreadTurnAdmission {
  private final HarnessSubagentTaskMapper taskMapper;
  private final HarnessThreadEventMapper eventMapper;

  public DatabaseThreadTurnAdmission(
      HarnessSubagentTaskMapper taskMapper, HarnessThreadEventMapper eventMapper) {
    this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
    this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
  }

  @Override
  public Optional<String> evaluate(long threadId) {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    // Locking lookup: beginTurn already holds the child Thread row; cancelTree locks Thread then
    // task, so both paths serialize and never admit after CANCELLED commits.
    HarnessSubagentTaskDO task = taskMapper.findByChildThreadIdForUpdate(threadId);
    if (task == null) {
      return Optional.empty();
    }
    if (!TaskState.RUNNING.name().equals(task.getStatus())) {
      return Optional.of(
          "subagent task is " + task.getStatus() + "; new model turns are not admitted");
    }
    int turnCount = eventMapper.countByType(threadId, ThreadEventType.TURN_STARTED.value());
    int maxTurns = task.getMaxTurns() == null ? 0 : task.getMaxTurns();
    if (turnCount >= maxTurns) {
      return Optional.of("subagent maxTurns exceeded: " + maxTurns);
    }
    return Optional.empty();
  }
}
