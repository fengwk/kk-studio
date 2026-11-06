package fun.fengwk.kkstudio.web.project;

import static org.mockito.ArgumentMatchers.anyString;
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
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.dto.CreateProjectRequestDTO;
import fun.fengwk.kkstudio.project.dto.ProjectDtoMapper;
import fun.fengwk.kkstudio.project.dto.ProjectSnapshotDTO;
import fun.fengwk.kkstudio.project.dto.ProjectVersionRequestDTO;
import fun.fengwk.kkstudio.project.dto.ProjectWorkflowDTO;
import fun.fengwk.kkstudio.project.dto.ProjectWorkflowStateDTO;
import fun.fengwk.kkstudio.project.dto.UpdateProjectRequestDTO;
import fun.fengwk.kkstudio.project.dto.UpdateProjectWorkflowRequestDTO;
import fun.fengwk.kkstudio.project.dto.UpdateProjectYoloRequestDTO;
import fun.fengwk.kkstudio.project.http.ProjectController;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.service.ProjectService;
import fun.fengwk.kkstudio.project.service.ProjectSnapshotAssembler;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.advice.ProblemAdvice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 验证 ProjectController 的全部 REST 接口端点与 CAS 409 处理。 */
class ProjectControllerTest {

  private ProjectService projectService;
  private ProjectSnapshotAssembler snapshotAssembler;
  private MockMvc mockMvc;

  private final JsonMapper objectMapper = ProjectHttpJsonTestSupport.get();
  private final UUID projectId = UUID.randomUUID();
  private final Instant now = Instant.now();
  private final ProjectWorkflowJsonCodec codec = new ProjectWorkflowJsonCodec();
  // 依赖显式提供：生产 HTTP Jackson 3 mapper，与容器注入的是同一实例。
  private final ProjectDtoMapper mapper =
      new ProjectDtoMapper(codec, ProjectHttpJsonTestSupport.get());

  @BeforeEach
  void setUp() {
    projectService = mock(ProjectService.class);
    snapshotAssembler = mock(ProjectSnapshotAssembler.class);

    ProjectController controller =
        new ProjectController(projectService, snapshotAssembler, mapper, codec);

    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ProblemAdvice(new MessageService()))
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.strictHttp()))
            .build();
  }

  @Test
  void testListProjects() throws Exception {
    Project p1 = Project.builder().id(UUID.randomUUID()).title("P1").version(0L).build();
    when(projectService.listProjects(false)).thenReturn(List.of(p1));

    mockMvc
        .perform(get("/api/project/projects"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").value("P1"));
  }

  @Test
  void testCreateProjectDefaultSettings() throws Exception {
    CreateProjectRequestDTO req =
        CreateProjectRequestDTO.builder().title("New Project").description("Desc").build();
    Project created =
        Project.builder()
            .id(projectId)
            .title("New Project")
            .description("Desc")
            .yoloEnabled(true)
            .version(0L)
            .build();
    when(projectService.createProject("New Project", "Desc", true)).thenReturn(created);

    mockMvc
        .perform(
            post("/api/project/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(projectId.toString().toLowerCase()))
        .andExpect(jsonPath("$.title").value("New Project"))
        .andExpect(jsonPath("$.yoloEnabled").value(true));

    verify(projectService).createProject("New Project", "Desc", true);
  }

  @Test
  void testCreateProjectExplicitSettings() throws Exception {
    CreateProjectRequestDTO req =
        CreateProjectRequestDTO.builder()
            .title("Custom Project")
            .description("Desc")
            .yoloEnabled(false)
            .build();
    Project created =
        Project.builder()
            .id(projectId)
            .title("Custom Project")
            .description("Desc")
            .yoloEnabled(false)
            .version(0L)
            .build();
    when(projectService.createProject("Custom Project", "Desc", false)).thenReturn(created);

    mockMvc
        .perform(
            post("/api/project/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(projectId.toString().toLowerCase()))
        .andExpect(jsonPath("$.title").value("Custom Project"))
        .andExpect(jsonPath("$.yoloEnabled").value(false));

    verify(projectService).createProject("Custom Project", "Desc", false);
  }

  @Test
  void testGetProject() throws Exception {
    Project project =
        Project.builder().id(projectId).title("Existing").version(1L).createdAt(now).build();
    when(projectService.getProject(projectId)).thenReturn(project);

    mockMvc
        .perform(get("/api/project/projects/" + projectId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(projectId.toString().toLowerCase()))
        .andExpect(jsonPath("$.title").value("Existing"));
  }

  @Test
  void testUpdateProjectSuccess() throws Exception {
    UpdateProjectRequestDTO req =
        UpdateProjectRequestDTO.builder()
            .expectedVersion(1L)
            .title("Updated Title")
            .description("Updated Desc")
            .build();
    Project updated =
        Project.builder()
            .id(projectId)
            .title("Updated Title")
            .description("Updated Desc")
            .version(2L)
            .build();
    when(projectService.updateConfiguration(projectId, 1L, "Updated Title", "Updated Desc"))
        .thenReturn(updated);

    mockMvc
        .perform(
            put("/api/project/projects/" + projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("2"));

    verify(projectService).updateConfiguration(projectId, 1L, "Updated Title", "Updated Desc");
  }

  @Test
  void testUpdateProjectConflict() throws Exception {
    UpdateProjectRequestDTO req =
        UpdateProjectRequestDTO.builder()
            .expectedVersion(1L)
            .title("Updated Title")
            .description("Updated Desc")
            .build();
    when(projectService.updateConfiguration(projectId, 1L, "Updated Title", "Updated Desc"))
        .thenThrow(DomainException.versionConflict("project", 1L, 2L));

    mockMvc
        .perform(
            put("/api/project/projects/" + projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"));
  }

  @Test
  void testUpdateWorkflow() throws Exception {
    ProjectWorkflowStateDTO init =
        ProjectWorkflowStateDTO.builder()
            .state("INIT")
            .name("Init")
            .enabled(true)
            .next(List.of("DONE"))
            .build();
    ProjectWorkflowStateDTO blocked =
        ProjectWorkflowStateDTO.builder()
            .state("BLOCKED")
            .name("Blocked")
            .enabled(true)
            .next(List.of())
            .build();
    ProjectWorkflowStateDTO done =
        ProjectWorkflowStateDTO.builder()
            .state("DONE")
            .name("Done")
            .enabled(true)
            .next(List.of())
            .build();
    ProjectWorkflowDTO wf =
        ProjectWorkflowDTO.builder().states(List.of(init, blocked, done)).build();

    UpdateProjectWorkflowRequestDTO req =
        UpdateProjectWorkflowRequestDTO.builder().expectedVersion(1L).workflow(wf).build();

    Project updated = Project.builder().id(projectId).version(2L).build();
    when(projectService.updateWorkflow(eq(projectId), eq(1L), anyString())).thenReturn(updated);

    mockMvc
        .perform(
            put("/api/project/projects/" + projectId + "/workflow")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("2"));
  }

  /** 工作流 maxRuns 也必须走严格 Long 输入边界，不能交给服务宽松解析。 */
  @Test
  void rejectsMalformedWorkflowMaxRuns() throws Exception {
    for (String token : List.of("1", "\" 1\"", "\"9223372036854775808\"")) {
      mockMvc
          .perform(
              put("/api/project/projects/" + projectId + "/workflow")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"expectedVersion\":\"1\",\"workflow\":{\"states\":["
                          + "{\"state\":\"WORK\",\"name\":\"Work\",\"maxRuns\":"
                          + token
                          + "}]}}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("validation"));
    }
  }

  @Test
  void rejectsMalformedClientWorkflowBeforeServiceCall() throws Exception {
    mockMvc
        .perform(
            put("/api/project/projects/" + projectId + "/workflow")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":"1","workflow":{"states":[
                    {"state":"not a state code","name":"Bad"}]}}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    mockMvc
        .perform(
            put("/api/project/projects/" + projectId + "/workflow")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));

    verifyNoInteractions(projectService);
  }

  @Test
  void testUpdateYolo() throws Exception {
    UpdateProjectYoloRequestDTO req =
        UpdateProjectYoloRequestDTO.builder().expectedVersion(1L).yoloEnabled(true).build();
    Project updated = Project.builder().id(projectId).version(2L).yoloEnabled(true).build();
    when(projectService.updateYolo(projectId, 1L, true)).thenReturn(updated);

    mockMvc
        .perform(
            put("/api/project/projects/" + projectId + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.yoloEnabled").value(true));
  }

  @Test
  void testDeleteProject() throws Exception {
    mockMvc
        .perform(delete("/api/project/projects/" + projectId + "?expectedVersion=0"))
        .andExpect(status().isNoContent());

    verify(projectService).deleteProject(projectId, 0L);
  }

  @Test
  void testArchiveProject() throws Exception {
    ProjectVersionRequestDTO req = ProjectVersionRequestDTO.builder().expectedVersion(0L).build();
    Project archived = Project.builder().id(projectId).version(1L).archivedAt(now).build();
    when(projectService.archiveProject(projectId, 0L)).thenReturn(archived);

    mockMvc
        .perform(
            post("/api/project/projects/" + projectId + "/archive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archivedAt").isNotEmpty());
  }

  @Test
  void testUnarchiveProject() throws Exception {
    ProjectVersionRequestDTO req = ProjectVersionRequestDTO.builder().expectedVersion(1L).build();
    Project unarchived = Project.builder().id(projectId).version(2L).archivedAt(null).build();
    when(projectService.unarchiveProject(projectId, 1L)).thenReturn(unarchived);

    mockMvc
        .perform(
            post("/api/project/projects/" + projectId + "/unarchive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archivedAt").isEmpty());
  }

  @Test
  void testGetSnapshot() throws Exception {
    ProjectSnapshotDTO snapshot =
        ProjectSnapshotDTO.builder()
            .project(mapper.toDto(Project.builder().id(projectId).version(0L).build()))
            .issues(List.of())
            .referencedStateCodes(List.of("INIT", "WORK"))
            .build();
    when(snapshotAssembler.assemble(projectId)).thenReturn(snapshot);

    mockMvc
        .perform(get("/api/project/projects/" + projectId + "/snapshot"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.project.id").value(projectId.toString().toLowerCase()))
        .andExpect(jsonPath("$.referencedStateCodes").isArray())
        .andExpect(jsonPath("$.referencedStateCodes[0]").value("INIT"))
        .andExpect(jsonPath("$.referencedStateCodes[1]").value("WORK"));
  }

  @Test
  void testResourceNotFoundAdvice() throws Exception {
    when(projectService.getProject(projectId)).thenThrow(DomainException.notFound("project"));

    mockMvc.perform(get("/api/project/projects/" + projectId)).andExpect(status().isNotFound());
  }
}
