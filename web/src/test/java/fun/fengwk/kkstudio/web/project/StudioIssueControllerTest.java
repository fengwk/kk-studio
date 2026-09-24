package fun.fengwk.kkstudio.web.project;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.common.json.jackson.ObjectMapperHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueEvidence;
import fun.fengwk.kkstudio.platform.project.model.IssueEvidenceOrigin;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.share.project.AddIssueDependencyRequestDTO;
import fun.fengwk.kkstudio.share.project.AddIssueEvidenceRequestDTO;
import fun.fengwk.kkstudio.share.project.AppendIssueActivityRequestDTO;
import fun.fengwk.kkstudio.share.project.ArchiveIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.BlockIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.CancelIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.ChangeIssueStatusRequestDTO;
import fun.fengwk.kkstudio.share.project.CreateIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.RecoverIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.RetryIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.ReviewIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.UnarchiveIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateIssueRequestDTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 验证 StudioIssueController 的全部 REST 接口端点、人类 Review/Block/Recover/Cancel/Retry 动作与 CAS 409 处理。 */
class StudioIssueControllerTest {

  private IssueService issueService;
  private IssueRunService issueRunService;
  private IssueEvidenceService issueEvidenceService;

  private MockMvc mockMvc;
  private final ObjectMapper objectMapper = ObjectMapperHolder.getInstance();
  private final UUID projectId = UUID.randomUUID();
  private final UUID issueId = UUID.randomUUID();
  private final Instant now = Instant.now();

  @BeforeEach
  void setUp() {
    issueService = mock(IssueService.class);
    issueRunService = mock(IssueRunService.class);
    issueEvidenceService = mock(IssueEvidenceService.class);

    StudioIssueController controller =
        new StudioIssueController(
            issueService, issueRunService, issueEvidenceService, new ProjectDtoMapper());

    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new StudioProjectErrorAdvice())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
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
            .status(IssueStatus.TODO)
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.createIssue(
            eq(projectId), eq("Task"), any(), any(), any(), eq(IssueStatus.TODO)))
        .thenReturn(created);

    CreateIssueRequestDTO req =
        CreateIssueRequestDTO.builder().title("Task").initialStatus("TODO").build();

    mockMvc
        .perform(
            post("/api/projects/" + projectId + "/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(issueId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.number").value("1"));
  }

  @Test
  void testGetIssueDetail() throws Exception {
    Issue issue =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Task")
            .status(IssueStatus.IN_PROGRESS)
            .assigneeAgentName("coder")
            .reviewerAgentName("reviewer")
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.getIssue(issueId)).thenReturn(issue);
    when(issueService.isBlocked(issueId)).thenReturn(false);
    when(issueService.listDependencies(issueId)).thenReturn(List.of());
    when(issueService.listActivitiesPage(issueId, 0L, 50)).thenReturn(List.of());

    UUID runId = UUID.randomUUID();
    UUID runSessionId = UUID.randomUUID();
    IssueRun run =
        IssueRun.builder()
            .id(runId)
            .issueId(issueId)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder")
            .status(IssueRunStatus.RUNNING)
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueRunService.listRuns(issueId)).thenReturn(List.of(run));
    when(issueRunService.getActiveRun(issueId)).thenReturn(run);
    when(issueRunService.getLatestRun(issueId)).thenReturn(run);

    IssueAgentSession coderSession =
        IssueAgentSession.builder()
            .id(UUID.randomUUID())
            .issueId(issueId)
            .agentName("coder")
            .sessionId(runSessionId)
            .threadId(UUID.randomUUID())
            .createdAt(now)
            .build();
    IssueAgentSession reviewerSession =
        IssueAgentSession.builder()
            .id(UUID.randomUUID())
            .issueId(issueId)
            .agentName("reviewer")
            .sessionId(UUID.randomUUID())
            .threadId(UUID.randomUUID())
            .createdAt(now)
            .build();
    when(issueRunService.getAgentSession(issueId, "coder")).thenReturn(coderSession);
    when(issueRunService.getAgentSession(issueId, "reviewer")).thenReturn(reviewerSession);

    // 已发布证据以规范 URI + 元数据暴露；URI 只是资源标识，读取仍由平台按已发布证据授予的 Session 引用决定
    UUID evidenceBlobId = UUID.randomUUID();
    when(issueEvidenceService.listEvidence(issueId))
        .thenReturn(
            List.of(
                IssueEvidence.builder()
                    .issueId(issueId)
                    .blobId(evidenceBlobId)
                    .origin(IssueEvidenceOrigin.EXECUTOR)
                    .runId(runId)
                    .createdAt(now)
                    .build()));

    mockMvc
        .perform(get("/api/issues/" + issueId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.issue.id").value(issueId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.blocked").value(false))
        .andExpect(jsonPath("$.data.sessions[0].agentName").value("coder"))
        .andExpect(jsonPath("$.data.sessions[0].role").value("EXECUTOR"))
        .andExpect(jsonPath("$.data.sessions[1].agentName").value("reviewer"))
        .andExpect(jsonPath("$.data.sessions[1].role").value("REVIEWER"))
        .andExpect(
            jsonPath("$.data.runs[0].sessionId").value(runSessionId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.currentRun.id").value(runId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.latestRun.id").value(runId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.evidence[0].blobId").value(evidenceBlobId.toString()))
        .andExpect(
            jsonPath("$.data.evidence[0].uri").value("kkstudio:/resources/" + evidenceBlobId))
        .andExpect(jsonPath("$.data.evidence[0].origin").value("EXECUTOR"))
        .andExpect(jsonPath("$.data.evidence[0].name").isEmpty())
        .andExpect(jsonPath("$.data.nextActivityCursor").isEmpty());
  }

  /** 人工上传转为公开证据：请求只携带 uploadId，响应给出规范 URI，绝不回显对象 key 或字节。 */
  @Test
  void testAddIssueEvidence() throws Exception {
    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    when(issueEvidenceService.publishHumanUpload(issueId, uploadId))
        .thenReturn(
            IssueEvidence.builder()
                .issueId(issueId)
                .blobId(blobId)
                .origin(IssueEvidenceOrigin.HUMAN)
                .name("report.pdf")
                .createdAt(now)
                .build());

    AddIssueEvidenceRequestDTO req =
        AddIssueEvidenceRequestDTO.builder().uploadId(uploadId.toString()).build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/evidence")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.blobId").value(blobId.toString()))
        .andExpect(jsonPath("$.data.uri").value("kkstudio:/resources/" + blobId))
        .andExpect(jsonPath("$.data.origin").value("HUMAN"))
        .andExpect(jsonPath("$.data.name").value("report.pdf"))
        .andExpect(jsonPath("$.data.runId").isEmpty());
  }

  @Test
  void testGetIssueDetailWithNextActivityCursor() throws Exception {
    Issue issue =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Task")
            .status(IssueStatus.TODO)
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.getIssue(issueId)).thenReturn(issue);
    when(issueService.isBlocked(issueId)).thenReturn(false);
    when(issueService.listDependencies(issueId)).thenReturn(List.of());
    when(issueRunService.listRuns(issueId)).thenReturn(List.of());
    when(issueRunService.getActiveRun(issueId)).thenReturn(null);
    when(issueRunService.getLatestRun(issueId)).thenReturn(null);

    IssueActivity act1 =
        IssueActivity.builder()
            .issueId(issueId)
            .sequence(1L)
            .kind(IssueActivityKind.COMMENT)
            .createdAt(now)
            .build();
    IssueActivity act2 =
        IssueActivity.builder()
            .issueId(issueId)
            .sequence(2L)
            .kind(IssueActivityKind.COMMENT)
            .createdAt(now)
            .build();
    IssueActivity act3 =
        IssueActivity.builder()
            .issueId(issueId)
            .sequence(3L)
            .kind(IssueActivityKind.COMMENT)
            .createdAt(now)
            .build();

    // limit=2, 返回 2 条
    when(issueService.listActivitiesPage(issueId, 0L, 2)).thenReturn(List.of(act1, act2));
    // 探测之后是否有更多：以 lastSequence=2，limit=1 探测，返回第 3 条
    when(issueService.listActivitiesPage(issueId, 2L, 1)).thenReturn(List.of(act3));

    mockMvc
        .perform(get("/api/issues/" + issueId + "?limit=2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.activities.length()").value(2))
        .andExpect(jsonPath("$.data.nextActivityCursor").value("2"));
  }

  @Test
  void testActivityLimitValidation() throws Exception {
    mockMvc
        .perform(get("/api/issues/" + issueId + "?limit=0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("limit must be between 1 and 200"));

    mockMvc
        .perform(get("/api/issues/" + issueId + "?limit=201"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("limit must be between 1 and 200"));

    mockMvc
        .perform(get("/api/issues/" + issueId + "/activities?limit=0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("limit must be between 1 and 200"));

    mockMvc
        .perform(get("/api/issues/" + issueId + "/activities?limit=201"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("limit must be between 1 and 200"));
  }

  @Test
  void testListActivities() throws Exception {
    IssueActivity act =
        IssueActivity.builder()
            .issueId(issueId)
            .sequence(11L)
            .kind(IssueActivityKind.COMMENT)
            .actorType(IssueActivityActorType.HUMAN)
            .body("Activity list item")
            .createdAt(now)
            .build();
    when(issueService.listActivitiesPage(issueId, 10L, 20)).thenReturn(List.of(act));

    mockMvc
        .perform(get("/api/issues/" + issueId + "/activities?afterSequence=10&limit=20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].sequence").value("11"))
        .andExpect(jsonPath("$.data[0].kind").value("COMMENT"))
        .andExpect(jsonPath("$.data[0].body").value("Activity list item"));
  }

  @Test
  void testUpdateIssueAndCasConflict() throws Exception {
    Issue updated =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Updated Task")
            .version(2L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.updateIssue(eq(issueId), eq(1L), eq("Updated Task"), any(), any(), any()))
        .thenReturn(updated);

    UpdateIssueRequestDTO req =
        UpdateIssueRequestDTO.builder().expectedVersion("1").title("Updated Task").build();

    mockMvc
        .perform(
            put("/api/issues/" + issueId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value("Updated Task"));

    // CAS 409
    when(issueService.updateIssue(eq(issueId), eq(99L), any(), any(), any(), any()))
        .thenThrow(new AiVersionConflictException("issue", "99", "100"));

    mockMvc
        .perform(
            put("/api/issues/" + issueId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        UpdateIssueRequestDTO.builder().expectedVersion("99").title("x").build())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.expectedVersion").value("99"))
        .andExpect(jsonPath("$.errors.actualVersion").value("100"));
  }

  @Test
  void testChangeStatus() throws Exception {
    Issue updated =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .status(IssueStatus.DONE)
            .version(3L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.setStatus(issueId, 2L, IssueStatus.DONE)).thenReturn(updated);

    ChangeIssueStatusRequestDTO req =
        ChangeIssueStatusRequestDTO.builder().expectedVersion("2").status("DONE").build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("DONE"));
  }

  @Test
  void testBlockIssue() throws Exception {
    Issue blocked =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .status(IssueStatus.BLOCKED)
            .version(2L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.blockIssue(issueId, 1L, "Blocked reason")).thenReturn(blocked);

    BlockIssueRequestDTO req =
        BlockIssueRequestDTO.builder().expectedVersion("1").reason("Blocked reason").build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/block")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("BLOCKED"))
        .andExpect(jsonPath("$.data.version").value("2"));

    verify(issueService).blockIssue(issueId, 1L, "Blocked reason");
  }

  @Test
  void testRecoverIssue() throws Exception {
    Issue recoveredToBacklog =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .status(IssueStatus.BACKLOG)
            .version(3L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.recoverIssue(issueId, 2L, true, "Need revision"))
        .thenReturn(recoveredToBacklog);

    RecoverIssueRequestDTO req1 =
        RecoverIssueRequestDTO.builder()
            .expectedVersion("2")
            .toBacklog(true)
            .comment("Need revision")
            .build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/recover")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("BACKLOG"));

    verify(issueService).recoverIssue(issueId, 2L, true, "Need revision");

    Issue recoveredToTodo =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .status(IssueStatus.TODO)
            .version(4L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.recoverIssue(issueId, 3L, false, null)).thenReturn(recoveredToTodo);

    RecoverIssueRequestDTO req2 =
        RecoverIssueRequestDTO.builder().expectedVersion("3").toBacklog(false).build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/recover")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("TODO"));

    verify(issueService).recoverIssue(issueId, 3L, false, null);
  }

  @Test
  void rejectsInvalidEnumWithoutEchoingTheInput() throws Exception {
    // 测试意图：非法枚举输入返回固定诊断，异常链与响应均不得回显原始输入。
    ChangeIssueStatusRequestDTO request =
        ChangeIssueStatusRequestDTO.builder()
            .expectedVersion("2")
            .status("sensitive-status-value")
            .build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("status is invalid"));
  }

  @Test
  void testDependenciesAddAndRemove() throws Exception {
    UUID depId = UUID.randomUUID();
    IssueDependency dependency =
        IssueDependency.builder()
            .issueId(issueId)
            .dependsOnIssueId(depId)
            .projectId(projectId)
            .createdAt(now)
            .build();
    when(issueService.addDependency(issueId, depId, 1L)).thenReturn(dependency);
    AddIssueDependencyRequestDTO addReq =
        AddIssueDependencyRequestDTO.builder()
            .dependsOnIssueId(depId.toString())
            .expectedVersion("1")
            .build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/dependencies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(addReq)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.issueId").value(issueId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.dependsOnIssueId").value(depId.toString().toLowerCase()));

    verify(issueService).addDependency(issueId, depId, 1L);

    mockMvc
        .perform(delete("/api/issues/" + issueId + "/dependencies/" + depId + "?expectedVersion=1"))
        .andExpect(status().isNoContent());

    verify(issueService).removeDependency(issueId, depId, 1L);
  }

  @Test
  void testAppendActivityKindInferenceAndValidation() throws Exception {
    when(issueService.appendActivity(any(IssueActivity.class)))
        .thenAnswer(
            inv -> {
              IssueActivity arg = inv.getArgument(0);
              return IssueActivity.builder()
                  .issueId(arg.getIssueId())
                  .sequence(1L)
                  .kind(arg.getKind())
                  .actorType(arg.getActorType())
                  .targetRole(arg.getTargetRole())
                  .body(arg.getBody())
                  .idempotencyKey(arg.getIdempotencyKey())
                  .createdAt(now)
                  .build();
            });

    // 1. targetRole 有值，kind 省略 -> 自动推导为 INSTRUCTION
    AppendIssueActivityRequestDTO reqInstruction =
        AppendIssueActivityRequestDTO.builder()
            .body("Check line 42")
            .targetRole("EXECUTOR")
            .idempotencyKey("idem-1")
            .build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqInstruction)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.kind").value("INSTRUCTION"))
        .andExpect(jsonPath("$.data.actorType").value("HUMAN"))
        .andExpect(jsonPath("$.data.targetRole").value("EXECUTOR"))
        .andExpect(jsonPath("$.data.body").value("Check line 42"))
        .andExpect(jsonPath("$.data.idempotencyKey").value("idem-1"));

    // 2. targetRole 为空，kind 省略 -> 自动推导为 COMMENT
    AppendIssueActivityRequestDTO reqComment =
        AppendIssueActivityRequestDTO.builder().body("General note").build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqComment)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.kind").value("COMMENT"))
        .andExpect(jsonPath("$.data.actorType").value("HUMAN"))
        .andExpect(jsonPath("$.data.body").value("General note"));

    // 3. 显式指定 HUMAN_INPUT
    AppendIssueActivityRequestDTO reqHumanInput =
        AppendIssueActivityRequestDTO.builder().kind("HUMAN_INPUT").body("User answer").build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqHumanInput)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.kind").value("HUMAN_INPUT"))
        .andExpect(jsonPath("$.data.actorType").value("HUMAN"));

    // 4. 空 body 拒绝
    AppendIssueActivityRequestDTO reqBlankBody =
        AppendIssueActivityRequestDTO.builder().body("   ").build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqBlankBody)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("body must not be blank"));

    // 5. INSTRUCTION 但缺少 targetRole 拒绝
    AppendIssueActivityRequestDTO reqInstructionNoRole =
        AppendIssueActivityRequestDTO.builder().kind("INSTRUCTION").body("Do this").build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqInstructionNoRole)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("INSTRUCTION requires targetRole"));

    // 6. 不允许人写入的 kind 拒绝（如 RETRY/SYSTEM 事实）
    AppendIssueActivityRequestDTO reqInvalidKind =
        AppendIssueActivityRequestDTO.builder().kind("RETRY").body("Retry please").build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqInvalidKind)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("kind is invalid"));
  }

  @Test
  void testReview() throws Exception {
    doNothing()
        .when(issueRunService)
        .reviewByHuman(eq(issueId), eq(ReviewDecision.APPROVE), eq("Looks good"), eq("idem-rev-1"));

    ReviewIssueRequestDTO req =
        ReviewIssueRequestDTO.builder()
            .decision("APPROVE")
            .reason("Looks good")
            .idempotencyKey("idem-rev-1")
            .build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk());

    verify(issueRunService)
        .reviewByHuman(issueId, ReviewDecision.APPROVE, "Looks good", "idem-rev-1");

    // 空 decision 拒绝
    ReviewIssueRequestDTO blankDecision =
        ReviewIssueRequestDTO.builder().decision("   ").reason("test").build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(blankDecision)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("decision must not be blank"));
  }

  @Test
  void testCancelRetryArchiveUnarchive() throws Exception {
    Issue issue = Issue.builder().id(issueId).projectId(projectId).version(1L).build();
    when(issueService.getIssue(issueId)).thenReturn(issue);
    when(issueService.cancelIssue(eq(issueId), eq(1L), any())).thenReturn(issue);
    when(issueService.archiveIssue(issueId, 1L)).thenReturn(issue);
    when(issueService.unarchiveIssue(issueId, 1L)).thenReturn(issue);

    IssueActivity retriedActivity =
        IssueActivity.builder()
            .issueId(issueId)
            .sequence(3L)
            .kind(IssueActivityKind.RETRY)
            .body("retry")
            .createdAt(now)
            .build();
    when(issueRunService.retryRun(eq(issueId), any())).thenReturn(retriedActivity);

    // Cancel
    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        CancelIssueRequestDTO.builder()
                            .expectedVersion("1")
                            .reason("test")
                            .build())))
        .andExpect(status().isOk());

    // Retry
    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/retry")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RetryIssueRequestDTO.builder().idempotencyKey("k").build())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.sequence").value("3"));

    // Archive
    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/archive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ArchiveIssueRequestDTO.builder().expectedVersion("1").build())))
        .andExpect(status().isOk());

    // Unarchive
    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/unarchive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        UnarchiveIssueRequestDTO.builder().expectedVersion("1").build())))
        .andExpect(status().isOk());
  }
}
