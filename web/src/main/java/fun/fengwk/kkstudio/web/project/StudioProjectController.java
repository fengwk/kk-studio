package fun.fengwk.kkstudio.web.project;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
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
import fun.fengwk.kkstudio.share.project.CreateProjectRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectArchiveRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectDTO;
import fun.fengwk.kkstudio.share.project.ProjectSnapshotDTO;
import fun.fengwk.kkstudio.share.project.ProjectUnarchiveRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateProjectRequestDTO;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Project 领域 REST 控制器。
 *
 * <p>Project 只拥有配置（YOLO 启动策略与打回阈值）与 Issue 集合；Issue Agent Session 归属按 {@code (issueId, agentName)} 由
 * Issue 详情暴露，因此本控制器不提供项目级 Session 路由。
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/projects")
public class StudioProjectController {

  /** 未显式指定时，新项目开启 YOLO。 */
  private static final boolean DEFAULT_YOLO_ENABLED = true;

  /** 未显式指定时，正式审查连续打回阈值取 3。 */
  private static final int DEFAULT_MAX_REVIEW_REJECTIONS = 3;

  private final ProjectService projectService;
  private final ProjectSnapshotAssembler projectSnapshotAssembler;
  private final ProjectDtoMapper mapper;

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
    int maxReviewRejections =
        request.getMaxReviewRejections() == null
            ? DEFAULT_MAX_REVIEW_REJECTIONS
            : request.getMaxReviewRejections();
    Project created =
        projectService.createProject(
            request.getTitle(), request.getDescription(), yoloEnabled, maxReviewRejections);
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
        projectService.updateProject(
            projectId,
            expectedVersion,
            request.getTitle(),
            request.getDescription(),
            request.getYoloEnabled(),
            request.getMaxReviewRejections());
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
      @RequestBody ProjectArchiveRequestDTO request) {
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Project archived = projectService.archiveProject(projectId, expectedVersion);
    return Results.ok(mapper.toDto(archived));
  }

  @PostMapping("/{projectId}/unarchive")
  public Result<ProjectDTO> unarchiveProject(
      @PathVariable("projectId") String projectIdStr,
      @RequestBody ProjectUnarchiveRequestDTO request) {
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
