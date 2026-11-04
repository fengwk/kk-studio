package fun.fengwk.kkstudio.canvas;

import java.util.Objects;
import java.util.UUID;

/** Canvas command batch 幂等键对应的持久化事实：请求指纹与首次接受位置。 */
public record CommandDedup(
    UUID canvasId, UUID idempotencyKey, String requestHash, long acceptedRevision) {

  public CommandDedup {
    canvasId = Objects.requireNonNull(canvasId, "canvasId");
    idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    requestHash = Objects.requireNonNull(requestHash, "requestHash");
    if (acceptedRevision < 0L) {
      throw new IllegalArgumentException("acceptedRevision must be >= 0");
    }
  }
}
