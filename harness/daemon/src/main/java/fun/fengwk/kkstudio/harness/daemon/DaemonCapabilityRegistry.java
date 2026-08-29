package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Environment Daemon 本地可执行 Environment Capability 的注册表。 */
public final class DaemonCapabilityRegistry {

  private final Map<EnvironmentCapabilityId, EnvironmentCapability> capabilities =
      new LinkedHashMap<>();
  private boolean frozen;

  /** 注册一个由本 Daemon 本地执行的 capability。 */
  public synchronized void register(EnvironmentCapability capability) {
    if (frozen) {
      throw new IllegalStateException("capability registry is frozen");
    }
    capability = Objects.requireNonNull(capability, "capability");
    EnvironmentCapabilityDescriptor descriptor =
        Objects.requireNonNull(capability.descriptor(), "capability.descriptor()");
    if (capabilities.putIfAbsent(descriptor.id(), capability) != null) {
      throw new IllegalArgumentException("capability is already registered: " + descriptor.id());
    }
  }

  /** 按稳定 typed capability ID 查询本地实现。 */
  public synchronized Optional<EnvironmentCapability> find(EnvironmentCapabilityId capabilityId) {
    return Optional.ofNullable(
        capabilities.get(Objects.requireNonNull(capabilityId, "capabilityId")));
  }

  /** 返回本地注册 capability 的稳定描述列表。 */
  public synchronized Collection<EnvironmentCapabilityDescriptor> descriptors() {
    return capabilities.values().stream().map(EnvironmentCapability::descriptor).toList();
  }

  synchronized void freeze() {
    frozen = true;
  }
}
