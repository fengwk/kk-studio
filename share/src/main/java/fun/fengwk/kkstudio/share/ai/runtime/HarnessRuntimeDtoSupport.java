package fun.fengwk.kkstudio.share.ai.runtime;

/** Harness Runtime 请求 DTO 的严格 JSON primitive 校验辅助。 */
final class HarnessRuntimeDtoSupport {

  private HarnessRuntimeDtoSupport() {}

  static String requireJsonString(Object value, String field) {
    if (value != null && !(value instanceof String)) {
      throw new HarnessRequestFormatException(field + " must be a JSON string");
    }
    return (String) value;
  }

  static Boolean requireJsonBoolean(Object value, String field) {
    if (value != null && !(value instanceof Boolean)) {
      throw new HarnessRequestFormatException(field + " must be a JSON boolean");
    }
    return (Boolean) value;
  }
}
