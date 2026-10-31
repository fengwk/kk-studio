package fun.fengwk.kkstudio.ide.protocol;

import fun.fengwk.kkstudio.common.number.DecimalLongs;

import java.util.Objects;
import java.util.UUID;

/**
 * 一条 IDE 控制命令（Server → Daemon，{@code IDE_COMMAND} 的 payload）。
 *
 * <p>字段与冻结契约 {@code daemon/src/contracts.ts} 的 {@code IdeCommand} 一一对应。{@code OPEN_VIEW}
 * 携带浏览器事件连接身份与 claimId；需要绑定身份的其它命令必须携带 {@code documentId} 与 {@code bindingEpoch}。Server 只做 wire
 * 语义校验，目录是否可访问、文档是否 dirty 一律由 Daemon 回执，不在这里推断。
 */
public record IdeCommand(
    IdeAction action,
    String viewId,
    String documentId,
    String eventNodeId,
    String connectionId,
    String claimId,
    Long bindingEpoch,
    IdeOpenMode mode,
    String directory,
    Boolean discard) {

  private static final int MAX_DIRECTORY_CHARS = 4096;

  public IdeCommand {
    action = Objects.requireNonNull(action, "action");
    viewId = validateOptionalUuid(viewId, "viewId");
    documentId = validateOptionalUuid(documentId, "documentId");
    eventNodeId = validateOptionalUuid(eventNodeId, "eventNodeId");
    connectionId = validateOptionalUuid(connectionId, "connectionId");
    claimId = validateOptionalUuid(claimId, "claimId");
    if (bindingEpoch != null && bindingEpoch <= 0) {
      throw new IllegalArgumentException("bindingEpoch must be a positive long");
    }
    directory = validateOptionalDirectory(directory);
    switch (action) {
      case OPEN_VIEW -> {
        require(viewId, "viewId");
        require(documentId, "documentId");
        require(eventNodeId, "eventNodeId");
        require(connectionId, "connectionId");
        require(claimId, "claimId");
        require(mode, "mode");
        requireAbsent(bindingEpoch, discard, "OPEN_VIEW");
      }
      case CHANGE_DIRECTORY -> {
        requireBoundIdentity(viewId, documentId, bindingEpoch, "CHANGE_DIRECTORY");
        require(directory, "directory");
        requireAbsent(mode, eventNodeId, connectionId, claimId, discard, "CHANGE_DIRECTORY");
      }
      case FOCUS_TERMINAL, DETACH_VIEW -> {
        requireBoundIdentity(viewId, documentId, bindingEpoch, action.name());
        requireAbsent(mode, directory, eventNodeId, connectionId, claimId, discard, action.name());
      }
      case CLOSE_VIEW -> {
        requireBoundIdentity(viewId, documentId, bindingEpoch, "CLOSE_VIEW");
        require(discard, "discard");
        requireAbsent(mode, directory, eventNodeId, connectionId, claimId, "CLOSE_VIEW");
      }
      case LIST_VIEWS -> {
        if (viewId != null
            || documentId != null
            || eventNodeId != null
            || connectionId != null
            || claimId != null
            || bindingEpoch != null
            || mode != null
            || directory != null
            || discard != null) {
          throw new IllegalArgumentException("LIST_VIEWS must not declare identity fields");
        }
      }
    }
  }

  public static IdeCommand openView(
      String viewId,
      String documentId,
      String eventNodeId,
      String connectionId,
      String claimId,
      IdeOpenMode mode,
      String directory) {
    return new IdeCommand(
        IdeAction.OPEN_VIEW,
        viewId,
        documentId,
        eventNodeId,
        connectionId,
        claimId,
        null,
        mode,
        directory,
        null);
  }

  public static IdeCommand changeDirectory(
      String viewId, String documentId, long bindingEpoch, String directory) {
    return new IdeCommand(
        IdeAction.CHANGE_DIRECTORY,
        viewId,
        documentId,
        null,
        null,
        null,
        bindingEpoch,
        null,
        directory,
        null);
  }

  public static IdeCommand focusTerminal(String viewId, String documentId, long bindingEpoch) {
    return new IdeCommand(
        IdeAction.FOCUS_TERMINAL,
        viewId,
        documentId,
        null,
        null,
        null,
        bindingEpoch,
        null,
        null,
        null);
  }

  public static IdeCommand closeView(
      String viewId, String documentId, long bindingEpoch, boolean discard) {
    return new IdeCommand(
        IdeAction.CLOSE_VIEW,
        viewId,
        documentId,
        null,
        null,
        null,
        bindingEpoch,
        null,
        null,
        discard);
  }

  public static IdeCommand detachView(String viewId, String documentId, long bindingEpoch) {
    return new IdeCommand(
        IdeAction.DETACH_VIEW,
        viewId,
        documentId,
        null,
        null,
        null,
        bindingEpoch,
        null,
        null,
        null);
  }

  public static IdeCommand listViews() {
    return new IdeCommand(
        IdeAction.LIST_VIEWS, null, null, null, null, null, null, null, null, null);
  }

  /** 校验 viewId 为规范小写 UUID；供 REST 路径与命令共用。 */
  public static String validateViewId(String value) {
    return validateOptionalUuid(value, "viewId");
  }

  /** 校验 UUID 文本为规范小写形式；{@code null} 保留为 {@code null}。 */
  static String validateOptionalUuid(String value, String field) {
    if (value == null) {
      return null;
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be a canonical UUID");
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical lowercase UUID");
    }
    return value;
  }

  /** 校验目录字段：非空白、无控制字符且不超长。 */
  public static String validateOptionalDirectory(String value) {
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

  /** 校验 wire 的规范正十进制 epoch 字符串，并解析为 long。 */
  public static long parseBindingEpoch(String value) {
    try {
      return DecimalLongs.parsePositive(value, "bindingEpoch");
    } catch (IllegalArgumentException error) {
      String message = error.getMessage();
      if (message != null && message.contains("exceeds long range")) {
        throw new IllegalArgumentException("bindingEpoch must fit in a signed 64-bit decimal");
      }
      throw new IllegalArgumentException("bindingEpoch must be a canonical positive decimal");
    }
  }

  private static void requireBoundIdentity(
      String viewId, String documentId, Long bindingEpoch, String context) {
    require(viewId, "viewId");
    require(documentId, "documentId");
    if (bindingEpoch == null) {
      throw new IllegalArgumentException(context + " requires bindingEpoch");
    }
  }

  private static void require(Object value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  private static void requireAbsent(Object... fields) {
    for (Object field : fields) {
      if (field != null) {
        throw new IllegalArgumentException("command declares a field that is not allowed");
      }
    }
  }

  private static void requireAbsent(Long bindingEpoch, Boolean discard, String context) {
    if (bindingEpoch != null || discard != null) {
      throw new IllegalArgumentException(context + " must not declare binding fields");
    }
  }

  private static void requireAbsent(
      IdeOpenMode mode,
      String eventNodeId,
      String connectionId,
      String claimId,
      Boolean discard,
      String context) {
    if (mode != null
        || eventNodeId != null
        || connectionId != null
        || claimId != null
        || discard != null) {
      throw new IllegalArgumentException(context + " must not declare open/close fields");
    }
  }

  private static void requireAbsent(
      IdeOpenMode mode,
      String directory,
      String eventNodeId,
      String connectionId,
      String claimId,
      Boolean discard,
      String context) {
    if (mode != null
        || directory != null
        || eventNodeId != null
        || connectionId != null
        || claimId != null
        || discard != null) {
      throw new IllegalArgumentException(context + " must not declare open/close fields");
    }
  }

  private static void requireAbsent(
      IdeOpenMode mode,
      String directory,
      String eventNodeId,
      String connectionId,
      String claimId,
      String context) {
    if (mode != null
        || directory != null
        || eventNodeId != null
        || connectionId != null
        || claimId != null) {
      throw new IllegalArgumentException(context + " must not declare open/directory fields");
    }
  }
}
