package fun.fengwk.kkstudio.studio.canvas;

/**
 * Write port for Canvas commands.
 *
 * <p>Persistence, revision CAS and idempotency are owned by adapters. This interface is the domain
 * boundary used by web/core.
 *
 * <p>The idempotency key is the {@code commandId} (UUID/ULID-style); the request payload hash is
 * computed server-side from the canonical {@code commandsJson} so clients cannot accidentally or
 * intentionally collide on it.
 */
public interface CanvasCommandService {

  CanvasDocument createCanvas(String title);

  CanvasSnapshot applyCommands(
      long canvasId, long baseRevision, String commandId, String commandsJson);
}
