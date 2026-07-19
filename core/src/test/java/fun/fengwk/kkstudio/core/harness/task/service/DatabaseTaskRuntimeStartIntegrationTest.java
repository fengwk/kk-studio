package fun.fengwk.kkstudio.core.harness.task.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadInputMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadInputDO;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.task.TaskCommand;
import fun.fengwk.kkstudio.harness.runtime.task.TaskInspection;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.task.WorkingCopyPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Child task creation persists its own Session/Main Thread/input and preserves the delegation root.
 */
@SpringBootTest
class DatabaseTaskRuntimeStartIntegrationTest {
  private static final long ROOT_SESSION_ID = 9_710_001L;
  private static final long ROOT_THREAD_ID = 9_710_101L;
  private static final long ROOT_ENTRY_ID = 9_710_201L;
  private static final long ROOT_INVOCATION_ID = 9_710_301L;
  private static final long NESTED_INVOCATION_ID = 9_710_302L;
  private static final long AGENT_ID = 9_710_401L;
  private static final String SUBAGENT = "test-subagent";

  @MockitoBean private ThreadKick threadKick;

  @Autowired private DatabaseTaskRuntime taskRuntime;
  @Autowired private AgentDefinitionMapper agentMapper;
  @Autowired private HarnessSessionMapper sessionMapper;
  @Autowired private HarnessSessionEntryMapper entryMapper;
  @Autowired private HarnessThreadMapper threadMapper;
  @Autowired private HarnessThreadInputMapper inputMapper;
  @Autowired private ToolInvocationMapper invocationMapper;
  @Autowired private HarnessSubagentTaskMapper taskMapper;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from harness_thread_event");
    jdbc.update("delete from harness_subagent_task");
    jdbc.update("delete from tool_invocation");
    jdbc.update("delete from harness_thread_input");
    jdbc.update("delete from harness_thread");
    jdbc.update("delete from harness_session_entry");
    jdbc.update("delete from harness_session");
    jdbc.update("delete from agent_definition where id = ?", AGENT_ID);
    reset(threadKick);
  }

  /**
   * A nested task keeps the first user's rootThreadId while each child owns a fresh Main Thread.
   */
  @Test
  void createsIndependentChildMainThreadQueuedPromptAndInheritedRootThread() {
    LocalDateTime timestamp = LocalDateTime.now(ZoneOffset.UTC);
    Instant now = timestamp.toInstant(ZoneOffset.UTC);
    insertTargetAgent();
    insertRootSession(timestamp);
    insertThread(ROOT_THREAD_ID, ROOT_SESSION_ID, ROOT_ENTRY_ID, timestamp);
    invocationMapper.insert(
        invocation(ROOT_INVOCATION_ID, ROOT_THREAD_ID, ROOT_ENTRY_ID, timestamp));

    TaskInspection first =
        taskRuntime.startOrResume(
            new ToolExecutionContext(ROOT_INVOCATION_ID, ROOT_THREAD_ID),
            command("first prompt"),
            now);

    assertEquals(ROOT_THREAD_ID, first.task().rootThreadId());
    assertEquals(TaskState.RUNNING, first.task().state());
    HarnessSessionDO firstSession = sessionMapper.find(first.task().childSessionId());
    HarnessThreadDO firstThread = threadMapper.find(first.task().childThreadId());
    assertNotNull(firstSession);
    assertNotNull(firstThread);
    assertEquals(first.task().childThreadId(), firstSession.getMainThreadId());
    assertEquals(first.task().childSessionId(), firstThread.getSessionId());
    assertEquals(ThreadStatus.RUNNING.name(), firstThread.getStatus());
    List<HarnessThreadInputDO> firstInputs = inputMapper.listByThread(first.task().childThreadId());
    assertEquals(1, firstInputs.size());
    assertEquals(1L, firstInputs.get(0).getSequence());
    assertEquals(ThreadInputType.USER_MESSAGE.value(), firstInputs.get(0).getInputType());
    assertEquals(ThreadInputStatus.QUEUED.value(), firstInputs.get(0).getStatus());
    assertEquals("subagent:" + ROOT_INVOCATION_ID, firstInputs.get(0).getClientMessageId());
    assertTrue(firstInputs.get(0).getPayloadJson().contains("first prompt"));

    TaskInspection replay =
        taskRuntime.startOrResume(
            new ToolExecutionContext(ROOT_INVOCATION_ID, ROOT_THREAD_ID),
            command("ignored replay"),
            now);
    assertEquals(first.task().childSessionId(), replay.task().childSessionId());
    assertEquals(first.task().childThreadId(), replay.task().childThreadId());
    assertEquals(TaskState.RUNNING, replay.task().state());

    invocationMapper.insert(
        invocation(
            NESTED_INVOCATION_ID,
            first.task().childThreadId(),
            firstThread.getHeadEntryId(),
            timestamp));
    TaskInspection nested =
        taskRuntime.startOrResume(
            new ToolExecutionContext(NESTED_INVOCATION_ID, first.task().childThreadId()),
            command("nested prompt"),
            now);

    assertEquals(ROOT_THREAD_ID, nested.task().rootThreadId());
    HarnessSessionDO nestedSession = sessionMapper.find(nested.task().childSessionId());
    HarnessThreadDO nestedThread = threadMapper.find(nested.task().childThreadId());
    assertNotNull(nestedSession);
    assertNotNull(nestedThread);
    assertEquals(first.task().childSessionId(), nestedSession.getParentSessionId());
    assertEquals(nested.task().childThreadId(), nestedSession.getMainThreadId());
    assertEquals(nested.task().childSessionId(), nestedThread.getSessionId());
    assertEquals(ThreadStatus.RUNNING.name(), nestedThread.getStatus());
    List<HarnessThreadInputDO> nestedInputs =
        inputMapper.listByThread(nested.task().childThreadId());
    assertEquals(1, nestedInputs.size());
    assertEquals("subagent:" + NESTED_INVOCATION_ID, nestedInputs.get(0).getClientMessageId());
    assertTrue(nestedInputs.get(0).getPayloadJson().contains("nested prompt"));
    verify(threadKick).kick(first.task().childThreadId());
    verify(threadKick).kick(nested.task().childThreadId());
  }

  /** Parent snapshot 是权限和上限的唯一入口；拒绝时不创建 child session 或发送 kick。 */
  @Test
  void rejectsSubagentThatIsNotAllowedByParentSnapshot() {
    LocalDateTime timestamp = LocalDateTime.now(ZoneOffset.UTC);
    insertTargetAgent();
    insertRootSession(timestamp, List.of("another-agent"), "{}", 0);
    insertThread(ROOT_THREAD_ID, ROOT_SESSION_ID, ROOT_ENTRY_ID, timestamp);
    invocationMapper.insert(
        invocation(ROOT_INVOCATION_ID, ROOT_THREAD_ID, ROOT_ENTRY_ID, timestamp));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                taskRuntime.startOrResume(
                    new ToolExecutionContext(ROOT_INVOCATION_ID, ROOT_THREAD_ID),
                    command("rejected"),
                    timestamp.toInstant(ZoneOffset.UTC)));

    assertTrue(error.getMessage().contains("subagent is not allowed"));
    assertEquals(1, jdbc.queryForObject("select count(*) from harness_session", Integer.class));
    verifyNoInteractions(threadKick);
  }

  /** 允许清单命中但 definition 被删除时，任务创建必须失败且没有半成品 child。 */
  @Test
  void rejectsUnknownSubagentDefinition() {
    LocalDateTime timestamp = LocalDateTime.now(ZoneOffset.UTC);
    insertRootSession(timestamp, List.of(SUBAGENT), "{}", 0);
    insertThread(ROOT_THREAD_ID, ROOT_SESSION_ID, ROOT_ENTRY_ID, timestamp);
    invocationMapper.insert(
        invocation(ROOT_INVOCATION_ID, ROOT_THREAD_ID, ROOT_ENTRY_ID, timestamp));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                taskRuntime.startOrResume(
                    new ToolExecutionContext(ROOT_INVOCATION_ID, ROOT_THREAD_ID),
                    command("unknown"),
                    timestamp.toInstant(ZoneOffset.UTC)));

    assertTrue(error.getMessage().contains("unknown subagent"));
    assertEquals(1, jdbc.queryForObject("select count(*) from harness_session", Integer.class));
    verifyNoInteractions(threadKick);
  }

  @Test
  void rejectsTaskAtMaximumDepth() {
    LocalDateTime timestamp = LocalDateTime.now(ZoneOffset.UTC);
    insertTargetAgent();
    insertRootSession(timestamp, List.of(SUBAGENT), "{\"maxDepth\":1}", 1);
    insertThread(ROOT_THREAD_ID, ROOT_SESSION_ID, ROOT_ENTRY_ID, timestamp);
    invocationMapper.insert(
        invocation(ROOT_INVOCATION_ID, ROOT_THREAD_ID, ROOT_ENTRY_ID, timestamp));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                taskRuntime.startOrResume(
                    new ToolExecutionContext(ROOT_INVOCATION_ID, ROOT_THREAD_ID),
                    command("too deep"),
                    timestamp.toInstant(ZoneOffset.UTC)));

    assertTrue(error.getMessage().contains("maximum depth"));
    verifyNoInteractions(threadKick);
  }

  @Test
  void rejectsTaskWhenDirectOrRootConcurrencyLimitIsReached() {
    LocalDateTime timestamp = LocalDateTime.now(ZoneOffset.UTC);
    insertTargetAgent();
    insertRootSession(timestamp, List.of(SUBAGENT), "{\"maxDirectSubagents\":1}", 0);
    insertThread(ROOT_THREAD_ID, ROOT_SESSION_ID, ROOT_ENTRY_ID, timestamp);
    invocationMapper.insert(
        invocation(ROOT_INVOCATION_ID, ROOT_THREAD_ID, ROOT_ENTRY_ID, timestamp));
    insertRunningTask(
        ROOT_INVOCATION_ID + 10,
        ROOT_SESSION_ID,
        ROOT_THREAD_ID + 10,
        ROOT_SESSION_ID + 10,
        ROOT_THREAD_ID + 20,
        timestamp);

    IllegalStateException directError =
        assertThrows(
            IllegalStateException.class,
            () ->
                taskRuntime.startOrResume(
                    new ToolExecutionContext(ROOT_INVOCATION_ID, ROOT_THREAD_ID),
                    command("too many direct"),
                    timestamp.toInstant(ZoneOffset.UTC)));
    assertTrue(directError.getMessage().contains("direct concurrency"));

    clean();
    insertTargetAgent();
    insertRootSession(
        timestamp, List.of(SUBAGENT), "{\"maxDirectSubagents\":2,\"maxTotalSubagents\":1}", 0);
    insertThread(ROOT_THREAD_ID, ROOT_SESSION_ID, ROOT_ENTRY_ID, timestamp);
    invocationMapper.insert(
        invocation(ROOT_INVOCATION_ID, ROOT_THREAD_ID, ROOT_ENTRY_ID, timestamp));
    insertChildSession(ROOT_SESSION_ID + 20, ROOT_SESSION_ID, timestamp);
    insertRunningTask(
        ROOT_INVOCATION_ID + 20,
        ROOT_SESSION_ID + 99,
        ROOT_THREAD_ID + 20,
        ROOT_SESSION_ID + 20,
        ROOT_THREAD_ID + 30,
        timestamp);

    IllegalStateException totalError =
        assertThrows(
            IllegalStateException.class,
            () ->
                taskRuntime.startOrResume(
                    new ToolExecutionContext(ROOT_INVOCATION_ID, ROOT_THREAD_ID),
                    command("too many total"),
                    timestamp.toInstant(ZoneOffset.UTC)));
    assertTrue(totalError.getMessage().contains("root concurrency"));
    verifyNoInteractions(threadKick);
  }

  private TaskCommand command(String prompt) {
    return new TaskCommand(SUBAGENT, prompt, WorkingCopyPolicy.NONE);
  }

  private void insertTargetAgent() {
    AgentDefinitionDO agent = new AgentDefinitionDO();
    agent.setId(AGENT_ID);
    agent.setName(SUBAGENT);
    agent.setDescription("test");
    agent.setSystemPrompt("test system");
    agent.setModelId(1L);
    agent.setVariant("default");
    agent.setConfigJson(
        "{\"allowedSubagents\":[\"test-subagent\"],\"executionPolicy\":{\"maxTurns\":3}}");
    agentMapper.insert(agent);
  }

  private void insertRootSession(LocalDateTime timestamp) {
    insertRootSession(timestamp, List.of(SUBAGENT), "{\"maxTurns\":3}", 0);
  }

  private void insertRootSession(
      LocalDateTime timestamp,
      List<String> allowedSubagents,
      String executionPolicyJson,
      int depth) {
    HarnessSessionDO session = new HarnessSessionDO();
    session.setId(ROOT_SESSION_ID);
    session.setTitle("root");
    session.setMainThreadId(ROOT_THREAD_ID);
    session.setRootSessionId(ROOT_SESSION_ID);
    session.setDepth(depth);
    session.setVersion(0L);
    session.setCreateTime(timestamp);
    session.setUpdateTime(timestamp);
    sessionMapper.insert(session);

    AgentSnapshot snapshot =
        new AgentSnapshot(
            "root system",
            "1",
            "default",
            List.of(),
            List.of(),
            allowedSubagents,
            executionPolicyJson);
    HarnessSessionEntryDO entry = new HarnessSessionEntryDO();
    entry.setId(ROOT_ENTRY_ID);
    entry.setSessionId(ROOT_SESSION_ID);
    entry.setEntryType(SessionEntryType.AGENT_SNAPSHOT.value());
    entry.setPayloadJson(
        new SessionEntryJsonCodec().encode(new AgentSnapshotEntryPayload(AGENT_ID, snapshot)));
    entry.setCreateTime(timestamp);
    entryMapper.insert(entry);
  }

  private void insertChildSession(long id, long rootSessionId, LocalDateTime timestamp) {
    HarnessSessionDO child = new HarnessSessionDO();
    child.setId(id);
    child.setTitle("existing child");
    child.setMainThreadId(id + 1);
    child.setParentSessionId(rootSessionId);
    child.setRootSessionId(rootSessionId);
    child.setDepth(1);
    child.setVersion(0L);
    child.setCreateTime(timestamp);
    child.setUpdateTime(timestamp);
    sessionMapper.insert(child);
  }

  private void insertRunningTask(
      long parentInvocationId,
      long parentSessionId,
      long parentThreadId,
      long childSessionId,
      long childThreadId,
      LocalDateTime timestamp) {
    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(parentInvocationId);
    task.setParentSessionId(parentSessionId);
    task.setParentThreadId(parentThreadId);
    task.setRootThreadId(ROOT_THREAD_ID);
    task.setChildSessionId(childSessionId);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent(SUBAGENT);
    task.setWorkingCopyPolicy(WorkingCopyPolicy.NONE.name());
    task.setMaxTurns(3);
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(timestamp);
    task.setUpdateTime(timestamp);
    taskMapper.insert(task);
  }

  private void insertThread(long id, long sessionId, long headEntryId, LocalDateTime timestamp) {
    HarnessThreadDO thread = new HarnessThreadDO();
    thread.setId(id);
    thread.setSessionId(sessionId);
    thread.setHeadEntryId(headEntryId);
    thread.setStatus(ThreadStatus.WAITING.name());
    thread.setInputSequence(0L);
    thread.setVersion(0L);
    thread.setCreateTime(timestamp);
    thread.setUpdateTime(timestamp);
    threadMapper.insert(thread);
  }

  private static ToolInvocationDO invocation(
      long id, long threadId, long assistantEntryId, LocalDateTime timestamp) {
    ToolInvocationDO invocation = new ToolInvocationDO();
    invocation.setId(id);
    invocation.setThreadId(threadId);
    invocation.setAssistantEntryId(assistantEntryId);
    invocation.setOrdinal(0);
    invocation.setToolCallId("task-" + id);
    invocation.setToolName("task");
    invocation.setToolVersion("1");
    invocation.setTargetType("CONTROL");
    invocation.setArgumentsJson("{}");
    invocation.setStatus(ToolInvocationStatus.RUNNING.name());
    invocation.setPermissionAction("ALLOW");
    invocation.setSideEffect("WRITE");
    invocation.setDeadlineAt(timestamp.plusHours(1));
    invocation.setCreateTime(timestamp);
    invocation.setUpdateTime(timestamp);
    return invocation;
  }
}
