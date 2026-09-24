package fun.fengwk.kkstudio.web.project;

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
import fun.fengwk.kkstudio.share.project.CreateProjectRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectArchiveRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectSnapshotDTO;
import fun.fengwk.kkstudio.share.project.ProjectUnarchiveRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateProjectRequestDTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 验证 StudioProjectController 的全部 REST 接口端点、配置透传以及 CAS 409 / 404 错误处理。 */
class StudioProjectControllerTest {

  private ProjectService projectService;
  private ProjectSnapshotAssembler snapshotAssembler;

  private MockMvc mockMvc;
  private final ObjectMapper objectMapper = ObjectMapperHolder.getInstance();
  private final UUID projectId = UUID.randomUUID();
  private final Instant now = Instant.now();

  @BeforeEach
  void setUp() {
    projectService = mock(ProjectService.class);
    snapshotAssembler = mock(ProjectSnapshotAssembler.class);

    StudioProjectController controller =
        new StudioProjectController(projectService, snapshotAssembler, new ProjectDtoMapper());

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
            .maxReviewRejections(3)
            .version(0L)
            .build();
    when(projectService.createProject("New Project", "Desc", true, 3)).thenReturn(created);

    mockMvc
        .perform(
            post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(projectId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.title").value("New Project"))
        .andExpect(jsonPath("$.data.yoloEnabled").value(true))
        .andExpect(jsonPath("$.data.maxReviewRejections").value("3"));

    verify(projectService).createProject("New Project", "Desc", true, 3);
  }

  @Test
  void testCreateProjectExplicitSettings() throws Exception {
    CreateProjectRequestDTO req =
        CreateProjectRequestDTO.builder()
            .title("Custom Project")
            .description("Desc")
            .yoloEnabled(false)
            .maxReviewRejections(5)
            .build();
    Project created =
        Project.builder()
            .id(projectId)
            .title("Custom Project")
            .description("Desc")
            .yoloEnabled(false)
            .maxReviewRejections(5)
            .version(0L)
            .build();
    when(projectService.createProject("Custom Project", "Desc", false, 5)).thenReturn(created);

    mockMvc
        .perform(
            post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(projectId.toString().toLowerCase()))
        .andExpect(jsonPath("$.data.title").value("Custom Project"))
        .andExpect(jsonPath("$.data.yoloEnabled").value(false))
        .andExpect(jsonPath("$.data.maxReviewRejections").value("5"));

    verify(projectService).createProject("Custom Project", "Desc", false, 5);
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
    when(projectService.updateProject(projectId, 1L, "Updated Title", "Updated Desc", null, null))
        .thenReturn(updated);

    mockMvc
        .perform(
            put("/api/projects/" + projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("2"));

    verify(projectService)
        .updateProject(projectId, 1L, "Updated Title", "Updated Desc", null, null);
  }

  @Test
  void testUpdateProjectWithExplicitSettings() throws Exception {
    UpdateProjectRequestDTO req =
        UpdateProjectRequestDTO.builder()
            .expectedVersion("1")
            .title("Updated Title")
            .description("Updated Desc")
            .yoloEnabled(false)
            .maxReviewRejections(4)
            .build();
    Project updated =
        Project.builder()
            .id(projectId)
            .title("Updated Title")
            .description("Updated Desc")
            .yoloEnabled(false)
            .maxReviewRejections(4)
            .version(2L)
            .build();
    when(projectService.updateProject(projectId, 1L, "Updated Title", "Updated Desc", false, 4))
        .thenReturn(updated);

    mockMvc
        .perform(
            put("/api/projects/" + projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("2"))
        .andExpect(jsonPath("$.data.yoloEnabled").value(false))
        .andExpect(jsonPath("$.data.maxReviewRejections").value("4"));

    verify(projectService).updateProject(projectId, 1L, "Updated Title", "Updated Desc", false, 4);
  }

  @Test
  void testUpdateProjectConflict() throws Exception {
    UpdateProjectRequestDTO req =
        UpdateProjectRequestDTO.builder()
            .expectedVersion("1")
            .title("Updated Title")
            .description("Updated Desc")
            .build();
    when(projectService.updateProject(projectId, 1L, "Updated Title", "Updated Desc", null, null))
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
  void testDeleteProject() throws Exception {
    mockMvc
        .perform(delete("/api/projects/" + projectId + "?expectedVersion=0"))
        .andExpect(status().isNoContent());

    verify(projectService).deleteProject(projectId, 0L);
  }

  @Test
  void testArchiveProject() throws Exception {
    ProjectArchiveRequestDTO req = ProjectArchiveRequestDTO.builder().expectedVersion("0").build();
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
    ProjectUnarchiveRequestDTO req =
        ProjectUnarchiveRequestDTO.builder().expectedVersion("1").build();
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
