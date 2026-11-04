package fun.fengwk.kkstudio.platform.plugin.support;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** 插件 HTTP 协议共享的严格解析、有界读取与调用期凭据辅助。 */
public final class PluginHttpSupport {

  private static final System.Logger LOGGER = System.getLogger(PluginHttpSupport.class.getName());

  private PluginHttpSupport() {}

  /** 保留宿主 mapper 的其余配置，同时禁止重复字段与尾随 JSON。 */
  public static ObjectMapper strictJson(ObjectMapper mapper) {
    return Objects.requireNonNull(mapper, "mapper")
        .rebuild()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();
  }

  /** 至多保留 limit 字节，超限时只多读一个字节以确认；由调用者关闭流。 */
  public static byte[] readBounded(InputStream input, int limit) throws IOException {
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
    byte[] buffer = new byte[8192];
    int remaining = limit;
    while (remaining > 0) {
      int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
      if (count == -1) {
        return output.toByteArray();
      }
      output.write(buffer, 0, count);
      remaining -= count;
    }
    if (input.read() != -1) {
      throw new ResponseTooLargeException();
    }
    return output.toByteArray();
  }

  /** 超限不携带远端正文。 */
  public static final class ResponseTooLargeException extends IOException {
    public ResponseTooLargeException() {
      super("response exceeds local limit");
    }
  }

  /** 只接受不含空格、控制字符或非 ASCII 的 header 凭据，错误不回显值。 */
  public static String bearer(String token) {
    if (token == null || token.isEmpty()) {
      throw new IllegalArgumentException("invalid bearer credential");
    }
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c <= 0x20 || c >= 0x7f) {
        throw new IllegalArgumentException("invalid bearer credential");
      }
    }
    return "Bearer " + token;
  }

  public static boolean isAuthRejected(int status) {
    return status == 401 || status == 403;
  }

  /** 只作废已用快照；作废失败不替换远端认证错误，也不记录异常消息。 */
  public static void rejectUsed(
      PluginCredentialAccess access, PluginCredentialSnapshot snapshot, String safeError) {
    try {
      access.rejectUsed(snapshot, safeError);
    } catch (RuntimeException error) {
      LOGGER.log(
          System.Logger.Level.DEBUG,
          "Credential rejection failed: {0}",
          error.getClass().getSimpleName());
    }
  }
}
