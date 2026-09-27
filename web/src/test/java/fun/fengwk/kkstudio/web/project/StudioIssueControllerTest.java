package fun.fengwk.kkstudio.web.project;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.error.ProjectVersionConflictException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueEvidence;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.PauseReason;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.project.service.IssueRunService;
import fun.fengwk.kkstudio.project.service.IssueService;
import fun.fengwk.kkstudio.project.service.IssueService.StageBudgetView;
import fun.fengwk.kkstudio.project.service.ProjectService;
import fun.fengwk.kkstudio.share.project.AddIssueEvidenceRequestDTO;
import fun.fengwk.kkstudio.share.project.AppendIssueActivityRequestDTO;
import fun.fengwk.kkstudio.share.project.ArchiveIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.BlockIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.CreateIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.PauseIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.RecoverIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.ReopenIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.ResetStageBudgetRequestDTO;
import fun.fengwk.kkstudio.share.project.ResolveUnknownIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.ResumeIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.StopIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.TransitionIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.UnarchiveIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateIssueRequestDTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 验证 StudioIssueController 的全部 15 个 REST 接口端点与 CAS 409 处理。 */
class StudioIssueControllerTest {

  private IssueService issueService;
  private IssueRunService issueRunService;
  private IssueEvidenceService issueEvidenceService;
  private ProjectService projectService;
  private IssueActivityRepository issueActivityRepository;

  private MockMvc mockMvc;
  private final ObjectMapper objectMapper = ObjectMapperHolder.getInstance();
  private final UUID projectId = UUID.randomUUID();
  private final UUID issueId = UUID.randomUUID();
  private final Instant now = Instant.now();
  private final ProjectWorkflowJsonCodec codec = new ProjectWorkflowJsonCodec();

  @BeforeEach
  void setUp() {
    issueService = mock(IssueService.class);
    issueRunService = mock(IssueRunService.class);
    issueEvidenceService = mock(IssueEvidenceService.class);
    projectService = mock(ProjectService.class);
    issueActivityRepository = mock(IssueActivityRepository.class);

    StudioIssueController controller =
        new StudioIssueController(
            issueService,
            issueRunService,
            issueEvidenceService,
            projectService,
            new ProjectDtoMapper(),
            issueActivityRepository,
            codec);

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
            .description("Desc")
            .state("INIT")
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.createIssue(projectId, "Task", "Desc")).thenReturn(created);

    CreateIssueRequestDTO req =
        CreateIssueRequestDTO.builder().title("Task").description("Desc").build();

    mockMvc
        .perform(
            post("/api/projects/" + projectId + "/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(issueId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.number").value("1"))
        .andExpect(jsonPath("$.data.state").value("INIT"));
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
    when(issueService.getIssue(issueId)).thenReturn(issue);
    when(issueService.listActivities(issueId, 0L, 50)).thenReturn(List.of());
    UUID threadId = UUID.randomUUID();
    when(issueService.listAgentThreads(issueId))
        .thenReturn(List.of(new IssueAgentThread(issueId, "coder", threadId)));

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
        .perform(get("/api/issues/" + issueId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.issue.id").value(issueId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.currentRun.agentName").value("coder"))
        .andExpect(jsonPath("$.data.agentThreads[0].agentName").value("coder"));
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
    when(issueService.getIssue(issueId)).thenReturn(issue);
    when(issueService.listActivities(issueId, 0L, 50)).thenReturn(List.of());
    when(issueService.listAgentThreads(issueId)).thenReturn(List.of());
    when(issueRunService.listRuns(issueId)).thenReturn(List.of());

    mockMvc
        .perform(get("/api/issues/" + issueId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.issue.id").value(issueId.toString()))
        .andExpect(jsonPath("$.data.runs").isEmpty())
        .andExpect(jsonPath("$.data.currentRun").value(nullValue()))
        .andExpect(jsonPath("$.data.latestRun").value(nullValue()));
  }

  @Test
  void testListActivities() throws Exception {
    IssueActivity act =
        IssueActivity.builder()
            .issueId(issueId)
            .sequence(1L)
            .kind(IssueActivityKind.COMMENT)
            .actorType(IssueActivityActorType.HUMAN)
            .body("Hello")
            .createdAt(now)
            .build();
    when(issueService.listActivities(issueId, 0L, 50)).thenReturn(List.of(act));

    mockMvc
        .perform(get("/api/issues/" + issueId + "/activities"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].sequence").value("1"))
        .andExpect(jsonPath("$.data[0].body").value("Hello"));
  }

  @Test
  void testUpdateIssue() throws Exception {
    UpdateIssueRequestDTO req =
        UpdateIssueRequestDTO.builder()
            .expectedVersion("1")
            .title("New Title")
            .description("New Desc")
            .build();
    Issue updated =
        Issue.builder().id(issueId).version(2L).title("New Title").description("New Desc").build();
    when(issueService.updateIssue(issueId, 1L, "New Title", "New Desc")).thenReturn(updated);

    mockMvc
        .perform(
            put("/api/issues/" + issueId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("2"))
        .andExpect(jsonPath("$.data.title").value("New Title"));
  }

  @Test
  void testTransition() throws Exception {
    TransitionIssueRequestDTO req =
        TransitionIssueRequestDTO.builder()
            .expectedVersion("1")
            .requestKey("k1")
            .toState("DONE")
            .build();
    Issue updated = Issue.builder().id(issueId).version(2L).state("DONE").build();
    when(issueService.transition(issueId, 1L, "k1", "DONE")).thenReturn(updated);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("DONE"));
  }

  @Test
  void testBlock() throws Exception {
    BlockIssueRequestDTO req =
        BlockIssueRequestDTO.builder()
            .expectedVersion("1")
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
    when(issueService.blockIssue(issueId, 1L, "k1", "need review")).thenReturn(blocked);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/block")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("BLOCKED"))
        .andExpect(jsonPath("$.data.blockReason").value("need review"));
  }

  @Test
  void testRecover() throws Exception {
    RecoverIssueRequestDTO req =
        RecoverIssueRequestDTO.builder().expectedVersion("1").requestKey("k1").build();
    Issue recovered = Issue.builder().id(issueId).version(2L).state("WORK").build();
    when(issueService.recoverIssue(issueId, 1L, "k1")).thenReturn(recovered);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/recover")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("WORK"));
  }

  @Test
  void testPause() throws Exception {
    PauseIssueRequestDTO req =
        PauseIssueRequestDTO.builder()
            .expectedVersion("1")
            .requestKey("k1")
            .reason("USER")
            .detail("manual pause")
            .build();
    Issue paused =
        Issue.builder()
            .id(issueId)
            .version(2L)
            .pauseReason("USER")
            .pauseDetail("manual pause")
            .build();
    when(issueService.pauseIssue(issueId, 1L, "k1", PauseReason.USER, "manual pause"))
        .thenReturn(paused);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/pause")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pauseReason").value("USER"));
  }

  @Test
  void testResume() throws Exception {
    ResumeIssueRequestDTO req =
        ResumeIssueRequestDTO.builder().expectedVersion("1").requestKey("k1").build();
    Issue resumed = Issue.builder().id(issueId).version(2L).build();
    when(issueService.resumeIssue(issueId, 1L, "k1")).thenReturn(resumed);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("2"));
  }

  @Test
  void testReopen() throws Exception {
    ReopenIssueRequestDTO req =
        ReopenIssueRequestDTO.builder().expectedVersion("1").requestKey("k1").build();
    Issue reopened = Issue.builder().id(issueId).version(2L).state("INIT").build();
    when(issueService.reopen(issueId, 1L, "k1")).thenReturn(reopened);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("INIT"));
  }

  @Test
  void testBudgetReset() throws Exception {
    ResetStageBudgetRequestDTO req =
        ResetStageBudgetRequestDTO.builder()
            .expectedVersion("1")
            .requestKey("k1")
            .state("WORK")
            .maxRuns(10)
            .build();
    StageBudgetView view = new StageBudgetView("WORK", 10, 5L, 0L, 10L);
    when(issueService.resetStageBudget(issueId, 1L, "k1", "WORK", 10)).thenReturn(view);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/budget-reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("WORK"))
        .andExpect(jsonPath("$.data.maxRuns").value(10))
        .andExpect(jsonPath("$.data.remainingRuns").value("10"));
  }

  @Test
  void testArchive() throws Exception {
    ArchiveIssueRequestDTO req = ArchiveIssueRequestDTO.builder().expectedVersion("1").build();
    Issue archived = Issue.builder().id(issueId).version(2L).archivedAt(now).build();
    when(issueService.archiveIssue(issueId, 1L)).thenReturn(archived);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/archive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.archivedAt").isNotEmpty());
  }

  @Test
  void testUnarchive() throws Exception {
    UnarchiveIssueRequestDTO req = UnarchiveIssueRequestDTO.builder().expectedVersion("1").build();
    Issue unarchived = Issue.builder().id(issueId).version(2L).archivedAt(null).build();
    when(issueService.unarchiveIssue(issueId, 1L)).thenReturn(unarchived);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/unarchive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.archivedAt").isEmpty());
  }

  @Test
  void testAppendActivityComment() throws Exception {
    AppendIssueActivityRequestDTO req =
        AppendIssueActivityRequestDTO.builder()
            .expectedVersion("1")
            .requestKey("k1")
            .kind("COMMENT")
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
    when(issueActivityRepository.findByIdempotencyKey(issueId, "comment:k1")).thenReturn(act);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.body").value("A human comment"))
        .andExpect(jsonPath("$.data.kind").value("COMMENT"));

    verify(issueService).appendComment(issueId, 1L, "k1", "A human comment");
  }

  @Test
  void testAppendActivityInstruction() throws Exception {
    AppendIssueActivityRequestDTO req =
        AppendIssueActivityRequestDTO.builder()
            .expectedVersion("1")
            .requestKey("k2")
            .kind("INSTRUCTION")
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
    when(issueActivityRepository.findByIdempotencyKey(issueId, "instruction:k2")).thenReturn(act);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.body").value("A human instruction"))
        .andExpect(jsonPath("$.data.kind").value("INSTRUCTION"));

    verify(issueService).appendInstruction(issueId, 1L, "k2", "A human instruction");
  }

  @Test
  void testAddEvidence() throws Exception {
    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    AddIssueEvidenceRequestDTO req =
        AddIssueEvidenceRequestDTO.builder().uploadId(uploadId.toString()).build();
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
            post("/api/issues/" + issueId + "/evidence")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.blobId").value(blobId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.name").value("upload.png"));
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
        .perform(get("/api/issues/" + issueId + "/evidence"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].blobId").value(blobId.toString()))
        .andExpect(jsonPath("$.data[0].name").value("deliverable.txt"));
    verify(issueService).getIssue(issueId);
  }

  @Test
  void testStopIssue() throws Exception {
    StopIssueRequestDTO req =
        StopIssueRequestDTO.builder()
            .expectedVersion("1")
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
    when(issueService.stopIssue(issueId, 1L, "stop-1", "Manual stop")).thenReturn(stopped);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pauseReason").value("USER"))
        .andExpect(jsonPath("$.data.pauseDetail").value("Manual stop"));

    verify(issueService).stopIssue(issueId, 1L, "stop-1", "Manual stop");
  }

  @Test
  void testResolveUnknown() throws Exception {
    ResolveUnknownIssueRequestDTO req =
        ResolveUnknownIssueRequestDTO.builder()
            .expectedVersion("2")
            .requestKey("res-1")
            .verification("Verified clean")
            .build();
    Issue resolved =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Task")
            .description("Desc")
            .state("INIT")
            .pauseReason(PauseReason.USER.name())
            .pauseDetail("Verified clean")
            .version(3L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.resolveUnknown(issueId, 2L, "res-1", "Verified clean")).thenReturn(resolved);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/resolve-unknown")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pauseReason").value("USER"))
        .andExpect(jsonPath("$.data.pauseDetail").value("Verified clean"));

    verify(issueService).resolveUnknown(issueId, 2L, "res-1", "Verified clean");
  }

  @Test
  void testDeleteIssue() throws Exception {
    mockMvc
        .perform(delete("/api/issues/" + issueId).param("expectedVersion", "3"))
        .andExpect(status().isNoContent());

    verify(issueService).deleteIssue(issueId, 3L);
  }

  @Test
  void testVersionConflict() throws Exception {
    UpdateIssueRequestDTO req =
        UpdateIssueRequestDTO.builder().expectedVersion("1").title("T").build();
    when(issueService.updateIssue(eq(issueId), eq(1L), any(), any()))
        .thenThrow(new ProjectVersionConflictException("issue", "1", "2"));

    mockMvc
        .perform(
            put("/api/issues/" + issueId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PROJECT_VERSION_CONFLICT"));
  }
}
