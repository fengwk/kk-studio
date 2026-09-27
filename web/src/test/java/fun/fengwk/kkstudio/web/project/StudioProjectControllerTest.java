package fun.fengwk.kkstudio.web.project;

import static org.mockito.ArgumentMatchers.anyString;
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

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.share.project.CreateProjectRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectSnapshotDTO;
import fun.fengwk.kkstudio.share.project.ProjectVersionRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectWorkflowDTO;
import fun.fengwk.kkstudio.share.project.ProjectWorkflowStateDTO;
import fun.fengwk.kkstudio.share.project.UpdateProjectRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateProjectWorkflowRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateProjectYoloRequestDTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 验证 StudioProjectController 的全部 REST 接口端点与 CAS 409 处理。 */
class StudioProjectControllerTest {

  private ProjectService projectService;
  private ProjectSnapshotAssembler snapshotAssembler;
  private MockMvc mockMvc;

  private final ObjectMapper objectMapper = ObjectMapperHolder.getInstance();
  private final UUID projectId = UUID.randomUUID();
  private final Instant now = Instant.now();
  private final ProjectWorkflowJsonCodec codec = new ProjectWorkflowJsonCodec();

  @BeforeEach
  void setUp() {
    projectService = mock(ProjectService.class);
    snapshotAssembler = mock(ProjectSnapshotAssembler.class);

    StudioProjectController controller =
        new StudioProjectController(
            projectService, snapshotAssembler, new ProjectDtoMapper(), codec);

    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new StudioProjectErrorAdvice())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
  }

  @Test
  void testListProjects() throws Exception {
    Project p1 = Project.builder().id(UUID.randomUUID()).title("P1").version(0L).build();
    when(projectService.listProjects(false)).thenReturn(List.of(p1));

    mockMvc
        .perform(get("/api/projects"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].title").value("P1"));
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
            post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(projectId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.title").value("New Project"))
        .andExpect(jsonPath("$.data.yoloEnabled").value(true));

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
            post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(projectId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.title").value("Custom Project"))
        .andExpect(jsonPath("$.data.yoloEnabled").value(false));

    verify(projectService).createProject("Custom Project", "Desc", false);
  }

  @Test
  void testGetProject() throws Exception {
    Project project =
        Project.builder().id(projectId).title("Existing").version(1L).createdAt(now).build();
    when(projectService.getProject(projectId)).thenReturn(project);

    mockMvc
        .perform(get("/api/projects/" + projectId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(projectId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.title").value("Existing"));
  }

  @Test
  void testUpdateProjectSuccess() throws Exception {
    UpdateProjectRequestDTO req =
        UpdateProjectRequestDTO.builder()
            .expectedVersion("1")
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
            put("/api/projects/" + projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("2"));

    verify(projectService).updateConfiguration(projectId, 1L, "Updated Title", "Updated Desc");
  }

  @Test
  void testUpdateProjectConflict() throws Exception {
    UpdateProjectRequestDTO req =
        UpdateProjectRequestDTO.builder()
            .expectedVersion("1")
            .title("Updated Title")
            .description("Updated Desc")
            .build();
    when(projectService.updateConfiguration(projectId, 1L, "Updated Title", "Updated Desc"))
        .thenThrow(new AiVersionConflictException("project", "1", "2"));

    mockMvc
        .perform(
            put("/api/projects/" + projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PROJECT_VERSION_CONFLICT"));
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
        UpdateProjectWorkflowRequestDTO.builder().expectedVersion("1").workflow(wf).build();

    Project updated = Project.builder().id(projectId).version(2L).build();
    when(projectService.updateWorkflow(eq(projectId), eq(1L), anyString())).thenReturn(updated);

    mockMvc
        .perform(
            put("/api/projects/" + projectId + "/workflow")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("2"));
  }

  @Test
  void testUpdateYolo() throws Exception {
    UpdateProjectYoloRequestDTO req =
        UpdateProjectYoloRequestDTO.builder().expectedVersion("1").yoloEnabled(true).build();
    Project updated = Project.builder().id(projectId).version(2L).yoloEnabled(true).build();
    when(projectService.updateYolo(projectId, 1L, true)).thenReturn(updated);

    mockMvc
        .perform(
            put("/api/projects/" + projectId + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.yoloEnabled").value(true));
  }

  @Test
  void testDeleteProject() throws Exception {
    mockMvc
        .perform(delete("/api/projects/" + projectId + "?expectedVersion=0"))
        .andExpect(status().isNoContent());

    verify(projectService).deleteProject(projectId, 0L);
  }

  @Test
  void testArchiveProject() throws Exception {
    ProjectVersionRequestDTO req = ProjectVersionRequestDTO.builder().expectedVersion("0").build();
    Project archived = Project.builder().id(projectId).version(1L).archivedAt(now).build();
    when(projectService.archiveProject(projectId, 0L)).thenReturn(archived);

    mockMvc
        .perform(
            post("/api/projects/" + projectId + "/archive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.archivedAt").isNotEmpty());
  }

  @Test
  void testUnarchiveProject() throws Exception {
    ProjectVersionRequestDTO req = ProjectVersionRequestDTO.builder().expectedVersion("1").build();
    Project unarchived = Project.builder().id(projectId).version(2L).archivedAt(null).build();
    when(projectService.unarchiveProject(projectId, 1L)).thenReturn(unarchived);

    mockMvc
        .perform(
            post("/api/projects/" + projectId + "/unarchive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.archivedAt").isEmpty());
  }

  @Test
  void testGetSnapshot() throws Exception {
    ProjectSnapshotDTO snapshot =
        ProjectSnapshotDTO.builder()
            .project(
                new ProjectDtoMapper().toDto(Project.builder().id(projectId).version(0L).build()))
            .issues(List.of())
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
