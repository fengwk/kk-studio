package fun.fengwk.kkstudio.workspace;

import com.fasterxml.jackson.annotation.JsonSetter;
import org.jspecify.annotations.Nullable;
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
import fun.fengwk.kkstudio.workspace.WorkspaceResources.WorkspaceCanvasCreateDTO;
import fun.fengwk.kkstudio.workspace.WorkspaceResources.WorkspaceResourcePatchDTO;
import fun.fengwk.kkstudio.workspace.WorkspaceResources.WorkspaceResourceRefDTO;
import fun.fengwk.kkstudio.workspace.WorkspaceSessions.WorkspaceBranchCreatedDTO;
import fun.fengwk.kkstudio.workspace.WorkspaceSessions.WorkspaceForkCreatedDTO;
import fun.fengwk.kkstudio.workspace.WorkspaceSessions.WorkspaceSessionCreateDTO;
import fun.fengwk.kkstudio.workspace.WorkspaceSessions.WorkspaceSessionCreatedDTO;
import fun.fengwk.kkstudio.workspace.WorkspaceSessions.WorkspaceThreadRenameDTO;
import fun.fengwk.kkstudio.workspace.WorkspaceSessions.WorkspaceTurnCutDTO;
import fun.fengwk.kkstudio.workspace.WorkspaceTreeService.WorkspaceThreadPlacementDTO;
import fun.fengwk.kkstudio.workspace.WorkspaceTreeService.WorkspaceTreeDTO;

import java.time.Instant;
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

  public record ChatSpaceSummaryDTO(
      UUID id,
      String title,
      @Nullable Instant archivedAt,
      String version,
      @Nullable String defaultAgentName,
      @Nullable UUID defaultEnvironmentId,
      boolean defaultYoloEnabled) {
    static ChatSpaceSummaryDTO of(ChatSpaceService.Space space) {
      return new ChatSpaceSummaryDTO(
          space.id(),
          space.title(),
          space.archivedAt() == null ? null : space.archivedAt().toInstant(),
          Long.toString(space.version()),
          space.defaultAgentName(),
          space.defaultEnvironmentId(),
          space.defaultYoloEnabled());
    }
  }

  public record ChatSpaceCreateDTO(
      String title,
      String rootFolderName,
      @Nullable String defaultAgentName,
      @Nullable UUID defaultEnvironmentId,
      @Nullable Boolean defaultYoloEnabled) {}

  public static final class ChatSpacePatchDTO {
    public String expectedVersion;
    public @Nullable String title;
    public @Nullable Boolean archived;
    public @Nullable Boolean defaultYoloEnabled;
    public @Nullable String defaultAgentName;
    public @Nullable UUID defaultEnvironmentId;
    private ChatSpaceService.Change<String> agent;
    private ChatSpaceService.Change<UUID> environment;

    @JsonSetter("defaultAgentName")
    public void setDefaultAgentName(@Nullable String value) {
      defaultAgentName = value;
      agent = new ChatSpaceService.Change<>(value);
    }

    @JsonSetter("defaultEnvironmentId")
    public void setDefaultEnvironmentId(@Nullable UUID value) {
      defaultEnvironmentId = value;
      environment = new ChatSpaceService.Change<>(value);
    }
  }

  public record WorkspaceFolderCreateDTO(
      @Nullable UUID parentId, String name, String expectedTreeVersion) {}

  public record WorkspaceFolderPatchDTO(
      @Nullable String name,
      @Nullable UUID parentId,
      @Nullable UUID beforeId,
      @Nullable String expectedTreeVersion) {}

  public record WorkspaceFolderCreatedDTO(UUID id, String treeVersion) {}

  public record WorkspaceTreeVersionDTO(String treeVersion) {
    static WorkspaceTreeVersionDTO of(long version) {
      return new WorkspaceTreeVersionDTO(Long.toString(version));
    }
  }

  @GetMapping
  public List<ChatSpaceSummaryDTO> list(StudioPrincipal actor) {
    return spaces.list(actor).stream().map(ChatSpaceSummaryDTO::of).toList();
  }

  @PostMapping
  public ChatSpaceSummaryDTO create(
      StudioPrincipal actor,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody ChatSpaceCreateDTO request) {
    return ChatSpaceSummaryDTO.of(
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
  public ChatSpaceSummaryDTO patch(
      StudioPrincipal actor, @PathVariable UUID spaceId, @RequestBody ChatSpacePatchDTO request) {
    if (request.title == null
        && request.archived == null
        && request.agent == null
        && request.environment == null
        && request.defaultYoloEnabled == null) {
      throw WorkspaceInput.invalid("At least one space change is required");
    }
    return ChatSpaceSummaryDTO.of(
        spaces.patch(
            actor,
            spaceId,
            new ChatSpaceService.Patch(
                WorkspaceInput.version(request.expectedVersion),
                request.title,
                request.archived,
                request.agent,
                request.environment,
                request.defaultYoloEnabled)));
  }

  @GetMapping("/{spaceId}/tree")
  public WorkspaceTreeDTO tree(StudioPrincipal actor, @PathVariable UUID spaceId) {
    return tree.tree(actor, spaceId);
  }

  @PostMapping("/{spaceId}/folders")
  public WorkspaceFolderCreatedDTO createFolder(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody WorkspaceFolderCreateDTO request) {
    var result =
        folders.create(
            actor,
            spaceId,
            WorkspaceInput.uuid(key),
            new FolderService.Create(
                request.parentId(),
                request.name(),
                WorkspaceInput.version(request.expectedTreeVersion())));
    return new WorkspaceFolderCreatedDTO(result.id(), Long.toString(result.treeVersion()));
  }

  @PatchMapping("/{spaceId}/folders/{folderId}")
  public WorkspaceTreeVersionDTO patchFolder(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID folderId,
      @RequestBody WorkspaceFolderPatchDTO request) {
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
      @PathVariable Kind kind,
      @PathVariable UUID id,
      @RequestBody WorkspaceResourcePatchDTO request) {
    return WorkspaceTreeVersionDTO.of(resources.patch(actor, spaceId, kind, id, request));
  }

  @PostMapping("/{spaceId}/canvases")
  public WorkspaceResourceRefDTO createCanvas(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody WorkspaceCanvasCreateDTO request) {
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
  public WorkspaceSessionCreatedDTO createSession(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody WorkspaceSessionCreateDTO request) {
    return sessions.create(actor, spaceId, WorkspaceInput.uuid(key), request);
  }

  @PostMapping("/{spaceId}/sessions/{id}/branches")
  public WorkspaceBranchCreatedDTO branch(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody WorkspaceTurnCutDTO request) {
    return sessions.branch(actor, spaceId, id, WorkspaceInput.uuid(key), request);
  }

  @PostMapping("/{spaceId}/sessions/{id}/forks")
  public WorkspaceForkCreatedDTO fork(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody WorkspaceTurnCutDTO request) {
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
      @RequestBody WorkspaceThreadRenameDTO request) {
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
