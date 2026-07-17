package fun.fengwk.kkstudio.studio.canvas;

/**
 * Write port for Canvas commands.
 *
 * <p>Persistence, revision CAS and idempotency are owned by adapters. This interface is the domain
 * boundary used by web/core.
 */
public interface CanvasCommandService {

  CanvasDocument createCanvas(long workspaceId, String title);

  CanvasDocument renameCanvas(long canvasId, long baseRevision, String title);

  CanvasSnapshot applyCommands(
      long canvasId, long baseRevision, String commandId, String requestHash, String commandsJson);
}
