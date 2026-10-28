package fun.fengwk.kkstudio.ide.protocol;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * IDE 控制命令的回执（Daemon → Server，{@code IDE_RESULT} 的 payload）。
 *
 * <p>成功回执携带 Daemon 确认的绑定身份与目录；失败回执只携带安全的 {@code code}/{@code message}。{@code code} 是固定大写 token，
 * {@code message} 去敏且不超过 500 字符，Server 不把宿主异常原文透出给浏览器。
 */
public record IdeReply(
    boolean ok,
    UUID viewId,
    UUID documentId,
    UUID eventNodeId,
    UUID connectionId,
    UUID claimId,
    Long bindingEpoch,
    String directory,
    String workspacePath,
    List<IdeViewSummary> views,
    String code,
    String message) {

  private static final int MAX_CODE_CHARS = 64;
  private static final int MAX_MESSAGE_CHARS = 500;
  private static final int MAX_VIEWS = 64;

  public IdeReply {
    if (bindingEpoch != null && bindingEpoch <= 0) {
      throw new IllegalArgumentException("bindingEpoch must be a positive long");
    }
    directory = validateOptionalPath(directory, "directory");
    workspacePath = validateOptionalPath(workspacePath, "workspacePath");
    if (views != null) {
      if (views.size() > MAX_VIEWS) {
        throw new IllegalArgumentException("views must not exceed " + MAX_VIEWS + " entries");
      }
      Set<UUID> viewIds = new HashSet<>();
      for (IdeViewSummary view : views) {
        Objects.requireNonNull(view, "view");
        if (!viewIds.add(view.viewId())) {
          throw new IllegalArgumentException("views must not repeat a viewId");
        }
      }
      views = List.copyOf(views);
    }
    code = validateOptionalCode(code);
    message = validateOptionalMessage(message);
    if (ok) {
      if (code != null || message != null) {
        throw new IllegalArgumentException("successful reply must not declare code/message");
      }
    } else {
      if (code == null || message == null) {
        throw new IllegalArgumentException("failed reply requires code and message");
      }
      if (viewId != null
          || documentId != null
          || eventNodeId != null
          || connectionId != null
          || claimId != null
          || bindingEpoch != null
          || directory != null
          || workspacePath != null
          || views != null) {
        throw new IllegalArgumentException("failed reply must not declare view fields");
      }
    }
  }

  static String validateOptionalPath(String value, String field) {
    if (value == null) {
      return null;
    }
    return IdeViewSummary.requirePath(value, field);
  }

  static String validateOptionalCode(String value) {
    if (value == null) {
      return null;
    }
    if (value.isEmpty() || value.length() > MAX_CODE_CHARS) {
      throw new IllegalArgumentException("code must be a bounded uppercase token");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean token = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
      if (!token) {
        throw new IllegalArgumentException("code must be a bounded uppercase token");
      }
    }
    return value;
  }

  static String validateOptionalMessage(String value) {
    if (value == null) {
      return null;
    }
    if (value.length() > MAX_MESSAGE_CHARS) {
      throw new IllegalArgumentException("message exceeds " + MAX_MESSAGE_CHARS + " characters");
    }
    if (IdeHeader.containsControlCharacters(value)) {
      throw new IllegalArgumentException("message must not contain control characters");
    }
    return value;
  }
}
