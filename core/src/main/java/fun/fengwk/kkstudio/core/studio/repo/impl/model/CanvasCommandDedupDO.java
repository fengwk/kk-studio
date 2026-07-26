package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

/**
 * {@code canvas_command_dedup} row mapping: pure idempotency fact keyed by {@code (canvas_id,
 * command_id)}. The {@code request_hash} is the server-computed SHA-256 of the canonicalized
 * command payload.
 */
@Data
public class CanvasCommandDedupDO {
  /** Owning canvas id. */
  private Long canvasId;

  /** Client-side idempotency key. */
  private String commandId;

  /** SHA-256 hex of the canonical command payload. */
  private String requestHash;
}
