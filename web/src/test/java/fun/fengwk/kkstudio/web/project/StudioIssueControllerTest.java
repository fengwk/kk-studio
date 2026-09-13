package fun.fengwk.kkstudio.web.project;

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

import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.share.project.AddIssueDependencyRequestDTO;
import fun.fengwk.kkstudio.share.project.AppendIssueInputRequestDTO;
import fun.fengwk.kkstudio.share.project.ArchiveIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.CancelIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.ChangeIssueStatusRequestDTO;
import fun.fengwk.kkstudio.share.project.CreateIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.RetryIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.ReviewIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.UnarchiveIssueRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateIssueRequestDTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 验证 StudioIssueController 的全部 REST 接口端点、人类 Review/Cancel/Retry 动作与 CAS 409 处理。 */
class StudioIssueControllerTest {

  private IssueService issueService;
  private IssueRunService issueRunService;
  private HarnessOwnerQueryService queryService;
  private ProjectInvalidationHub invalidationHub;

  private MockMvc mockMvc;
  private final ObjectMapper objectMapper = ObjectMapperHolder.getInstance();
  private final UUID projectId = UUID.randomUUID();
  private final UUID issueId = UUID.randomUUID();
  private final Instant now = Instant.now();

  @BeforeEach
  void setUp() {
    issueService = mock(IssueService.class);
    issueRunService = mock(IssueRunService.class);
    queryService = mock(HarnessOwnerQueryService.class);
    invalidationHub = mock(ProjectInvalidationHub.class);

    StudioIssueController controller =
        new StudioIssueController(
            issueService, issueRunService, queryService, new ProjectDtoMapper(), invalidationHub);

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

    verify(invalidationHub).publishChange(projectId);
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
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.getIssue(issueId)).thenReturn(issue);
    when(issueService.isBlocked(issueId)).thenReturn(false);
    when(issueService.listDependencies(issueId)).thenReturn(List.of());
    when(issueService.listInputs(issueId)).thenReturn(List.of());

    UUID runId = UUID.randomUUID();
    UUID runSessionId = UUID.randomUUID();
    IssueRun run =
        IssueRun.builder()
            .id(runId)
            .issueId(issueId)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
            .actorType(IssueRunActorType.AGENT)
            .status(IssueRunStatus.RUNNING)
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueRunService.listRuns(issueId)).thenReturn(List.of(run));
    when(issueRunService.getActiveRun(issueId)).thenReturn(run);
    when(issueRunService.getLatestRun(issueId)).thenReturn(run);

    HarnessSessionSummaryDTO sessionDto = new HarnessSessionSummaryDTO();
    sessionDto.setSessionId(runSessionId.toString().toLowerCase());
    when(queryService.listIssueRunSessions(runId)).thenReturn(List.of(sessionDto));

    mockMvc
        .perform(get("/api/issues/" + issueId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.issue.id").value(issueId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.blocked").value(false))
        .andExpect(
            jsonPath("$.data.runs[0].sessionId").value(runSessionId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.currentRun.id").value(runId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.latestRun.id").value(runId.toString().toLowerCase()));
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
        .thenThrow(new AiVersionConflictException("issue", issueId.toString(), "99", "100"));

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

    verify(invalidationHub).publishChange(projectId);
  }

  @Test
  void testDependenciesAddAndRemove() throws Exception {
    Issue issue = Issue.builder().id(issueId).projectId(projectId).version(1L).build();
    when(issueService.getIssue(issueId)).thenReturn(issue);

    UUID depId = UUID.randomUUID();
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
    verify(invalidationHub).publishChange(projectId);

    mockMvc
        .perform(delete("/api/issues/" + issueId + "/dependencies/" + depId + "?expectedVersion=1"))
        .andExpect(status().isNoContent());

    verify(issueService).removeDependency(issueId, depId, 1L);
  }

  @Test
  void testAppendInput() throws Exception {
    Issue issue = Issue.builder().id(issueId).projectId(projectId).version(1L).build();
    when(issueService.getIssue(issueId)).thenReturn(issue);

    IssueInput input =
        IssueInput.builder()
            .issueId(issueId)
            .sequence(1L)
            .kind(IssueInputKind.HUMAN)
            .body("Please check logs")
            .createdAt(now)
            .build();
    when(issueService.appendInput(
            eq(issueId), eq(IssueInputKind.HUMAN), eq("Please check logs"), any()))
        .thenReturn(input);

    AppendIssueInputRequestDTO req =
        AppendIssueInputRequestDTO.builder().kind("HUMAN").body("Please check logs").build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/inputs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.body").value("Please check logs"));

    verify(invalidationHub).publishChange(projectId);
  }

  @Test
  void testReview() throws Exception {
    Issue issue =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .specRevision(1L)
            .inputSequence(2L)
            .version(1L)
            .build();
    when(issueService.getIssue(issueId)).thenReturn(issue);

    IssueRun reviewerRun =
        IssueRun.builder()
            .id(UUID.randomUUID())
            .issueId(issueId)
            .ordinal(2L)
            .role(IssueRunRole.REVIEWER)
            .actorType(IssueRunActorType.HUMAN)
            .status(IssueRunStatus.COMPLETED)
            .outcome(IssueRunOutcome.APPROVED)
            .version(1L)
            .createdAt(now)
            .updatedAt(now)
            .build();

    when(issueRunService.reviewRun(
            eq(issueId),
            eq(null), // 人类 review 必须为 null
            eq(IssueRunActorType.HUMAN),
            eq(null), // 人类 reviewerAgentName 为 null
            any(),
            eq(1L),
            eq(2L),
            eq(ReviewDecision.APPROVE),
            eq("Looks good"),
            any()))
        .thenReturn(reviewerRun);

    ReviewIssueRequestDTO req =
        ReviewIssueRequestDTO.builder().decision("APPROVE").summary("Looks good").build();

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.role").value("REVIEWER"))
        .andExpect(jsonPath("$.data.outcome").value("APPROVED"));

    verify(invalidationHub).publishChange(projectId);
  }

  @Test
  void testCancelRetryArchiveUnarchive() throws Exception {
    Issue issue = Issue.builder().id(issueId).projectId(projectId).version(1L).build();
    when(issueService.getIssue(issueId)).thenReturn(issue);
    when(issueService.cancelIssue(eq(issueId), eq(1L), any())).thenReturn(issue);
    when(issueService.archiveIssue(issueId, 1L)).thenReturn(issue);
    when(issueService.unarchiveIssue(issueId, 1L)).thenReturn(issue);

    IssueInput retriedInput =
        IssueInput.builder()
            .issueId(issueId)
            .sequence(3L)
            .kind(IssueInputKind.HUMAN)
            .body("retry")
            .createdAt(now)
            .build();
    when(issueRunService.retryRun(eq(issueId), any())).thenReturn(retriedInput);

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
