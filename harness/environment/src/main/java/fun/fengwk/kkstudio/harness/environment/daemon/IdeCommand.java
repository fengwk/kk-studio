package fun.fengwk.kkstudio.harness.environment.daemon;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条 IDE 控制命令（Server → Daemon，{@code IDE_COMMAND} 的 payload）。
 *
 * <p>字段与跨切片契约 {@code daemon/src/contracts.ts} 的 {@code IdeCommand} 一一对应；本类型承担 wire 边界校验： 只保证语义安全（规范
 * UUID、合法 URL/路径/header、未知组合拒绝），不解释宿主文件系统事实——目录是否可访问、文档是否 dirty 一律由 Daemon 按 Node 标准失败回执，Server
 * 不伪造结果。
 *
 * <p>{@code OPEN_STREAM} 的 {@code streamUrl} 是 Server 当前实例受控的 endpoint；客户端（浏览器）不得指定任意 proxy
 * 地址，因此这里拒绝 userinfo、非 http(s) scheme 与 fragment，只保留受控 origin + path + query。
 */
public record IdeCommand(
    IdeAction action,
    String viewId,
    String directory,
    String ticket,
    String streamUrl,
    IdeStreamMode mode,
    String method,
    String path,
    List<IdeHeader> headers) {

  /** 与 {@code EnvironmentId} 一致的规范 UUID 文本长度。 */
  private static final int MAX_DIRECTORY_CHARS = 4096;

  private static final int MAX_TICKET_CHARS = 512;
  private static final int MAX_URL_CHARS = 2048;
  private static final int MAX_METHOD_CHARS = 16;
  private static final int MAX_PATH_CHARS = 8192;
  private static final int MAX_HEADERS = 64;

  public IdeCommand {
    action = Objects.requireNonNull(action, "action");
    viewId = validateViewId(viewId);
    directory = validateOptionalDirectory(directory);
    headers = headers == null ? List.of() : List.copyOf(headers);
    if (headers.size() > MAX_HEADERS) {
      throw new IllegalArgumentException("headers must not exceed " + MAX_HEADERS + " entries");
    }
    switch (action) {
      case OPEN_VIEW -> requireAbsent(ticket, streamUrl, mode, method, path, "OPEN_VIEW");
      case CHANGE_DIRECTORY -> {
        if (directory == null) {
          throw new IllegalArgumentException("CHANGE_DIRECTORY requires directory");
        }
        requireAbsent(ticket, streamUrl, mode, method, path, "CHANGE_DIRECTORY");
      }
      case FOCUS_TERMINAL, CLOSE_VIEW -> {
        if (directory != null) {
          throw new IllegalArgumentException(action + " must not declare directory");
        }
        requireAbsent(ticket, streamUrl, mode, method, path, action.name());
      }
      case OPEN_STREAM -> {
        if (directory != null) {
          throw new IllegalArgumentException("OPEN_STREAM must not declare directory");
        }
        ticket = validateTicket(ticket);
        streamUrl = validateStreamUrl(streamUrl);
        if (mode == null) {
          throw new IllegalArgumentException("OPEN_STREAM requires mode");
        }
        if (mode == IdeStreamMode.HTTP) {
          method = validateMethod(method);
          path = validatePath(path);
        } else if (method != null || path != null) {
          throw new IllegalArgumentException("WS OPEN_STREAM must not declare method or path");
        }
      }
    }
    // 非流式动作不允许携带 headers：headers 只在 OPEN_STREAM 的原始请求转发中使用。
    if (action != IdeAction.OPEN_STREAM && !headers.isEmpty()) {
      throw new IllegalArgumentException(action + " must not declare headers");
    }
  }

  /** 简单构造 OPEN_VIEW / CHANGE_DIRECTORY / FOCUS_TERMINAL / CLOSE_VIEW 命令。 */
  public static IdeCommand view(IdeAction action, String viewId, String directory) {
    return new IdeCommand(action, viewId, directory, null, null, null, null, null, List.of());
  }

  /** 校验 viewId 为规范小写 UUID。 */
  public static String validateViewId(String value) {
    Objects.requireNonNull(value, "viewId");
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("viewId must be a canonical UUID");
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException("viewId must be a canonical lowercase UUID");
    }
    return value;
  }

  static String validateOptionalDirectory(String value) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException("directory must not be blank when present");
    }
    if (value.length() > MAX_DIRECTORY_CHARS) {
      throw new IllegalArgumentException(
          "directory exceeds " + MAX_DIRECTORY_CHARS + " characters");
    }
    if (IdeHeader.containsControlCharacters(value)) {
      throw new IllegalArgumentException("directory must not contain control characters");
    }
    return value;
  }

  static String validateTicket(String value) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("OPEN_STREAM requires a ticket");
    }
    if (value.length() > MAX_TICKET_CHARS) {
      throw new IllegalArgumentException("ticket exceeds " + MAX_TICKET_CHARS + " characters");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean base64UrlChar =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '_';
      if (!base64UrlChar) {
        throw new IllegalArgumentException("ticket must be base64url text");
      }
    }
    return value;
  }

  static String validateStreamUrl(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("OPEN_STREAM requires a streamUrl");
    }
    if (value.length() > MAX_URL_CHARS) {
      throw new IllegalArgumentException("streamUrl exceeds " + MAX_URL_CHARS + " characters");
    }
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException error) {
      throw new IllegalArgumentException("streamUrl must be a valid absolute URI");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new IllegalArgumentException("streamUrl scheme must be http or https");
    }
    if (!uri.isAbsolute() || uri.getHost() == null) {
      throw new IllegalArgumentException(
          "streamUrl must be an absolute http(s) origin with a host");
    }
    if (uri.getUserInfo() != null) {
      throw new IllegalArgumentException("streamUrl must not contain userinfo");
    }
    if (uri.getFragment() != null) {
      throw new IllegalArgumentException("streamUrl must not contain a fragment");
    }
    return value;
  }

  static String validateMethod(String value) {
    if (value == null || value.isEmpty() || value.length() > MAX_METHOD_CHARS) {
      throw new IllegalArgumentException("HTTP OPEN_STREAM requires a bounded method");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 'A' || c > 'Z') {
        throw new IllegalArgumentException("method must be an uppercase HTTP token");
      }
    }
    return value;
  }

  static String validatePath(String value) {
    if (value == null || value.isEmpty() || value.length() > MAX_PATH_CHARS) {
      throw new IllegalArgumentException("HTTP OPEN_STREAM requires a bounded path");
    }
    if (!value.startsWith("/") || value.startsWith("//")) {
      throw new IllegalArgumentException("path must be an IDE-relative URL path");
    }
    if (IdeHeader.containsControlCharacters(value)) {
      throw new IllegalArgumentException("path must not contain control characters");
    }
    return value;
  }

  private static void requireAbsent(
      String ticket,
      String streamUrl,
      IdeStreamMode mode,
      String method,
      String path,
      String context) {
    if (ticket != null || streamUrl != null || mode != null || method != null || path != null) {
      throw new IllegalArgumentException(context + " must not declare stream fields");
    }
  }
}
