package fun.fengwk.kkstudio.platform.environment.operation;

import fun.fengwk.kkstudio.platform.error.AiDuplicateException;

import java.util.Objects;
import java.util.UUID;

/**
 * 同一 (environment_id, source_id) 已存在未终结操作冲突；映射为 HTTP 409。
 *
 * <p>本异常绝不回显任何调用参数或凭证。
 */
public class DuplicateActiveOperationException extends AiDuplicateException {

  private final UUID environmentId;
  private final UUID sourceId;

  public DuplicateActiveOperationException(UUID environmentId, UUID sourceId) {
    super(
        "environment_operation",
        "active operation already exists for environment "
            + Objects.requireNonNull(environmentId, "environmentId")
            + " and source "
            + Objects.requireNonNull(sourceId, "sourceId"));
    this.environmentId = environmentId;
    this.sourceId = sourceId;
  }

  public DuplicateActiveOperationException(UUID environmentId, UUID sourceId, Throwable cause) {
    super(
        "environment_operation",
        "active operation already exists for environment "
            + Objects.requireNonNull(environmentId, "environmentId")
            + " and source "
            + Objects.requireNonNull(sourceId, "sourceId"),
        cause);
    this.environmentId = environmentId;
    this.sourceId = sourceId;
  }

  public UUID getEnvironmentId() {
    return environmentId;
  }

  public UUID getSourceId() {
    return sourceId;
  }
}
