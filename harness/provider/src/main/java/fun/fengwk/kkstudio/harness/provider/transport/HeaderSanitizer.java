package fun.fengwk.kkstudio.harness.provider.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 统一的 HTTP 标头敏感信息清洗器。
 *
 * <p>确保所有对外暴露的元数据或异常信息绝不包含敏感 Token、API Key、Cookie、凭证等。 遵循 Locale.ROOT，匹配敏感关键词与安全过滤规则，并严格拒绝或剔除包含
 * CR/LF 注入的非法标头值。
 */
final class HeaderSanitizer {

  private static final String REDACTED = "[REDACTED]";

  private static final Set<String> ALLOWLISTED_HEADERS =
      Set.of(
          "content-type",
          "content-length",
          "retry-after",
          "x-ratelimit-limit",
          "x-ratelimit-remaining",
          "x-ratelimit-reset",
          "x-ratelimit-reset-requests",
          "x-ratelimit-reset-tokens",
          "x-request-id",
          "request-id");

  private static final Set<String> SENSITIVE_KEYWORDS =
      Set.of("token", "key", "auth", "cookie", "secret", "credential");

  private HeaderSanitizer() {}

  /** 判断给定的标头名称是否包含敏感关键词。 */
  public static boolean isSensitiveHeader(String headerName) {
    if (headerName == null || headerName.isBlank()) {
      return false;
    }
    String lower = headerName.toLowerCase(Locale.ROOT);
    for (String keyword : SENSITIVE_KEYWORDS) {
      if (lower.contains(keyword)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 清洗标头集合。
   *
   * <p>仅保留协议诊断真实需要且值可安全规范化的最小白名单标头，丢弃所有未知标头。 对命中敏感词的标头无条件覆盖其值为 "[REDACTED]"，对值中包含 CR/LF 的进行剔除，
   * 返回按大小写不敏感排序的不可变 Map。
   */
  public static Map<String, List<String>> sanitizeHeaders(Map<String, List<String>> headers) {
    if (headers == null || headers.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, List<String>> clean = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.forEach(
        (name, values) -> {
          if (name == null || name.isBlank()) {
            return;
          }
          String lower = name.toLowerCase(Locale.ROOT);
          if (!ALLOWLISTED_HEADERS.contains(lower)) {
            // 未在严格白名单内的未知标头直接丢弃，杜绝回显凭据或敏感调试字段泄露
            return;
          }
          if (isSensitiveHeader(lower)) {
            clean.put(lower, List.of(REDACTED));
          } else if (values != null) {
            List<String> sanitizedValues = new ArrayList<>(values.size());
            for (String val : values) {
              if (val != null) {
                // 剔除 CR/LF，防止日志或响应拆分注入
                String safeVal = val.replace("\r", "").replace("\n", "").trim();
                sanitizedValues.add(safeVal);
              }
            }
            clean.put(lower, Collections.unmodifiableList(sanitizedValues));
          } else {
            clean.put(lower, List.of());
          }
        });
    return Collections.unmodifiableMap(clean);
  }
}
