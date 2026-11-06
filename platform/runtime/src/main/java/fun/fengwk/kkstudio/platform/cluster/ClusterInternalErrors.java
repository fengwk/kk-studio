package fun.fengwk.kkstudio.platform.cluster;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 只读取有界内部 problem 响应的稳定类别、原因及去敏详情。 */
public final class ClusterInternalErrors {

  private static final int MAX_DETAIL_LENGTH = 500;

  private ClusterInternalErrors() {}

  public static Problem parse(ObjectMapper mapper, byte[] body) {
    if (body == null || body.length == 0) {
      return new Problem(null, null, null);
    }
    try {
      JsonNode root = mapper.readTree(body);
      String detail = text(root, "detail");
      if (detail != null && detail.length() > MAX_DETAIL_LENGTH) {
        detail = detail.substring(0, MAX_DETAIL_LENGTH);
      }
      return new Problem(text(root, "code"), text(root, "reason"), detail);
    } catch (JacksonException error) {
      return new Problem(null, null, null);
    }
  }

  private static String text(JsonNode root, String name) {
    JsonNode value = root == null ? null : root.get(name);
    return value != null && value.isString() && !value.stringValue().isBlank()
        ? value.stringValue()
        : null;
  }

  public record Problem(String code, String reason, String detail) {}
}
