package fun.fengwk.kkstudio.canvas.infra.function;

import fun.fengwk.kkstudio.common.error.DomainException;

/** Function run requestId 的唯一公开校验。 */
final class CanvasFunctionRequestIds {

  private CanvasFunctionRequestIds() {}

  static String validate(String requestId) {
    if (requestId == null
        || requestId.isEmpty()
        || requestId.length() > 128
        || !requestId.equals(requestId.strip())) {
      throw DomainException.validation(
          "requestId", "requestId must be non-empty, untrimmed, and at most 128 characters");
    }
    for (int index = 0; index < requestId.length(); index++) {
      if (Character.isISOControl(requestId.charAt(index))) {
        throw DomainException.validation(
            "requestId", "requestId must not contain control characters");
      }
    }
    return requestId;
  }
}
