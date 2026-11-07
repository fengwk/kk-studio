package fun.fengwk.kkstudio.web.project;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.i18n.MessageService;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.dto.AddIssueEvidenceRequestDTO;
import fun.fengwk.kkstudio.project.dto.AppendIssueActivityRequestDTO;
import fun.fengwk.kkstudio.project.dto.BlockIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.CreateIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.IssueActivityRequestKind;
import fun.fengwk.kkstudio.project.dto.ProjectDtoMapper;
import fun.fengwk.kkstudio.project.dto.RecoverIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.ReopenIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.ResetStageBudgetRequestDTO;
import fun.fengwk.kkstudio.project.dto.ResumeIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.StopIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.TransitionIssueRequestDTO;
import fun.fengwk.kkstudio.project.dto.UpdateIssueRequestDTO;
import fun.fengwk.kkstudio.project.http.IssueController;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueEvidence;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.PauseReason;
import fun.fengwk.kkstudio.project.service.IssueDetailAssembler;
import fun.fengwk.kkstudio.project.service.IssueEditingService;
import fun.fengwk.kkstudio.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.project.service.IssueLifecycleService;
import fun.fengwk.kkstudio.project.service.IssueRunService;
import fun.fengwk.kkstudio.project.service.IssueStageBudgetService;
import fun.fengwk.kkstudio.project.service.ProjectService;
import fun.fengwk.kkstudio.project.service.StageBudgetView;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.advice.ProblemAdvice;
import fun.fengwk.kkstudio.web.advice.RequestBodyRequiredValidationAdvice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 验证 IssueController 的 REST 接口与 CAS 409 处理。 */
class IssueControllerTest {

  private IssueEditingService issueEditingService;
  private IssueLifecycleService issueLifecycleService;
  private IssueStageBudgetService issueStageBudgetService;
  private IssueRunService issueRunService;
  private IssueEvidenceService issueEvidenceService;
  private ProjectService projectService;

  private MockMvc mockMvc;
  private final JsonMapper objectMapper = StrictHttpJsonMapperTestSupport.get();
  private final UUID projectId = UUID.randomUUID();
  private final UUID issueId = UUID.randomUUID();
  private final Instant now = Instant.now();
  private final ProjectWorkflowJsonCodec codec = new ProjectWorkflowJsonCodec();
  private final ProjectDtoMapper mapper =
      new ProjectDtoMapper(codec, StrictHttpJsonMapperTestSupport.get());

  @BeforeEach
  void setUp() {
    issueEditingService = mock(IssueEditingService.class);
    issueLifecycleService = mock(IssueLifecycleService.class);
    issueStageBudgetService = mock(IssueStageBudgetService.class);
    issueRunService = mock(IssueRunService.class);
    issueEvidenceService = mock(IssueEvidenceService.class);
    projectService = mock(ProjectService.class);

    // ProjectDtoMapper 的依赖在测试 fixture 显式提供，与生产注入同一 Jackson 3 mapper 实例。
    IssueDetailAssembler issueDetailAssembler =
        new IssueDetailAssembler(
            issueEditingService,
            issueStageBudgetService,
            issueRunService,
            projectService,
            mapper,
            codec);
    IssueController controller =
        new IssueController(
            issueEditingService,
            issueLifecycleService,
            issueStageBudgetService,
            issueEvidenceService,
            mapper,
            issueDetailAssembler);

    JsonMapper strictHttp = StrictHttpJsonMapperTestSupport.strictHttp();
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new RequestBodyRequiredValidationAdvice(strictHttp),
                new ProblemAdvice(new MessageService()))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(strictHttp))
            .build();
  }

  @Test
  void testCreateIssue() throws Exception {
    Issue created =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Task")
            .description("Desc")
            .state("INIT")
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueEditingService.createIssue(projectId, "Task", "Desc")).thenReturn(created);

    CreateIssueRequestDTO req =
        CreateIssueRequestDTO.builder().title("Task").description("Desc").build();

    mockMvc
        .perform(
            post("/api/project/projects/" + projectId + "/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(issueId.toString().toLowerCase()))
        .andExpect(jsonPath("$.number").value("1"))
        .andExpect(jsonPath("$.state").value("INIT"));
  }

  @Test
  void testGetIssueDetail() throws Exception {
    Issue issue =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Task")
            .state("WORK")
            .version(1L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueEditingService.getIssue(issueId)).thenReturn(issue);
    when(issueEditingService.listActivities(issueId, 0L, 50)).thenReturn(List.of());
    UUID threadId = UUID.randomUUID();
    when(issueEditingService.listAgentThreads(issueId))
        .thenReturn(List.of(new IssueAgentThread(issueId, "coder", UUID.randomUUID(), threadId)));

    IssueRun run =
        IssueRun.builder()
            .id(UUID.randomUUID())
            .issueId(issueId)
            .ordinal(1L)
            .state("WORK")
            .threadId(threadId)
            .status(IssueRunStatus.RUNNING)
            .version(0L)
            .startedAt(now)
            .build();
    when(issueRunService.listRuns(issueId)).thenReturn(List.of(run));

    mockMvc
        .perform(get("/api/project/issues/" + issueId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.issue.id").value(issueId.toString().toLowerCase()))
        .andExpect(jsonPath("$.currentRun.agentName").value("coder"))
        .andExpect(jsonPath("$.agentThreads[0].agentName").value("coder"));
  }

  /** 无 Run 的 Issue 详情仍可读取，缺失的 Run 投影保持 null，不调用精确查询接口。 */
  @Test
  void testGetIssueDetailWithoutRun() throws Exception {
    Issue issue =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Task")
            .state("INIT")
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueEditingService.getIssue(issueId)).thenReturn(issue);
    when(issueEditingService.listActivities(issueId, 0L, 50)).thenReturn(List.of());
    when(issueEditingService.listAgentThreads(issueId)).thenReturn(List.of());
    when(issueRunService.listRuns(issueId)).thenReturn(List.of());

    mockMvc
        .perform(get("/api/project/issues/" + issueId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.issue.id").value(issueId.toString()))
        .andExpect(jsonPath("$.runs").isEmpty())
        .andExpect(jsonPath("$.currentRun").value(nullValue()))
        .andExpect(jsonPath("$.latestRun").value(nullValue()));
  }

  @Test
  void issueDetailIncludesActivityWindow() throws Exception {
    Issue issue =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Task")
            .state("INIT")
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    IssueActivity act =
        IssueActivity.builder()
            .issueId(issueId)
            .sequence(1L)
            .kind(IssueActivityKind.COMMENT)
            .actorType(IssueActivityActorType.HUMAN)
            .body("Hello")
            .createdAt(now)
            .build();
    when(issueEditingService.getIssue(issueId)).thenReturn(issue);
    when(issueEditingService.listActivities(issueId, 0L, 1)).thenReturn(List.of(act));

    mockMvc
        .perform(get("/api/project/issues/" + issueId).param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activities[0].sequence").value("1"))
        .andExpect(jsonPath("$.activities[0].body").value("Hello"))
        .andExpect(jsonPath("$.nextActivityCursor").value("1"));
    mockMvc
        .perform(get("/api/project/issues/" + issueId + "/activities"))
        .andExpect(status().isMethodNotAllowed());
  }

  /** 非规范 expectedVersion 必须在真实 HTTP mapper 处拒绝，不进入领域服务。 */
  @Test
  void rejectsNonCanonicalExpectedVersion() throws Exception {
    mockMvc
        .perform(
            put("/api/project/issues/" + issueId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\" 0\",\"title\":\"T\",\"description\":null}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    verifyNoInteractions(issueEditingService);
  }

  @Test
  void testUpdateIssue() throws Exception {
    UpdateIssueRequestDTO req =
        UpdateIssueRequestDTO.builder()
            .expectedVersion(1L)
            .title("New Title")
            .description("New Desc")
            .build();
    Issue updated =
        Issue.builder().id(issueId).version(2L).title("New Title").description("New Desc").build();
    when(issueEditingService.updateIssue(issueId, 1L, "New Title", "New Desc")).thenReturn(updated);

    mockMvc
        .perform(
            put("/api/project/issues/" + issueId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("2"))
        .andExpect(jsonPath("$.title").value("New Title"));
  }

  @Test
  void testTransition() throws Exception {
    TransitionIssueRequestDTO req =
        TransitionIssueRequestDTO.builder()
            .expectedVersion(1L)
            .requestKey("k1")
            .toState("DONE")
            .build();
    Issue updated = Issue.builder().id(issueId).version(2L).state("DONE").build();
    when(issueLifecycleService.transition(issueId, 1L, "k1", "DONE")).thenReturn(updated);

    mockMvc
        .perform(
            post("/api/project/issues/" + issueId + "/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("DONE"));
  }

  @Test
  void testBlock() throws Exception {
    BlockIssueRequestDTO req =
        BlockIssueRequestDTO.builder()
            .expectedVersion(1L)
            .requestKey("k1")
            .reason("need review")
            .build();
    Issue blocked =
        Issue.builder()
            .id(issueId)
            .version(2L)
            .state("BLOCKED")
            .blockedFromState("WORK")
            .blockReason("need review")
            .build();
    when(issueLifecycleService.blockIssue(issueId, 1L, "k1", "need review")).thenReturn(blocked);

    mockMvc
        .perform(
            post("/api/project/issues/" + issueId + "/block")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("BLOCKED"))
        .andExpect(jsonPath("$.blockReason").value("need review"));
  }

  @Test
  void testRecover() throws Exception {
    RecoverIssueRequestDTO req =
        RecoverIssueRequestDTO.builder().expectedVersion(1L).requestKey("k1").build();
    Issue recovered = Issue.builder().id(issueId).version(2L).state("WORK").build();
    when(issueLifecycleService.recoverIssue(issueId, 1L, "k1")).thenReturn(recovered);

    mockMvc
        .perform(
            post("/api/project/issues/" + issueId + "/recover")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WORK"));
  }

  @Test
  void testResume() throws Exception {
    ResumeIssueRequestDTO req =
        ResumeIssueRequestDTO.builder().expectedVersion(1L).requestKey("k1").build();
    Issue resumed = Issue.builder().id(issueId).version(2L).build();
    when(issueLifecycleService.resumeIssue(issueId, 1L, "k1")).thenReturn(resumed);

    mockMvc
        .perform(
            post("/api/project/issues/" + issueId + "/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("2"));
  }

  @Test
  void testReopen() throws Exception {
    ReopenIssueRequestDTO req =
        ReopenIssueRequestDTO.builder().expectedVersion(1L).requestKey("k1").build();
    Issue reopened = Issue.builder().id(issueId).version(2L).state("INIT").build();
    when(issueLifecycleService.reopen(issueId, 1L, "k1")).thenReturn(reopened);

    mockMvc
        .perform(
            post("/api/project/issues/" + issueId + "/reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("INIT"));
  }

  @Test
  void testBudgetReset() throws Exception {
    ResetStageBudgetRequestDTO req =
        ResetStageBudgetRequestDTO.builder()
            .expectedVersion(1L)
            .requestKey("k1")
            .state("WORK")
            .maxRuns(10)
            .build();
    StageBudgetView view = new StageBudgetView("WORK", 10, 5L, 0L, 10L);
    when(issueStageBudgetService.resetStageBudget(issueId, 1L, "k1", "WORK", 10)).thenReturn(view);

    mockMvc
        .perform(
            post("/api/project/issues/" + issueId + "/reset-budget")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WORK"))
        .andExpect(jsonPath("$.maxRuns").value(10))
        .andExpect(jsonPath("$.remainingRuns").value("10"));
  }

  @Test
  void testAppendActivityComment() throws Exception {
    AppendIssueActivityRequestDTO req =
        AppendIssueActivityRequestDTO.builder()
            .expectedVersion(1L)
            .requestKey("k1")
            .kind(IssueActivityRequestKind.COMMENT)
            .body("A human comment")
            .build();
    IssueActivity act =
        IssueActivity.builder()
            .issueId(issueId)
            .sequence(1L)
            .kind(IssueActivityKind.COMMENT)
            .actorType(IssueActivityActorType.HUMAN)
            .body("A human comment")
            .idempotencyKey("comment:k1")
            .createdAt(now)
            .build();
    when(issueEditingService.appendActivity(
            issueId, 1L, IssueActivityKind.COMMENT, "k1", "A human comment"))
        .thenReturn(act);

    mockMvc
        .perform(
            post("/api/project/issues/" + issueId + "/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.body").value("A human comment"))
        .andExpect(jsonPath("$.kind").value("COMMENT"));

    verify(issueEditingService)
        .appendActivity(issueId, 1L, IssueActivityKind.COMMENT, "k1", "A human comment");
  }

  @Test
  void testAppendActivityInstruction() throws Exception {
    AppendIssueActivityRequestDTO req =
        AppendIssueActivityRequestDTO.builder()
            .expectedVersion(1L)
            .requestKey("k2")
            .kind(IssueActivityRequestKind.INSTRUCTION)
            .body("A human instruction")
            .build();
    IssueActivity act =
        IssueActivity.builder()
            .issueId(issueId)
            .sequence(2L)
            .kind(IssueActivityKind.INSTRUCTION)
            .actorType(IssueActivityActorType.HUMAN)
            .body("A human instruction")
            .idempotencyKey("instruction:k2")
            .createdAt(now)
            .build();
    when(issueEditingService.appendActivity(
            issueId, 1L, IssueActivityKind.INSTRUCTION, "k2", "A human instruction"))
        .thenReturn(act);

    mockMvc
        .perform(
            post("/api/project/issues/" + issueId + "/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.body").value("A human instruction"))
        .andExpect(jsonPath("$.kind").value("INSTRUCTION"));

    verify(issueEditingService)
        .appendActivity(issueId, 1L, IssueActivityKind.INSTRUCTION, "k2", "A human instruction");
  }

  @Test
  void testAppendActivityRejectsNonHumanKind() throws Exception {
    mockMvc
        .perform(
            post("/api/project/issues/" + issueId + "/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedVersion\":\"1\",\"requestKey\":\"k1\","
                        + "\"kind\":\"RUN\",\"body\":\"system only\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));

    verifyNoInteractions(issueEditingService);
  }

  @Test
  void testAppendActivityReplayConflictReturns409() throws Exception {
    AppendIssueActivityRequestDTO req =
        AppendIssueActivityRequestDTO.builder()
            .expectedVersion(1L)
            .requestKey("k1")
            .kind(IssueActivityRequestKind.COMMENT)
            .body("Different content reusing key")
            .build();
    when(issueEditingService.appendActivity(
            issueId, 1L, IssueActivityKind.COMMENT, "k1", "Different content reusing key"))
        .thenThrow(
            DomainException.duplicate(
                "issue_activity", "Request key was reused with a different request payload"));

    mockMvc
        .perform(
            post("/api/project/issues/" + issueId + "/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isConflict());
  }

  @Test
  void testGetIssueInvalidLimitReturns400() throws Exception {
    mockMvc
        .perform(get("/api/project/issues/" + issueId).param("limit", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("limit has an invalid value"));
    mockMvc
        .perform(get("/api/project/issues/" + issueId).param("limit", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("limit must be within [1, 200]"));
    mockMvc
        .perform(get("/api/project/issues/" + issueId).param("limit", "201"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void testAddEvidence() throws Exception {
    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    AddIssueEvidenceRequestDTO req =
        AddIssueEvidenceRequestDTO.builder().uploadId(uploadId).build();
    IssueEvidence evidence =
        IssueEvidence.builder()
            .issueId(issueId)
            .blobId(blobId)
            .name("upload.png")
            .createdAt(now)
            .build();
    when(issueEvidenceService.publishHumanUpload(issueId, uploadId)).thenReturn(evidence);

    mockMvc
        .perform(
            post("/api/project/issues/" + issueId + "/evidence")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.blobId").value(blobId.toString().toLowerCase()))
        .andExpect(jsonPath("$.name").value("upload.png"));
  }

  /** 刷新详情后仍能读取持久证据，而不是只显示本页刚上传的内容。 */
  @Test
  void listsPersistedEvidenceForIssue() throws Exception {
    UUID blobId = UUID.randomUUID();
    when(issueEvidenceService.listEvidence(issueId))
        .thenReturn(
            List.of(
                IssueEvidence.builder()
                    .issueId(issueId)
                    .blobId(blobId)
                    .name("deliverable.txt")
                    .createdAt(now)
                    .build()));
    mockMvc
        .perform(get("/api/project/issues/" + issueId + "/evidence"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].blobId").value(blobId.toString()))
        .andExpect(jsonPath("$[0].name").value("deliverable.txt"));
    verify(issueEvidenceService).listEvidence(issueId);
  }

  @Test
  void testStopIssue() throws Exception {
    StopIssueRequestDTO req =
        StopIssueRequestDTO.builder()
            .expectedVersion(1L)
            .requestKey("stop-1")
            .detail("Manual stop")
            .build();
    Issue stopped =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Task")
            .description("Desc")
            .state("INIT")
            .pauseReason(PauseReason.USER.name())
            .pauseDetail("Manual stop")
            .version(2L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueLifecycleService.stopIssue(issueId, 1L, "stop-1", "Manual stop")).thenReturn(stopped);

    mockMvc
        .perform(
            post("/api/project/issues/" + issueId + "/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pauseReason").value("USER"))
        .andExpect(jsonPath("$.pauseDetail").value("Manual stop"));

    verify(issueLifecycleService).stopIssue(issueId, 1L, "stop-1", "Manual stop");
  }

  @Test
  void testDeleteIssue() throws Exception {
    mockMvc
        .perform(delete("/api/project/issues/" + issueId).param("expectedVersion", "3"))
        .andExpect(status().isNoContent());

    verify(issueLifecycleService).deleteIssue(issueId, 3L);
  }

  @Test
  void testVersionConflict() throws Exception {
    UpdateIssueRequestDTO req =
        UpdateIssueRequestDTO.builder().expectedVersion(1L).title("T").build();
    when(issueEditingService.updateIssue(eq(issueId), eq(1L), any(), any()))
        .thenThrow(DomainException.versionConflict("issue", 1L, 2L));

    mockMvc
        .perform(
            put("/api/project/issues/" + issueId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"));
  }
}
