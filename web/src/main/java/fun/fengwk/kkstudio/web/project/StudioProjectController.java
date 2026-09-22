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
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.share.project.CreateProjectRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectArchiveRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectDTO;
import fun.fengwk.kkstudio.share.project.ProjectSnapshotDTO;
import fun.fengwk.kkstudio.share.project.ProjectUnarchiveRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateProjectRequestDTO;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Project 领域 REST 控制器。 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/projects")
public class StudioProjectController {

  private final ProjectService projectService;
  private final HarnessOwnerQueryService harnessOwnerQueryService;
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
    Project created =
        projectService.createProject(
            request.getTitle(), request.getDescription(), request.getCoordinatorAgentName());
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
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    long expectedVersion =
        ProjectDtoMapper.parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    Project updated =
        projectService.updateProject(
            projectId,
            expectedVersion,
            request.getTitle(),
            request.getDescription(),
            request.getCoordinatorAgentName());
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

  @GetMapping("/{projectId}/sessions")
  public Result<List<HarnessSessionSummaryDTO>> listSessions(
      @PathVariable("projectId") String projectIdStr) {
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    List<HarnessSessionSummaryDTO> sessions =
        harnessOwnerQueryService.listProjectSessions(projectId);
    return Results.ok(sessions);
  }

  @GetMapping("/{projectId}/snapshot")
  public Result<ProjectSnapshotDTO> getSnapshot(@PathVariable("projectId") String projectIdStr) {
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    ProjectSnapshotDTO snapshot = projectSnapshotAssembler.assemble(projectId);
    return Results.ok(snapshot);
  }
}
