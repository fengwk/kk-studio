package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;

import java.util.Objects;

/** 配置命令携带的完整、已冻结 Runtime 配置快照。 */
public record RuntimeConfigInputPayload(ThreadInputType type, RuntimeConfigSnapshot snapshot)
    implements ThreadInputPayload {

  public RuntimeConfigInputPayload {
    type = Objects.requireNonNull(type, "type");
    if (!type.isConfig()) {
      throw new IllegalArgumentException("runtime config payload requires a config input type");
    }
    snapshot = Objects.requireNonNull(snapshot, "snapshot");
  }
}
