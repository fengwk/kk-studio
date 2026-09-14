package fun.fengwk.kkstudio.platform.orchestration;

import java.util.Objects;
import java.util.UUID;

/** 产品 owner 引用（类型与 UUID）；归属授权、查询和深删除都以此为单位。 */
public record OwnerRef(OwnerType type, UUID id) {

  public OwnerRef {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(id, "id");
  }

  @Override
  public String toString() {
    return type.name() + ":" + id;
  }
}
