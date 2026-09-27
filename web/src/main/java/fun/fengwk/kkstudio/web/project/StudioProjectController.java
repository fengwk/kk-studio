package fun.fengwk.kkstudio.web.project;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
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

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.share.project.CreateProjectRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectDTO;
import fun.fengwk.kkstudio.share.project.ProjectSnapshotDTO;
import fun.fengwk.kkstudio.share.project.ProjectVersionRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateProjectRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateProjectWorkflowRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateProjectYoloRequestDTO;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Project 领域 REST 控制器。
 *
 * <p>提供项目配置、工作流、YOLO 执行策略、归档/解归档/删除以及聚合快照的权威 HTTP 操作面。
 */
@RestController
@RequestMapping("/api/projects")
public class StudioProjectController {

  /** 未显式指定时，新项目默认开启 YOLO。 */
  private static final boolean DEFAULT_YOLO_ENABLED = true;

  private final ProjectService projectService;
  private final ProjectSnapshotAssembler projectSnapshotAssembler;
  private final ProjectDtoMapper mapper;
  private final ProjectWorkflowJsonCodec workflowCodec;

  @Autowired
  public StudioProjectController(
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

  public StudioProjectController(
      ProjectService projectService,
      ProjectSnapshotAssembler projectSnapshotAssembler,
      ProjectDtoMapper mapper) {
    this(projectService, projectSnapshotAssembler, mapper, new ProjectWorkflowJsonCodec());
  }

  @GetMapping
  public Result<List<ProjectDTO>> listProjects(
      @RequestParam(value = "includeArchived", required = false, defaultValue = "false")
          boolean includeArchived) {
    List<Project> list = projectService.listProjects(includeArchived);
    return Results.ok(list.stream().map(mapper::toDto).toList());
  }

  @PostMapping
  public ResponseEntity<Result<ProjectDTO>> createProject(
      @RequestBody CreateProjectRequestDTO request) {
    Objects.requireNonNull(request, "request");
    boolean yoloEnabled =
        request.getYoloEnabled() == null ? DEFAULT_YOLO_ENABLED : request.getYoloEnabled();
    Project created =
        projectService.createProject(request.getTitle(), request.getDescription(), yoloEnabled);
    return ResponseEntity.status(HttpStatus.CREATED).body(Results.ok(mapper.toDto(created)));
  }

  @GetMapping("/{projectId}")
  public Result<ProjectDTO> getProject(@PathVariable("projectId") String projectIdStr) {
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    Project project = projectService.getProject(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }
    return Results.ok(mapper.toDto(project));
  }

  @PutMapping("/{projectId}")
  public Result<ProjectDTO> updateProject(
      @PathVariable("projectId") String projectIdStr,
      @RequestBody UpdateProjectRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Project updated =
        projectService.updateConfiguration(
            projectId, expectedVersion, request.getTitle(), request.getDescription());
    return Results.ok(mapper.toDto(updated));
  }

  @PutMapping("/{projectId}/workflow")
  public Result<ProjectDTO> updateWorkflow(
      @PathVariable("projectId") String projectIdStr,
      @RequestBody UpdateProjectWorkflowRequestDTO request) {
    Objects.requireNonNull(request, "request");
    if (request.getWorkflow() == null) {
      throw new IllegalArgumentException("workflow must not be null");
    }
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    ProjectWorkflow workflow = mapper.toWorkflow(request.getWorkflow());
    String workflowJson = workflowCodec.encode(workflow);
    Project updated = projectService.updateWorkflow(projectId, expectedVersion, workflowJson);
    return Results.ok(mapper.toDto(updated));
  }

  @PutMapping("/{projectId}/yolo")
  public Result<ProjectDTO> updateYolo(
      @PathVariable("projectId") String projectIdStr,
      @RequestBody UpdateProjectYoloRequestDTO request) {
    Objects.requireNonNull(request, "request");
    if (request.getYoloEnabled() == null) {
      throw new IllegalArgumentException("yoloEnabled must not be null");
    }
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Project updated =
        projectService.updateYolo(projectId, expectedVersion, request.getYoloEnabled());
    return Results.ok(mapper.toDto(updated));
  }

  @DeleteMapping("/{projectId}")
  public ResponseEntity<Void> deleteProject(
      @PathVariable("projectId") String projectIdStr,
      @RequestParam("expectedVersion") String expectedVersionStr) {
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(expectedVersionStr, "expectedVersion");
    projectService.deleteProject(projectId, expectedVersion);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{projectId}/archive")
  public Result<ProjectDTO> archiveProject(
      @PathVariable("projectId") String projectIdStr,
      @RequestBody ProjectVersionRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Project archived = projectService.archiveProject(projectId, expectedVersion);
    return Results.ok(mapper.toDto(archived));
  }

  @PostMapping("/{projectId}/unarchive")
  public Result<ProjectDTO> unarchiveProject(
      @PathVariable("projectId") String projectIdStr,
      @RequestBody ProjectVersionRequestDTO request) {
    Objects.requireNonNull(request, "request");
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Project unarchived = projectService.unarchiveProject(projectId, expectedVersion);
    return Results.ok(mapper.toDto(unarchived));
  }

  @GetMapping("/{projectId}/snapshot")
  public Result<ProjectSnapshotDTO> getSnapshot(@PathVariable("projectId") String projectIdStr) {
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    ProjectSnapshotDTO snapshot = projectSnapshotAssembler.assemble(projectId);
    return Results.ok(snapshot);
  }
}
