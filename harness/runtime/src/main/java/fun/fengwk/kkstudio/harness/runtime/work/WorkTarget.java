package fun.fengwk.kkstudio.harness.runtime.work;

import java.util.Objects;

/** Polymorphic identity of a Work mailbox target. */
public record WorkTarget(WorkTargetType type, long id) {

  public WorkTarget {
    type = Objects.requireNonNull(type, "type");
    if (id <= 0) {
      throw new IllegalArgumentException("target id must be positive");
    }
  }
}
