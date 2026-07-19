package fun.fengwk.kkstudio.core.harness.thread.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/** DB turn admission for root vs durable child maxTurns / terminal task states. */
@SpringBootTest
@Transactional
class DatabaseThreadTurnAdmissionTest {

  @Autowired private DatabaseThreadTurnAdmission admission;
  @Autowired private HarnessSubagentTaskMapper taskMapper;
  @Autowired private HarnessThreadEventMapper eventMapper;

  @Test
  void rootThreadIsAlwaysAdmitted() {
    assertTrue(admission.evaluate(9_900_001L).isEmpty());
  }

  @Test
  void runningChildBelowMaxTurnsIsAdmitted() {
    long childThreadId = 9_900_101L;
    insertTask(childThreadId, TaskState.RUNNING, 3);
    insertTurnStarted(childThreadId, 2);
    assertTrue(admission.evaluate(childThreadId).isEmpty());
  }

  @Test
  void runningChildAtMaxTurnsIsRejected() {
    long childThreadId = 9_900_102L;
    insertTask(childThreadId, TaskState.RUNNING, 2);
    insertTurnStarted(childThreadId, 2);
    Optional<String> rejection = admission.evaluate(childThreadId);
    assertTrue(rejection.isPresent());
    assertTrue(rejection.get().contains("maxTurns"));
  }

  @Test
  void cancelledTaskRejectsNewModelWork() {
    long childThreadId = 9_900_103L;
    insertTask(childThreadId, TaskState.CANCELLED, 5);
    Optional<String> rejection = admission.evaluate(childThreadId);
    assertTrue(rejection.isPresent());
    assertEquals("subagent task is CANCELLED; new model turns are not admitted", rejection.get());
  }

  private void insertTask(long childThreadId, TaskState state, int maxTurns) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(childThreadId + 1_000);
    task.setParentSessionId(1L);
    task.setParentThreadId(2L);
    task.setRootThreadId(2L);
    task.setChildSessionId(childThreadId + 100);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent("sub");
    task.setWorkingCopyPolicy("NONE");
    task.setMaxTurns(maxTurns);
    task.setStatus(state.name());
    task.setCreateTime(now);
    task.setUpdateTime(now);
    taskMapper.insert(task);
  }

  private void insertTurnStarted(long threadId, int count) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    for (int i = 0; i < count; i++) {
      HarnessThreadEventDO event = new HarnessThreadEventDO();
      event.setId(threadId * 10 + i + 1);
      event.setThreadId(threadId);
      event.setEventType(ThreadEventType.TURN_STARTED.value());
      event.setPayloadJson("{\"schemaVersion\":1}");
      event.setCreateTime(now.minusSeconds(count - i));
      eventMapper.insert(event);
    }
  }
}
