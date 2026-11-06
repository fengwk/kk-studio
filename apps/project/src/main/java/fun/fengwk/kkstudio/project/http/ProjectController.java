package fun.fengwk.kkstudio.project.http;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.dto.CreateProjectRequestDTO;
import fun.fengwk.kkstudio.project.dto.ProjectDTO;
import fun.fengwk.kkstudio.project.dto.ProjectDtoMapper;
import fun.fengwk.kkstudio.project.dto.ProjectSnapshotDTO;
import fun.fengwk.kkstudio.project.dto.ProjectVersionRequestDTO;
import fun.fengwk.kkstudio.project.dto.UpdateProjectRequestDTO;
import fun.fengwk.kkstudio.project.dto.UpdateProjectWorkflowRequestDTO;
import fun.fengwk.kkstudio.project.dto.UpdateProjectYoloRequestDTO;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.service.ProjectService;
import fun.fengwk.kkstudio.project.service.ProjectSnapshotAssembler;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Project 领域 REST 控制器。
 *
 * <p>提供项目配置、工作流、YOLO 执行策略、归档/解归档/删除以及聚合快照的权威 HTTP 操作面。
 */
@RestController
@RequestMapping("/api/project/projects")
public class ProjectController {

  /** 未显式指定时，新项目默认开启 YOLO。 */
  private static final boolean DEFAULT_YOLO_ENABLED = true;

  private final ProjectService projectService;
  private final ProjectSnapshotAssembler projectSnapshotAssembler;
  private final ProjectDtoMapper mapper;
  private final ProjectWorkflowJsonCodec workflowCodec;

  @Autowired
  public ProjectController(
      ProjectService projectService,
      ProjectSnapshotAssembler projectSnapshotAssembler,
      ProjectDtoMapper mapper,
      ProjectWorkflowJsonCodec workflowCodec) {
    this.projectService = Objects.requireNonNull(projectService, "projectService");
    this.projectSnapshotAssembler =
        Objects.requireNonNull(projectSnapshotAssembler, "projectSnapshotAssembler");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
  }

  @GetMapping
  public List<ProjectDTO> listProjects(
      @RequestParam(value = "includeArchived", required = false, defaultValue = "false")
          boolean includeArchived) {
    List<Project> list = projectService.listProjects(includeArchived);
    return list.stream().map(mapper::toDto).toList();
  }

  @PostMapping
  public ResponseEntity<ProjectDTO> createProject(@RequestBody CreateProjectRequestDTO request) {
    boolean yoloEnabled =
        request.getYoloEnabled() == null ? DEFAULT_YOLO_ENABLED : request.getYoloEnabled();
    Project created =
        projectService.createProject(request.getTitle(), request.getDescription(), yoloEnabled);
    return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(created));
  }

  @GetMapping("/{projectId}")
  public ProjectDTO getProject(@PathVariable("projectId") UUID projectId) {
    return mapper.toDto(projectService.getProject(projectId));
  }

  @PutMapping("/{projectId}")
  public ProjectDTO updateProject(
      @PathVariable("projectId") UUID projectId, @RequestBody UpdateProjectRequestDTO request) {
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    Project updated =
        projectService.updateConfiguration(
            projectId, expectedVersion, request.getTitle(), request.getDescription());
    return mapper.toDto(updated);
  }

  @PutMapping("/{projectId}/workflow")
  public ProjectDTO updateWorkflow(
      @PathVariable("projectId") UUID projectId,
      @RequestBody UpdateProjectWorkflowRequestDTO request) {
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    ProjectWorkflow workflow = mapper.toWorkflow(request.getWorkflow());
    Project updated =
        projectService.updateWorkflow(projectId, expectedVersion, workflowCodec.encode(workflow));
    return mapper.toDto(updated);
  }

  @PutMapping("/{projectId}/yolo")
  public ProjectDTO updateYolo(
      @PathVariable("projectId") UUID projectId, @RequestBody UpdateProjectYoloRequestDTO request) {
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    Project updated =
        projectService.updateYolo(projectId, expectedVersion, request.getYoloEnabled());
    return mapper.toDto(updated);
  }

  @DeleteMapping("/{projectId}")
  public ResponseEntity<Void> deleteProject(
      @PathVariable("projectId") UUID projectId,
      @RequestParam("expectedVersion") long expectedVersion) {
    projectService.deleteProject(projectId, expectedVersion);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{projectId}/archive")
  public ProjectDTO archiveProject(
      @PathVariable("projectId") UUID projectId, @RequestBody ProjectVersionRequestDTO request) {
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    return mapper.toDto(projectService.archiveProject(projectId, expectedVersion));
  }

  @PostMapping("/{projectId}/unarchive")
  public ProjectDTO unarchiveProject(
      @PathVariable("projectId") UUID projectId, @RequestBody ProjectVersionRequestDTO request) {
    long expectedVersion =
        DecimalLongs.parseNonNegative(request.getExpectedVersion(), "expectedVersion");
    return mapper.toDto(projectService.unarchiveProject(projectId, expectedVersion));
  }

  @GetMapping("/{projectId}/snapshot")
  public ProjectSnapshotDTO getSnapshot(@PathVariable("projectId") UUID projectId) {
    return projectSnapshotAssembler.assemble(projectId);
  }
}
