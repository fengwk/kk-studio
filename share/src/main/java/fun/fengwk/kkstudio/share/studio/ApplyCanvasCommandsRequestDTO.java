package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

/**
 * Body of {@code POST /api/canvases/{canvasId}/commands}.
 *
 * <p>Idempotency is enforced server-side: the request payload hash is recomputed from {@link
 * #commandsJson} on every call so the client does not (and cannot) supply a {@code requestHash}.
 */
@Data
public class ApplyCanvasCommandsRequestDTO {
  private String baseRevision;
  private String commandId;
  private String commandsJson;
}
