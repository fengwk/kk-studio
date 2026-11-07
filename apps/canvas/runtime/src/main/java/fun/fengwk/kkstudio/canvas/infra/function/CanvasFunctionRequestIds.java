package fun.fengwk.kkstudio.canvas.infra.function;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.id.Uuids;

import java.util.UUID;

/** Function run requestId 的唯一客户端校验：canonical lowercase UUID。 */
final class CanvasFunctionRequestIds {

  private CanvasFunctionRequestIds() {}

  static UUID validate(String requestId) {
    try {
      return Uuids.parseCanonical(requestId, "requestId");
    } catch (IllegalArgumentException error) {
      throw DomainException.validation("requestId", "requestId must be a canonical lowercase UUID");
    }
  }
}
