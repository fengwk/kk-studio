package fun.fengwk.kkstudio.platform.environment.operation;

import java.util.Objects;
import java.util.UUID;

/** 本地节点过期 RUNNING 推进至 UNKNOWN 后的轻量认领信息，用于取消内存中的活跃执行句柄（包内私有）。 */
public record SweptOperationInfo(UUID id, UUID ownerNodeId, UUID leaseToken) {

  public SweptOperationInfo {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    Objects.requireNonNull(leaseToken, "leaseToken");
  }

  @Override
  public String toString() {
    return "SweptOperationInfo[id=" + id + ", ownerNodeId=" + ownerNodeId + ", leaseToken=***]";
  }
}
