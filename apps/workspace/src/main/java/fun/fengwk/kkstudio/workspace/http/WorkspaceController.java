package fun.fengwk.kkstudio.workspace.http;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.workspace.ChatSpaceService;
import fun.fengwk.kkstudio.workspace.FolderService;
import fun.fengwk.kkstudio.workspace.WorkspaceInput;
import fun.fengwk.kkstudio.workspace.WorkspaceResources;
import fun.fengwk.kkstudio.workspace.WorkspaceSessions;
import fun.fengwk.kkstudio.workspace.WorkspaceTreeService;
import fun.fengwk.kkstudio.workspace.dto.BranchCreatedDTO;
import fun.fengwk.kkstudio.workspace.dto.CanvasCreateDTO;
import fun.fengwk.kkstudio.workspace.dto.ChatSpaceCreateDTO;
import fun.fengwk.kkstudio.workspace.dto.ChatSpaceDTO;
import fun.fengwk.kkstudio.workspace.dto.ChatSpacePatchDTO;
import fun.fengwk.kkstudio.workspace.dto.FolderCreateDTO;
import fun.fengwk.kkstudio.workspace.dto.FolderCreatedDTO;
import fun.fengwk.kkstudio.workspace.dto.FolderPatchDTO;
import fun.fengwk.kkstudio.workspace.dto.ForkCreatedDTO;
import fun.fengwk.kkstudio.workspace.dto.ResourcePatchDTO;
import fun.fengwk.kkstudio.workspace.dto.ResourceRefDTO;
import fun.fengwk.kkstudio.workspace.dto.SessionCreateDTO;
import fun.fengwk.kkstudio.workspace.dto.SessionCreatedDTO;
import fun.fengwk.kkstudio.workspace.dto.ThreadRenameDTO;
import fun.fengwk.kkstudio.workspace.dto.TurnCutDTO;
import fun.fengwk.kkstudio.workspace.dto.WorkspaceResourceKind;
import fun.fengwk.kkstudio.workspace.dto.WorkspaceThreadPlacementDTO;
import fun.fengwk.kkstudio.workspace.dto.WorkspaceTreeDTO;
import fun.fengwk.kkstudio.workspace.dto.WorkspaceTreeVersionDTO;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat-spaces")
public class WorkspaceController {
  private final ChatSpaceService spaces;
  private final FolderService folders;
  private final WorkspaceTreeService tree;
  private final WorkspaceResources resources;
  private final WorkspaceSessions sessions;

  WorkspaceController(
      ChatSpaceService spaces,
      FolderService folders,
      WorkspaceTreeService tree,
      WorkspaceResources resources,
      WorkspaceSessions sessions) {
    this.spaces = spaces;
    this.folders = folders;
    this.tree = tree;
    this.resources = resources;
    this.sessions = sessions;
  }

  private static ChatSpaceDTO toDto(ChatSpaceService.Space space) {
    return new ChatSpaceDTO(
        space.id(),
        space.title(),
        space.archivedAt() == null ? null : space.archivedAt().toInstant(),
        Long.toString(space.version()),
        space.defaultAgentName(),
        space.defaultEnvironmentId(),
        space.defaultYoloEnabled());
  }

  @GetMapping
  public List<ChatSpaceDTO> list(StudioPrincipal actor) {
    return spaces.list(actor).stream().map(WorkspaceController::toDto).toList();
  }

  @PostMapping
  public ChatSpaceDTO create(
      StudioPrincipal actor,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody ChatSpaceCreateDTO request) {
    return toDto(
        spaces.create(
            actor,
            WorkspaceInput.uuid(key),
            new ChatSpaceService.Create(
                request.title(),
                request.rootFolderName(),
                request.defaultAgentName(),
                request.defaultEnvironmentId(),
                Boolean.TRUE.equals(request.defaultYoloEnabled()))));
  }

  @PatchMapping("/{spaceId}")
  public ChatSpaceDTO patch(
      StudioPrincipal actor, @PathVariable UUID spaceId, @RequestBody ChatSpacePatchDTO request) {
    if (request.title() == null
        && request.archived() == null
        && !request.hasDefaultAgentName()
        && !request.hasDefaultEnvironmentId()
        && request.defaultYoloEnabled() == null) {
      throw WorkspaceInput.invalid("At least one space change is required");
    }
    return toDto(
        spaces.patch(
            actor,
            spaceId,
            new ChatSpaceService.Patch(
                WorkspaceInput.version(request.expectedVersion()),
                request.title(),
                request.archived(),
                request.hasDefaultAgentName()
                    ? new ChatSpaceService.Change<>(request.defaultAgentName())
                    : null,
                request.hasDefaultEnvironmentId()
                    ? new ChatSpaceService.Change<>(request.defaultEnvironmentId())
                    : null,
                request.defaultYoloEnabled())));
  }

  @GetMapping("/{spaceId}/tree")
  public WorkspaceTreeDTO tree(StudioPrincipal actor, @PathVariable UUID spaceId) {
    return tree.tree(actor, spaceId);
  }

  @PostMapping("/{spaceId}/folders")
  public FolderCreatedDTO createFolder(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody FolderCreateDTO request) {
    var result =
        folders.create(
            actor,
            spaceId,
            WorkspaceInput.uuid(key),
            new FolderService.Create(
                request.parentId(),
                request.name(),
                WorkspaceInput.version(request.expectedTreeVersion())));
    return new FolderCreatedDTO(result.id(), Long.toString(result.treeVersion()));
  }

  @PatchMapping("/{spaceId}/folders/{folderId}")
  public WorkspaceTreeVersionDTO patchFolder(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID folderId,
      @RequestBody FolderPatchDTO request) {
    if (request.name() != null) {
      if (request.expectedTreeVersion() != null
          || request.parentId() != null
          || request.beforeId() != null) {
        throw WorkspaceInput.invalid("Rename and move are separate operations");
      }
      return WorkspaceTreeVersionDTO.of(folders.rename(actor, spaceId, folderId, request.name()));
    }
    return WorkspaceTreeVersionDTO.of(
        folders.move(
            actor,
            spaceId,
            folderId,
            request.parentId(),
            request.beforeId(),
            WorkspaceInput.version(request.expectedTreeVersion())));
  }

  @DeleteMapping("/{spaceId}/folders/{folderId}")
  public WorkspaceTreeVersionDTO deleteFolder(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID folderId,
      @RequestParam String expectedTreeVersion) {
    return WorkspaceTreeVersionDTO.of(
        folders.delete(actor, spaceId, folderId, WorkspaceInput.version(expectedTreeVersion)));
  }

  @PatchMapping("/{spaceId}/resources/{kind}/{id}")
  public WorkspaceTreeVersionDTO patchResource(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable WorkspaceResourceKind kind,
      @PathVariable UUID id,
      @RequestBody ResourcePatchDTO request) {
    return WorkspaceTreeVersionDTO.of(
        resources.patch(
            actor,
            spaceId,
            switch (kind) {
              case SESSION -> Kind.SESSION;
              case CANVAS -> Kind.CANVAS;
            },
            id,
            request));
  }

  @PostMapping("/{spaceId}/canvases")
  public ResourceRefDTO createCanvas(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody CanvasCreateDTO request) {
    return resources.createCanvas(actor, spaceId, WorkspaceInput.uuid(key), request);
  }

  @DeleteMapping("/{spaceId}/canvases/{canvasId}")
  public WorkspaceTreeVersionDTO deleteCanvas(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID canvasId,
      @RequestParam String expectedTreeVersion,
      @RequestParam String expectedRevision) {
    return WorkspaceTreeVersionDTO.of(
        resources.deleteCanvas(
            actor,
            spaceId,
            canvasId,
            WorkspaceInput.version(expectedTreeVersion),
            WorkspaceInput.version(expectedRevision)));
  }

  @PostMapping("/{spaceId}/sessions")
  public SessionCreatedDTO createSession(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody SessionCreateDTO request) {
    return sessions.create(actor, spaceId, WorkspaceInput.uuid(key), request);
  }

  @PostMapping("/{spaceId}/sessions/{id}/branches")
  public BranchCreatedDTO branch(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody TurnCutDTO request) {
    return sessions.branch(actor, spaceId, id, WorkspaceInput.uuid(key), request);
  }

  @PostMapping("/{spaceId}/sessions/{id}/forks")
  public ForkCreatedDTO fork(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody TurnCutDTO request) {
    return sessions.fork(actor, spaceId, id, WorkspaceInput.uuid(key), request);
  }

  @GetMapping("/{spaceId}/threads/{id}")
  public WorkspaceThreadPlacementDTO thread(
      StudioPrincipal actor, @PathVariable UUID spaceId, @PathVariable UUID id) {
    return tree.thread(actor, spaceId, id);
  }

  @PatchMapping("/{spaceId}/threads/{id}")
  public WorkspaceThreadPlacementDTO renameThread(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID id,
      @RequestBody ThreadRenameDTO request) {
    return sessions.rename(actor, spaceId, id, request);
  }

  @DeleteMapping("/{spaceId}/threads/{id}")
  public WorkspaceTreeVersionDTO deleteThread(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID id,
      @RequestParam String expectedTreeVersion,
      @RequestParam String expectedVersion) {
    return WorkspaceTreeVersionDTO.of(
        sessions.deleteThread(
            actor,
            spaceId,
            id,
            WorkspaceInput.version(expectedTreeVersion),
            WorkspaceInput.version(expectedVersion)));
  }

  @DeleteMapping("/{spaceId}/sessions/{id}")
  public WorkspaceTreeVersionDTO deleteSession(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID id,
      @RequestParam String expectedTreeVersion) {
    return WorkspaceTreeVersionDTO.of(
        sessions.deleteSession(actor, spaceId, id, WorkspaceInput.version(expectedTreeVersion)));
  }
}
