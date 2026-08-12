package fun.fengwk.kkstudio.harness.runtime.work;

import java.util.Objects;
import java.util.UUID;

/** Work mailbox target 的多态身份。 */
public record WorkTarget(WorkTargetType type, UUID id) {

  public WorkTarget {
    type = Objects.requireNonNull(type, "type");
    Objects.requireNonNull(id, "id");
  }
}
