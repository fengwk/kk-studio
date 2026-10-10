package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.UUID;

/**
 * IDE 控制命令的回执（Daemon → Server，{@code IDE_RESULT} 的 payload）。
 *
 * <p>与跨切片契约 {@code daemon/src/contracts.ts} 的 {@code IdeReply} 对应。成功时 {@code ok=true}；失败时 {@code
 * ok=false} 且只携带固定安全文案的 {@code message}，不泄漏宿主路径细节之外的内部异常原文。
 */
public record IdeReply(
    boolean ok, String viewId, String directory, String workspacePath, String message) {

  private static final int MAX_PATH_CHARS = 4096;
  private static final int MAX_MESSAGE_CHARS = 2048;

  public IdeReply {
    viewId = validateOptionalViewId(viewId);
    directory = validateOptionalText(directory, "directory");
    workspacePath = validateOptionalText(workspacePath, "workspacePath");
    message = validateOptionalText(message, "message");
    if (message != null && message.length() > MAX_MESSAGE_CHARS) {
      throw new IllegalArgumentException("message exceeds " + MAX_MESSAGE_CHARS + " characters");
    }
  }

  public static IdeReply ok(String viewId, String directory, String workspacePath) {
    return new IdeReply(true, viewId, directory, workspacePath, null);
  }

  public static IdeReply failure(String message) {
    return new IdeReply(false, null, null, null, message);
  }

  static String validateOptionalViewId(String value) {
    if (value == null) {
      return null;
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("reply viewId must be a canonical UUID");
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException("reply viewId must be a canonical lowercase UUID");
    }
    return value;
  }

  static String validateOptionalText(String value, String field) {
    if (value == null) {
      return null;
    }
    if (value.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be empty when present");
    }
    if (value.length() > MAX_PATH_CHARS) {
      throw new IllegalArgumentException(field + " exceeds " + MAX_PATH_CHARS + " characters");
    }
    if (IdeHeader.containsControlCharacters(value)) {
      throw new IllegalArgumentException(field + " must not contain control characters");
    }
    return value;
  }
}
