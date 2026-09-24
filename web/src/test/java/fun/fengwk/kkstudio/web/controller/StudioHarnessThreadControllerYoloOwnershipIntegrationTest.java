package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.SetThreadYoloCommand;
import fun.fengwk.kkstudio.platform.harness.thread.query.ModelRequestDebugService;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.platform.project.session.ProjectHarnessSessionBootstrapService;
import fun.fengwk.kkstudio.platform.project.tool.ProjectThreadOwnerResolver;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeTestFixtures;

import java.util.UUID;

/**
 * 公开 Thread YOLO 控制面在真实 PostgreSQL 归属数据上的拒绝边界。
 *
 * <p>测试意图：Issue Agent Session 的 Thread（无论是否存在活动 Run）与其同一 Session 的兄弟 Thread 的 YOLO 由 Project
 * 启动策略与内部 {@code IssueHarnessController} 对齐维护，公开 {@code PUT /api/harness/threads/{threadId}/yolo}
 * 必须以 409 拒绝且不触达 {@link HarnessRuntime}（既不改 Thread 行也不产生任何运行副作用）；非 Issue 归属的 Chat/Canvas Thread 仍走原
 * CAS 更新路径。
 *
 * <p>归属判定使用真实 Spring {@link ProjectThreadOwnerResolver} bean 与真实归属行，仅 {@link HarnessRuntime} 用 mock
 * 以便断言拒绝路径零调用。
 */
class StudioHarnessThreadControllerYoloOwnershipIntegrationTest extends WebPostgresTestSupport {

  private static final String AGENT_NAME = "default-assistant";
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String YOLO_BODY = "{\"expectedVersion\":\"0\",\"yoloEnabled\":true}";

  @Autowired private ProjectThreadOwnerResolver projectThreadOwnerResolver;
  @Autowired private ProjectService projectService;
  @Autowired private IssueService issueService;
  @Autowired private IssueRunService issueRunService;
  @Autowired private ProjectHarnessSessionBootstrapService bootstrapService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private HarnessRuntime runtime;
  private MockMvc mockMvc;

  @BeforeEach
  void setUpControllerBoundary() {
    runtime = mock(HarnessRuntime.class);
    StudioHarnessThreadController controller =
        new StudioHarnessThreadController(
            runtime, mock(ModelRequestDebugService.class), projectThreadOwnerResolver);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
  }

  /**
   * 意图：无活动 Run 的 Issue Agent 工作 Branch 与同一 Session 的兄弟 Branch 都不可经公开控制面覆盖 YOLO；拒绝发生在任何 runtime
   * 变更之前，Thread 行的 yolo_enabled 保持未改动。
   */
  @Test
  void rejectsYoloToggleOnIdleIssueAgentBranchAndItsSiblingThreadWithoutTouchingRuntime()
      throws Exception {
    IssueAgentBranch branch = bootstrapIdleIssueAgentBranch();
    UUID siblingThreadId = insertSiblingThreadOfSameAgentSession(branch.sessionId());

    mockMvc
        .perform(
            put("/api/harness/threads/" + branch.threadId() + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(YOLO_BODY))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            put("/api/harness/threads/" + siblingThreadId + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(YOLO_BODY))
        .andExpect(status().isConflict());

    // 归属判定完全基于持久化归属：没有 Run 也必须拒绝，且公开 API 不得以任何方式触达 Runtime。
    verifyNoInteractions(runtime);
    assertFalse(threadYoloEnabled(branch.threadId()), "Issue Agent 工作 Branch 的 YOLO 不得被覆盖");
    assertFalse(threadYoloEnabled(siblingThreadId), "兄弟 Branch 的 YOLO 不得被覆盖");
  }

  /** 意图：存在活动 EXECUTOR Run 时同一个 Session 的兄弟 Branch 同样不可覆盖 YOLO（拒绝不依赖 Run 状态）。 */
  @Test
  void rejectsYoloToggleOnSiblingThreadWhileExecutorRunIsActive() throws Exception {
    IssueAgentBranch branch = bootstrapIdleIssueAgentBranch();
    UUID siblingThreadId = insertSiblingThreadOfSameAgentSession(branch.sessionId());
    issueRunService.startExecutorRun(branch.issueId(), AGENT_NAME, null, 3);

    mockMvc
        .perform(
            put("/api/harness/threads/" + siblingThreadId + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(YOLO_BODY))
        .andExpect(status().isConflict());

    verifyNoInteractions(runtime);
    assertFalse(threadYoloEnabled(siblingThreadId), "兄弟 Branch 的 YOLO 不得被覆盖");
  }

  /** 意图：非 Issue 归属的普通 Thread（其 Session 没有任何 owner relation）仍走原有 CAS 更新路径，并返回权威 Thread。 */
  @Test
  void allowsYoloToggleOnThreadWithoutIssueAgentOwnership() throws Exception {
    UUID threadId = insertStandaloneThread();
    when(runtime.setThreadYolo(any(SetThreadYoloCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.thread(threadId, UUID.randomUUID()));
    when(runtime.getThreadSnapshot(threadId))
        .thenReturn(HarnessRuntimeTestFixtures.idleSnapshot(threadId));

    mockMvc
        .perform(
            put("/api/harness/threads/" + threadId + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(YOLO_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.threadId").value(threadId.toString()));

    ArgumentCaptor<SetThreadYoloCommand> captor =
        ArgumentCaptor.forClass(SetThreadYoloCommand.class);
    verify(runtime).setThreadYolo(captor.capture());
    assertEquals(threadId, captor.getValue().threadId());
    assertTrue(captor.getValue().enabled());
  }

  /** 建立 Project(yoloEnabled=false) + Issue + IssueAgentSession 的真实归属，且不启动任何 Run（idle）。 */
  private IssueAgentBranch bootstrapIdleIssueAgentBranch() {
    Project project = projectService.createProject("Yolo Guard Project", "Desc", false, 0);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Yolo Guard Issue", "Desc", AGENT_NAME, null, IssueStatus.TODO);
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    bootstrapService.bootstrapIssueAgentSession(
        issue.getId(), AGENT_NAME, sessionId, threadId, UUID.randomUUID(), "Execute issue task");
    return new IssueAgentBranch(issue.getId(), sessionId, threadId);
  }

  /** 插入同一 Issue Agent Session 的兄弟 Thread（fork 分支）：无直接 owner relation，只能靠 Session 归属解析。 */
  private UUID insertSiblingThreadOfSameAgentSession(UUID sessionId) {
    UUID threadId = UUID.randomUUID();
    insertThread(sessionId, threadId, "branch-sibling");
    return threadId;
  }

  /** 插入与任何 Issue Agent Session 都无关的普通 Thread（模拟 Chat/Canvas 归属）。 */
  private UUID insertStandaloneThread() {
    UUID sessionId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)",
        sessionId,
        "session-" + sessionId);
    UUID rootEntryId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, null, 'ROOT', ?::jsonb, current_timestamp)",
        rootEntryId,
        sessionId,
        """
        {"settings":{"agentName":"default-assistant","model":{"providerName":"openai",\
        "modelName":"gpt-test","variant":"default"},"environmentName":null,"goal":null},\
        "subagentContext":null}
        """);
    UUID threadId = UUID.randomUUID();
    insertThread(sessionId, threadId, "standalone-branch");
    return threadId;
  }

  private void insertThread(UUID sessionId, UUID threadId, String name) {
    UUID headEntryId =
        jdbcTemplate.queryForObject(
            "select id from harness_entry where session_id = ? and entry_type = 'ROOT'",
            UUID.class,
            sessionId);
    jdbcTemplate.update(
        "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name,"
            + " yolo_enabled, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, ?, ?, false, 1, 0, current_timestamp, current_timestamp)",
        threadId,
        sessionId,
        headEntryId,
        CREATION_REQUEST_HASH,
        name);
  }

  private boolean threadYoloEnabled(UUID threadId) {
    Boolean enabled =
        jdbcTemplate.queryForObject(
            "select yolo_enabled from harness_thread where id = ?", Boolean.class, threadId);
    return Boolean.TRUE.equals(enabled);
  }

  /** 一个 Issue Agent 工作 Branch 的稳定事实：Issue、Session 与主 Thread。 */
  private record IssueAgentBranch(UUID issueId, UUID sessionId, UUID threadId) {}
}
