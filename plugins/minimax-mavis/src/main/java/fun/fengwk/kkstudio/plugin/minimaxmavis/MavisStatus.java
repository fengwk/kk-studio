package fun.fengwk.kkstudio.plugin.minimaxmavis;

import tools.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Mavis 响应的业务状态分层校验。
 *
 * <p>网关在不同端点上依次使用三种状态层：桌面私有端点先看 {@code statusInfo.code}，再看 {@code base_resp.status_code}，最后看顶层
 * {@code code}；MCP 端点只看 {@code base_resp.status_code} 与顶层 {@code code}。缺失、{@code null}、数值 {@code 0}
 * 与字符串 {@code "0"} 都表示成功，其余取值表示失败，其中 {@code 401} 与 {@code 1004} 是认证被拒绝。
 */
public final class MavisStatus {

  private static final Set<String> AUTH_CODES = Set.of("401", "1004");
  private static final Pattern NUMERIC_CODE = Pattern.compile("-?[0-9]{1,9}");
  private static final String UNEXPECTED_STATUS = "unexpected-status";

  private MavisStatus() {}

  /** 桌面私有端点（catalog、renewal）的分层状态；按观测顺序返回第一个失败状态。 */
  public static Optional<JsonNode> privateStatus(JsonNode payload) {
    JsonNode statusInfo = payload.get("statusInfo");
    if (statusInfo != null && statusInfo.isObject()) {
      Optional<JsonNode> code = unsuccessful(statusInfo.get("code"));
      if (code.isPresent()) {
        return code;
      }
    }
    return businessStatus(payload);
  }

  /** MCP 业务端点的状态；按观测顺序返回第一个失败状态。 */
  public static Optional<JsonNode> businessStatus(JsonNode payload) {
    JsonNode baseResp = payload.get("base_resp");
    if (baseResp != null && baseResp.isObject()) {
      Optional<JsonNode> code = unsuccessful(baseResp.get("status_code"));
      if (code.isPresent()) {
        return code;
      }
    }
    return unsuccessful(payload.get("code"));
  }

  /** 判断失败状态是否为确定性认证拒绝。 */
  public static boolean isAuthCode(JsonNode code) {
    return AUTH_CODES.contains(render(code));
  }

  /** 把失败状态转换为类型化错误：{@code 401} / {@code 1004} 为认证错误，其余为业务错误。 */
  public static MavisException raise(JsonNode code, String context, String serverMessage) {
    if (isAuthCode(code)) {
      return new MavisAuthException(MavisAuthException.REJECTED_MESSAGE);
    }
    return new MavisBusinessException(context, render(code), serverMessage);
  }

  /**
   * 状态码的稳定文本形式。
   *
   * <p>远端只能影响一个短数值：文本必须是规范十进制整数，数值也受长度约束。其他取值退化为固定文本，避免把服务端原文（可能含凭据）带进错误消息。
   */
  public static String render(JsonNode code) {
    if (code == null || code.isNull()) {
      return "";
    }
    if (code.isNumber() || code.isTextual()) {
      String text = code.asText();
      if (NUMERIC_CODE.matcher(text).matches()) {
        return text;
      }
    }
    return UNEXPECTED_STATUS;
  }

  private static Optional<JsonNode> unsuccessful(JsonNode code) {
    if (code == null || code.isNull()) {
      return Optional.empty();
    }
    if (code.isNumber() && code.doubleValue() == 0) {
      return Optional.empty();
    }
    if (code.isTextual() && "0".equals(code.textValue().trim())) {
      return Optional.empty();
    }
    return Optional.of(code);
  }
}
