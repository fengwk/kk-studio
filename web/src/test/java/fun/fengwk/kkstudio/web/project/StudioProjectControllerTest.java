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

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ProjectSession;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.platform.project.session.ProjectHarnessSessionBootstrapService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSummaryDTO;
import fun.fengwk.kkstudio.share.project.CreateProjectRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectArchiveRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectCommandRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectSnapshotDTO;
import fun.fengwk.kkstudio.share.project.ProjectUnarchiveRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateProjectRequestDTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 验证 StudioProjectController 的全部 REST 接口端点、Coordinator 对话以及 CAS 409 / 404 错误处理。 */
class StudioProjectControllerTest {

  private ProjectService projectService;
  private ProjectHarnessSessionBootstrapService bootstrapService;
  private HarnessCommandAcceptanceOrchestrator orchestrator;
  private HarnessRuntime runtime;
  private HarnessOwnerQueryService queryService;
  private ProjectSnapshotAssembler snapshotAssembler;

  private MockMvc mockMvc;
  private final ObjectMapper objectMapper = ObjectMapperHolder.getInstance();
  private final UUID projectId = UUID.randomUUID();
  private final Instant now = Instant.now();

  @BeforeEach
  void setUp() {
    projectService = mock(ProjectService.class);
    bootstrapService = mock(ProjectHarnessSessionBootstrapService.class);
    orchestrator = mock(HarnessCommandAcceptanceOrchestrator.class);
    runtime = mock(HarnessRuntime.class);
    queryService = mock(HarnessOwnerQueryService.class);
    snapshotAssembler = mock(ProjectSnapshotAssembler.class);

    StudioProjectController controller =
        new StudioProjectController(
            projectService,
            bootstrapService,
            orchestrator,
            runtime,
            queryService,
            snapshotAssembler,
            new ProjectDtoMapper());

    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new StudioProjectErrorAdvice())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
  }

  @Test
  void testListAndGetProjects() throws Exception {
    Project p =
        Project.builder()
            .id(projectId)
            .title("Title")
            .coordinatorAgentName("coord")
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(projectService.listProjects(false)).thenReturn(List.of(p));
    when(projectService.getProject(projectId)).thenReturn(p);

    mockMvc
        .perform(get("/api/projects"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value(projectId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data[0].title").value("Title"));

    mockMvc
        .perform(get("/api/projects/" + projectId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(projectId.toString().toLowerCase()));
  }

  @Test
  void testCreateProject() throws Exception {
    Project created =
        Project.builder()
            .id(projectId)
            .title("New Proj")
            .coordinatorAgentName("coord")
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(projectService.createProject(eq("New Proj"), any(), eq("coord"))).thenReturn(created);

    CreateProjectRequestDTO req =
        CreateProjectRequestDTO.builder().title("New Proj").coordinatorAgentName("coord").build();

    mockMvc
        .perform(
            post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(projectId.toString().toLowerCase()));
  }

  @Test
  void testUpdateProjectAndCasConflict() throws Exception {
    Project updated =
        Project.builder()
            .id(projectId)
            .title("Updated")
            .version(1L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(projectService.updateProject(eq(projectId), eq(0L), eq("Updated"), any(), any()))
        .thenReturn(updated);

    UpdateProjectRequestDTO req =
        UpdateProjectRequestDTO.builder().expectedVersion("0").title("Updated").build();

    mockMvc
        .perform(
            put("/api/projects/" + projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value("Updated"));

    // CAS 冲突测试
    when(projectService.updateProject(eq(projectId), eq(5L), any(), any(), any()))
        .thenThrow(new AiVersionConflictException("project", "5", "6"));

    UpdateProjectRequestDTO conflictReq =
        UpdateProjectRequestDTO.builder().expectedVersion("5").title("Conflict").build();

    mockMvc
        .perform(
            put("/api/projects/" + projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(conflictReq)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.expectedVersion").value("5"))
        .andExpect(jsonPath("$.errors.actualVersion").value("6"));
  }

  @Test
  void testDeleteProject() throws Exception {
    mockMvc
        .perform(delete("/api/projects/" + projectId + "?expectedVersion=1"))
        .andExpect(status().isNoContent());

    verify(projectService).deleteProject(projectId, 1L);
  }

  @Test
  void testArchiveAndUnarchive() throws Exception {
    Project archived =
        Project.builder()
            .id(projectId)
            .title("Archived")
            .version(2L)
            .archivedAt(now)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(projectService.archiveProject(projectId, 1L)).thenReturn(archived);
    when(projectService.unarchiveProject(projectId, 2L)).thenReturn(archived);

    mockMvc
        .perform(
            post("/api/projects/" + projectId + "/archive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ProjectArchiveRequestDTO.builder().expectedVersion("1").build())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.archivedAt").isNotEmpty());

    mockMvc
        .perform(
            post("/api/projects/" + projectId + "/unarchive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ProjectUnarchiveRequestDTO.builder().expectedVersion("2").build())))
        .andExpect(status().isOk());
  }

  @Test
  void testCommandsBootstrapWhenNoCoordinatorSession() throws Exception {
    // 测试意图：当项目尚无 session 时，自动触发 bootstrap
    when(projectService.getCoordinatorSession(projectId)).thenReturn(null);

    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID rootEntryId = UUID.randomUUID();

    ThreadState thread = mock(ThreadState.class);
    when(thread.id()).thenReturn(threadId);
    when(thread.sessionId()).thenReturn(sessionId);
    when(thread.headEntryId()).thenReturn(rootEntryId);
    when(thread.nextCommandSequence()).thenReturn(1L);

    RootPayload rootPayload = mock(RootPayload.class);
    when(rootPayload.type()).thenReturn(EntryType.ROOT);
    when(rootPayload.settings())
        .thenReturn(
            new BranchSettings("coord", new ModelSelection("provider", "model", "var"), null));

    Entry rootEntry = mock(Entry.class);
    when(rootEntry.id()).thenReturn(rootEntryId);
    when(rootEntry.sessionId()).thenReturn(sessionId);
    when(rootEntry.payload()).thenReturn(rootPayload);

    ThreadCommand tc = mock(ThreadCommand.class);
    when(tc.threadId()).thenReturn(threadId);
    when(tc.sequence()).thenReturn(1L);
    when(tc.type()).thenReturn(ThreadCommandType.USER_MESSAGE);
    when(tc.state()).thenReturn(ThreadCommandState.QUEUED);
    when(tc.idempotencyKey()).thenReturn(UUID.randomUUID());
    when(tc.payload())
        .thenReturn(
            new UserMessageCommandPayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("msg")))));
    when(tc.createdAt()).thenReturn(now);

    Session session = new Session(sessionId, "Session", now);
    AcceptedCommands accepted =
        new AcceptedCommands(session, rootEntry, thread, List.of(tc), false);
    when(bootstrapService.bootstrapProjectSession(
            eq(projectId), any(), any(), any(), eq("Hello Coordinator")))
        .thenReturn(accepted);

    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    when(snapshot.thread()).thenReturn(thread);
    EntryPath entryPath = mock(EntryPath.class);
    when(entryPath.root()).thenReturn(rootEntry);
    when(entryPath.head()).thenReturn(rootEntry);
    when(entryPath.baseSettings())
        .thenReturn(
            new BranchSettings("coord", new ModelSelection("provider", "model", "var"), null));
    when(snapshot.entryPath()).thenReturn(entryPath);
    when(runtime.getThreadSnapshot(threadId)).thenReturn(snapshot);

    UUID idemKey = UUID.randomUUID();
    ProjectCommandRequestDTO req =
        ProjectCommandRequestDTO.builder()
            .idempotencyKey(idemKey.toString())
            .message("Hello Coordinator")
            .build();

    mockMvc
        .perform(
            post("/api/projects/" + projectId + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.session.sessionId").value(sessionId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.thread.threadId").value(threadId.toString().toLowerCase()));
  }

  @Test
  void testCommandsOnExistingCoordinatorSession() throws Exception {
    // 测试意图：当项目已有 session 时，通过 acceptanceOrchestrator 发送至既有 thread
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID rootEntryId = UUID.randomUUID();
    ProjectSession ps =
        ProjectSession.builder().projectId(projectId).sessionId(sessionId).createdAt(now).build();
    when(projectService.getCoordinatorSession(projectId)).thenReturn(ps);

    HarnessThreadSummaryDTO threadSummary = new HarnessThreadSummaryDTO();
    threadSummary.setThreadId(threadId.toString().toLowerCase());
    when(queryService.listThreadSummaries(sessionId)).thenReturn(List.of(threadSummary));

    ThreadState thread = mock(ThreadState.class);
    when(thread.id()).thenReturn(threadId);
    when(thread.sessionId()).thenReturn(sessionId);
    when(thread.headEntryId()).thenReturn(rootEntryId);
    when(thread.nextCommandSequence()).thenReturn(1L);

    RootPayload rootPayload = mock(RootPayload.class);
    when(rootPayload.type()).thenReturn(EntryType.ROOT);
    when(rootPayload.settings())
        .thenReturn(
            new BranchSettings("coord", new ModelSelection("provider", "model", "var"), null));

    Entry rootEntry = mock(Entry.class);
    when(rootEntry.id()).thenReturn(rootEntryId);
    when(rootEntry.sessionId()).thenReturn(sessionId);
    when(rootEntry.payload()).thenReturn(rootPayload);

    ThreadCommand tc = mock(ThreadCommand.class);
    when(tc.threadId()).thenReturn(threadId);
    when(tc.sequence()).thenReturn(1L);
    when(tc.type()).thenReturn(ThreadCommandType.USER_MESSAGE);
    when(tc.state()).thenReturn(ThreadCommandState.QUEUED);
    when(tc.idempotencyKey()).thenReturn(UUID.randomUUID());
    when(tc.payload())
        .thenReturn(
            new UserMessageCommandPayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("msg")))));
    when(tc.createdAt()).thenReturn(now);

    Session session = new Session(sessionId, "Session", now);
    AcceptedCommands accepted =
        new AcceptedCommands(session, rootEntry, thread, List.of(tc), false);

    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    when(snapshot.thread()).thenReturn(thread);
    EntryPath entryPath = mock(EntryPath.class);
    when(entryPath.root()).thenReturn(rootEntry);
    when(entryPath.head()).thenReturn(rootEntry);
    when(entryPath.baseSettings())
        .thenReturn(
            new BranchSettings("coord", new ModelSelection("provider", "model", "var"), null));
    when(snapshot.entryPath()).thenReturn(entryPath);
    when(runtime.getThreadSnapshot(threadId)).thenReturn(snapshot);
    when(orchestrator.accept(any(OwnerRef.class), any(AcceptCommandsCommand.class)))
        .thenReturn(accepted);

    UUID idemKey = UUID.randomUUID();
    ProjectCommandRequestDTO req =
        ProjectCommandRequestDTO.builder()
            .idempotencyKey(idemKey.toString())
            .message("Follow-up question")
            .build();

    mockMvc
        .perform(
            post("/api/projects/" + projectId + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.session.sessionId").value(sessionId.toString().toLowerCase()));
  }

  @Test
  void testGetSnapshot() throws Exception {
    ProjectSnapshotDTO snapshot =
        ProjectSnapshotDTO.builder()
            .project(
                new ProjectDtoMapper().toDto(Project.builder().id(projectId).version(0L).build()))
            .issues(List.of())
            .dependencies(List.of())
            .build();
    when(snapshotAssembler.assemble(projectId)).thenReturn(snapshot);

    mockMvc
        .perform(get("/api/projects/" + projectId + "/snapshot"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.project.id").value(projectId.toString().toLowerCase()));
  }

  @Test
  void testResourceNotFoundAdvice() throws Exception {
    when(projectService.getProject(projectId))
        .thenThrow(new AiResourceNotFoundException("project"));

    mockMvc.perform(get("/api/projects/" + projectId)).andExpect(status().isNotFound());
  }
}
