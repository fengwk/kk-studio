package fun.fengwk.kkstudio.platform.studio;

import java.util.Objects;
import java.util.UUID;

/** Chat/Canvas owner（{@code {CHAT|CANVAS, id}}）。归属授权与深删除都以此为单位，Chat 与 Canvas 必须共用同一份实现。 */
public record StudioOwner(StudioOwnerType type, UUID id) {

  public StudioOwner {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(id, "id");
  }

  @Override
  public String toString() {
    return type.name() + ":" + id;
  }
}
