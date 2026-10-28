package fun.fengwk.kkstudio.ide.protocol;

import java.util.Objects;
import java.util.UUID;

/**
 * {@code LIST_VIEWS} 回执中的一条视图摘要（Server 只把它用于恢复路由快照与租约核对，不据此创建视图）。
 *
 * <p>{@code eventNodeId}/{@code connectionId}/{@code claimId} 是浏览器事件连接的关联键，不是认证凭据；{@code
 * directory}/{@code workspacePath} 只接受 Daemon 回执，客户端输入永不进入摘要。
 */
public record IdeViewSummary(
    UUID viewId,
    UUID documentId,
    UUID eventNodeId,
    UUID connectionId,
    UUID claimId,
    long bindingEpoch,
    String directory,
    String workspacePath,
    IdeViewState state) {

  private static final int MAX_PATH_CHARS = 4096;

  public IdeViewSummary {
    viewId = Objects.requireNonNull(viewId, "viewId");
    documentId = Objects.requireNonNull(documentId, "documentId");
    eventNodeId = Objects.requireNonNull(eventNodeId, "eventNodeId");
    connectionId = Objects.requireNonNull(connectionId, "connectionId");
    claimId = Objects.requireNonNull(claimId, "claimId");
    if (bindingEpoch <= 0) {
      throw new IllegalArgumentException("bindingEpoch must be a positive long");
    }
    directory = requirePath(directory, "directory");
    workspacePath = requirePath(workspacePath, "workspacePath");
    state = Objects.requireNonNull(state, "state");
  }

  static String requirePath(String value, String field) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be empty");
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
