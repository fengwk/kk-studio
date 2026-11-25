package fun.fengwk.kkstudio.workspace;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasCommandService;
import fun.fengwk.kkstudio.harness.api.RenameSessionCommand;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;

import java.util.UUID;

@Service
public class WorkspaceResources {
  private final ChatSpaceService spaces;
  private final WorkspacePlacements placements;
  private final CanvasCommandService canvases;
  private final ObjectProvider<HarnessRuntime> runtime;

  WorkspaceResources(
      ChatSpaceService spaces,
      WorkspacePlacements placements,
      CanvasCommandService canvases,
      ObjectProvider<HarnessRuntime> runtime) {
    this.spaces = spaces;
    this.placements = placements;
    this.canvases = canvases;
    this.runtime = runtime;
  }

  public record WorkspaceCanvasCreateDTO(UUID folderId, String title, String expectedTreeVersion) {}

  public record WorkspaceResourceRefDTO(Kind kind, UUID id, String treeVersion) {}

  public record WorkspaceResourcePatchDTO(
      @Nullable String title,
      @Nullable UUID folderId,
      @Nullable UUID beforeId,
      @Nullable String expectedTreeVersion) {}

  @Transactional
  public WorkspaceResourceRefDTO createCanvas(
      StudioPrincipal actor, UUID spaceId, UUID key, WorkspaceCanvasCreateDTO request) {
    var space = spaces.lock(actor, spaceId);
    var id = ChatSpaceService.derivedId("canvas", spaceId, key);
    var hash = WorkspacePlacements.fingerprint(request);
    var replay = placements.replay(spaceId, id, hash);
    if (replay.isPresent()) {
      return new WorkspaceResourceRefDTO(
          Kind.CANVAS, id, replay.get().createdTreeVersion().toString());
    }
    ChatSpaceService.requireTreeVersion(
        space, WorkspaceInput.version(request.expectedTreeVersion()));
    placements.requireFolder(spaceId, request.folderId());
    canvases.createCanvas(id, request.title());
    placements.insert(
        spaceId, request.folderId(), id, Kind.CANVAS, null, hash, space.treeVersion() + 1, null);
    return new WorkspaceResourceRefDTO(Kind.CANVAS, id, Long.toString(spaces.advance(spaceId)));
  }

  @Transactional
  public long patch(
      StudioPrincipal actor, UUID spaceId, Kind kind, UUID id, WorkspaceResourcePatchDTO request) {
    var space = spaces.lock(actor, spaceId);
    var edge = placements.require(spaceId, id, kind);
    if (request.title() != null) {
      if (request.expectedTreeVersion() != null
          || request.folderId() != null
          || request.beforeId() != null) {
        throw WorkspaceInput.invalid("Rename and move are separate operations");
      }
      var title = ChatSpaceService.name(request.title(), "title");
      if (kind == Kind.SESSION) {
        runtime.getObject().renameSession(new RenameSessionCommand(id, title));
      } else {
        canvases.renameCanvas(id, title);
      }
      return space.treeVersion();
    }
    ChatSpaceService.requireTreeVersion(
        space, WorkspaceInput.version(request.expectedTreeVersion()));
    placements.move(spaceId, edge, request.folderId(), request.beforeId());
    return spaces.advance(spaceId);
  }

  @Transactional
  public long deleteCanvas(
      StudioPrincipal actor, UUID spaceId, UUID id, long treeVersion, long revision) {
    var space = spaces.lock(actor, spaceId);
    var edge = placements.require(spaceId, id, Kind.CANVAS);
    ChatSpaceService.requireTreeVersion(space, treeVersion);
    placements.delete(spaceId, edge);
    canvases.deleteCanvas(id, revision);
    return spaces.advance(spaceId);
  }
}
