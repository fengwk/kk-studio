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

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ProjectSession;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.platform.project.session.ProjectHarnessSessionBootstrapService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSummaryDTO;
import fun.fengwk.kkstudio.share.project.CreateProjectRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectArchiveRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectCommandRequestDTO;
import fun.fengwk.kkstudio.share.project.ProjectDTO;
import fun.fengwk.kkstudio.share.project.ProjectSnapshotDTO;
import fun.fengwk.kkstudio.share.project.ProjectUnarchiveRequestDTO;
import fun.fengwk.kkstudio.share.project.UpdateProjectRequestDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeResponseMapper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Project 领域 REST 控制器。 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/projects")
public class StudioProjectController {

  private final ProjectService projectService;
  private final ProjectHarnessSessionBootstrapService projectHarnessSessionBootstrapService;
  private final HarnessCommandAcceptanceOrchestrator harnessCommandAcceptanceOrchestrator;
  private final HarnessRuntime harnessRuntime;
  private final HarnessOwnerQueryService harnessOwnerQueryService;
  private final ProjectSnapshotAssembler projectSnapshotAssembler;
  private final ProjectDtoMapper mapper;
  private final ProjectInvalidationHub invalidationHub;

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
    invalidationHub.publishChange(created.getId());
    return ResponseEntity.status(HttpStatus.CREATED).body(Results.ok(mapper.toDto(created)));
  }

  @GetMapping("/{projectId}")
  public Result<ProjectDTO> getProject(@PathVariable("projectId") String projectIdStr) {
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    Project project = projectService.getProject(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project", projectId.toString());
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
    invalidationHub.publishChange(projectId);
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
    invalidationHub.publishChange(projectId);
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
    invalidationHub.publishChange(projectId);
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
    invalidationHub.publishChange(projectId);
    return Results.ok(mapper.toDto(unarchived));
  }

  @PostMapping("/{projectId}/commands")
  public ResponseEntity<Result<HarnessAcceptedCommandsDTO>> commands(
      @PathVariable("projectId") String projectIdStr,
      @RequestBody ProjectCommandRequestDTO request) {
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
      throw new IllegalArgumentException("message must not be blank");
    }
    UUID idempotencyKey = ProjectDtoMapper.parseUuid(request.getIdempotencyKey(), "idempotencyKey");

    ProjectSession sessionRelation = projectService.getCoordinatorSession(projectId);
    AcceptedCommands accepted;

    if (sessionRelation == null) {
      // 首次会话：原子 bootstrap
      UUID sessionId = UUID.randomUUID();
      UUID threadId =
          request.getThreadId() != null && !request.getThreadId().isBlank()
              ? ProjectDtoMapper.parseUuid(request.getThreadId(), "threadId")
              : UUID.randomUUID();
      accepted =
          projectHarnessSessionBootstrapService.bootstrapProjectSession(
              projectId, sessionId, threadId, idempotencyKey, request.getMessage().trim());
    } else {
      // 既有会话：向既有或首个 thread 发送用户消息
      UUID sessionId = sessionRelation.getSessionId();
      UUID targetThreadId;
      if (request.getThreadId() != null && !request.getThreadId().isBlank()) {
        targetThreadId = ProjectDtoMapper.parseUuid(request.getThreadId(), "threadId");
      } else {
        List<HarnessThreadSummaryDTO> threads =
            harnessOwnerQueryService.listThreadSummaries(sessionId);
        if (threads.isEmpty()) {
          throw new IllegalStateException("No thread found for project session: " + sessionId);
        }
        targetThreadId = UUID.fromString(threads.get(0).getThreadId());
      }

      ThreadSnapshot currentSnapshot = harnessRuntime.getThreadSnapshot(targetThreadId);
      UUID expectedHead =
          request.getExpectedHeadEntryId() != null && !request.getExpectedHeadEntryId().isBlank()
              ? ProjectDtoMapper.parseUuid(request.getExpectedHeadEntryId(), "expectedHeadEntryId")
              : currentSnapshot.thread().headEntryId();
      long expectedNextSeq =
          request.getExpectedNextCommandSequence() != null
                  && !request.getExpectedNextCommandSequence().isBlank()
              ? ProjectDtoMapper.parseNonNegativeLong(
                  request.getExpectedNextCommandSequence(), "expectedNextCommandSequence")
              : currentSnapshot.thread().nextCommandSequence();

      UserMessageCommandPayload payload =
          new UserMessageCommandPayload(
              new AgentMessage(
                  AgentMessageRole.USER,
                  List.of(new TextMessageContent(request.getMessage().trim()))));
      NewThreadCommand command = new NewThreadCommand(payload, idempotencyKey);

      AcceptCommandsTarget.Thread target =
          new AcceptCommandsTarget.Thread(targetThreadId, expectedHead, expectedNextSeq);
      AcceptCommandsCommand acceptCommand = new AcceptCommandsCommand(target, List.of(command));

      accepted =
          harnessCommandAcceptanceOrchestrator.accept(
              new OwnerRef(OwnerType.PROJECT, projectId), acceptCommand);
    }

    ThreadSnapshot currentSnapshot = harnessRuntime.getThreadSnapshot(accepted.thread().id());
    HarnessAcceptedCommandsDTO dto =
        HarnessRuntimeResponseMapper.toAcceptedCommandsDto(accepted, currentSnapshot);

    invalidationHub.publishChange(projectId);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Results.ok(dto));
  }

  @GetMapping("/{projectId}/snapshot")
  public Result<ProjectSnapshotDTO> getSnapshot(@PathVariable("projectId") String projectIdStr) {
    UUID projectId = ProjectDtoMapper.parseUuid(projectIdStr, "projectId");
    ProjectSnapshotDTO snapshot = projectSnapshotAssembler.assemble(projectId);
    return Results.ok(snapshot);
  }
}
