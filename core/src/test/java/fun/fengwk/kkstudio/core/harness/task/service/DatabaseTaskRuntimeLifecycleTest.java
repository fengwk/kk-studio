package fun.fengwk.kkstudio.core.harness.task.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadInputMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadInputDO;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.task.TaskInspection;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolTargetType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Durable task lifecycle edges: pending retry vs cancel-tree side effects. */
@SpringBootTest
class DatabaseTaskRuntimeLifecycleTest {

  @MockitoBean private ThreadKick threadKick;

  @Autowired private DatabaseTaskRuntime taskRuntime;
  @Autowired private HarnessSubagentTaskMapper taskMapper;
  @Autowired private HarnessThreadMapper threadMapper;
  @Autowired private HarnessThreadEventMapper eventMapper;
  @Autowired private HarnessThreadInputMapper inputMapper;
  @Autowired private ToolInvocationMapper invocationMapper;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from harness_thread_event");
    jdbc.update("delete from harness_subagent_task");
    jdbc.update("delete from tool_invocation");
    jdbc.update("delete from harness_thread_input");
    jdbc.update("delete from harness_thread");
    jdbc.update("delete from harness_session_entry");
    reset(threadKick);
  }

  @Test
  void failedLifecycleWithPendingRetryDoesNotCompleteTask() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long parentInvocationId = 6_600_001L;
    long childThreadId = 6_600_101L;
    long childSessionId = 6_600_201L;

    HarnessThreadDO child = new HarnessThreadDO();
    child.setId(childThreadId);
    child.setSessionId(childSessionId);
    child.setHeadEntryId(1L);
    child.setStatus(ThreadStatus.WAITING.name());
    child.setInputSequence(1L);
    child.setVersion(0L);
    child.setCreateTime(now);
    child.setUpdateTime(now);
    threadMapper.insert(child);

    HarnessThreadEventDO failed = new HarnessThreadEventDO();
    failed.setId(6_600_301L);
    failed.setThreadId(childThreadId);
    failed.setEventType("thread_failed");
    failed.setPayloadJson("{\"schemaVersion\":1,\"message\":\"old failure\"}");
    failed.setCreateTime(now.minusSeconds(30));
    eventMapper.insert(failed);

    HarnessThreadInputDO pending = new HarnessThreadInputDO();
    pending.setId(6_600_401L);
    pending.setThreadId(childThreadId);
    pending.setSequence(1L);
    pending.setInputType("user_message");
    pending.setPayloadJson("{\"type\":\"message\"}");
    pending.setCreateTime(now);
    inputMapper.insert(pending);

    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(parentInvocationId);
    task.setParentSessionId(1L);
    task.setParentThreadId(2L);
    task.setRootThreadId(2L);
    task.setChildSessionId(childSessionId);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent("sub");
    task.setWorkingCopyPolicy("NONE");
    task.setMaxTurns(4);
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(now);
    task.setUpdateTime(now);
    taskMapper.insert(task);

    TaskInspection inspection = taskRuntime.inspect(parentInvocationId, Instant.now());
    assertEquals(TaskState.RUNNING, inspection.task().state());
    assertNull(taskMapper.find(parentInvocationId).getReportJson());
  }

  /**
   * cancelTree marks the task CANCELLED and requests cancel on nonterminal child tools before
   * kicking; later model turns are blocked by turn admission, without interrupting an in-flight
   * provider call.
   */
  @Test
  void cancelTreeRequestsCancelOnChildTools() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long parentInvocationId = 6_600_011L;
    long childThreadId = 6_600_111L;
    long childSessionId = 6_600_211L;
    long childToolId = 6_600_311L;

    HarnessThreadDO child = new HarnessThreadDO();
    child.setId(childThreadId);
    child.setSessionId(childSessionId);
    child.setHeadEntryId(1L);
    child.setStatus(ThreadStatus.WAITING.name());
    child.setInputSequence(0L);
    child.setVersion(0L);
    child.setCreateTime(now);
    child.setUpdateTime(now);
    threadMapper.insert(child);

    ToolInvocationDO open = new ToolInvocationDO();
    open.setId(childToolId);
    open.setThreadId(childThreadId);
    open.setAssistantEntryId(1L);
    open.setOrdinal(0);
    open.setToolCallId("call-1");
    open.setToolName("bash");
    open.setToolVersion("1");
    open.setTargetType(ToolTargetType.CLOUD.name());
    open.setArgumentsJson("{}");
    open.setStatus(ToolInvocationStatus.QUEUED.name());
    open.setPermissionAction("ALLOW");
    open.setSideEffect("WRITE");
    open.setDeadlineAt(now.plusHours(1));
    open.setCreateTime(now);
    open.setUpdateTime(now);
    invocationMapper.insert(open);

    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(parentInvocationId);
    task.setParentSessionId(1L);
    task.setParentThreadId(2L);
    task.setRootThreadId(2L);
    task.setChildSessionId(childSessionId);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent("sub");
    task.setWorkingCopyPolicy("NONE");
    task.setMaxTurns(4);
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(now);
    task.setUpdateTime(now);
    taskMapper.insert(task);

    taskRuntime.cancelTree(parentInvocationId, Instant.now());

    assertEquals(TaskState.CANCELLED.name(), taskMapper.find(parentInvocationId).getStatus());
    assertEquals(
        ToolInvocationStatus.CANCEL_REQUESTED.name(),
        invocationMapper.find(childToolId).getStatus());
    assertEquals(ThreadStatus.RUNNING.name(), threadMapper.find(childThreadId).getStatus());
    verify(threadKick).kick(2L);
    verify(threadKick).kick(childThreadId);
    verifyNoMoreInteractions(threadKick);
  }

  /**
   * Child terminal report atomically completes the task, wakes its waiting parent, then kicks it.
   */
  @Test
  void childFailureReportsAndResumesParentAfterCommit() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long parentInvocationId = 6_600_021L;
    long parentThreadId = 6_600_121L;
    long childThreadId = 6_600_221L;
    insertThread(parentThreadId, parentThreadId + 10, now);
    insertThread(childThreadId, childThreadId + 10, now);

    HarnessThreadEventDO failed = new HarnessThreadEventDO();
    failed.setId(6_600_321L);
    failed.setThreadId(childThreadId);
    failed.setEventType("thread_failed");
    failed.setPayloadJson("{\"schemaVersion\":1,\"message\":\"child failed\"}");
    failed.setCreateTime(now);
    eventMapper.insert(failed);
    insertRunningTask(parentInvocationId, parentThreadId, childThreadId, childThreadId + 10, now);

    TaskInspection inspection = taskRuntime.inspect(parentInvocationId, Instant.now());

    assertEquals(TaskState.FAILED, inspection.task().state());
    assertNotNull(inspection.report());
    assertEquals("child failed", inspection.report().finalAssistantReport());
    assertEquals(ThreadStatus.RUNNING.name(), threadMapper.find(parentThreadId).getStatus());
    verify(threadKick).kick(parentThreadId);
    verifyNoMoreInteractions(threadKick);
  }

  private void insertThread(long id, long sessionId, LocalDateTime now) {
    HarnessThreadDO thread = new HarnessThreadDO();
    thread.setId(id);
    thread.setSessionId(sessionId);
    thread.setHeadEntryId(1L);
    thread.setStatus(ThreadStatus.WAITING.name());
    thread.setInputSequence(0L);
    thread.setVersion(0L);
    thread.setCreateTime(now);
    thread.setUpdateTime(now);
    threadMapper.insert(thread);
  }

  private void insertRunningTask(
      long parentInvocationId,
      long parentThreadId,
      long childThreadId,
      long childSessionId,
      LocalDateTime now) {
    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(parentInvocationId);
    task.setParentSessionId(parentThreadId + 20);
    task.setParentThreadId(parentThreadId);
    task.setRootThreadId(parentThreadId);
    task.setChildSessionId(childSessionId);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent("sub");
    task.setWorkingCopyPolicy("NONE");
    task.setMaxTurns(4);
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(now);
    task.setUpdateTime(now);
    taskMapper.insert(task);
  }
}
