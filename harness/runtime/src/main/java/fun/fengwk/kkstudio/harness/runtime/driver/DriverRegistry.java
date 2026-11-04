package fun.fengwk.kkstudio.harness.runtime.driver;

import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.spi.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.spi.driver.DriverCapabilities;
import fun.fengwk.kkstudio.harness.spi.driver.DriverThreadDeletion;
import fun.fengwk.kkstudio.harness.spi.driver.DriverWork;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** 集中管理全部装配的 AgentDriver，按 Thread 的执行选择路由到对应的 Driver。 */
public final class DriverRegistry implements DriverThreadDeletion {

  private final Map<String, AgentDriver> drivers;
  private final UninstalledAgentDriver uninstalled = new UninstalledAgentDriver();

  public DriverRegistry(Collection<AgentDriver> drivers) {
    Objects.requireNonNull(drivers, "drivers");
    Map<String, AgentDriver> map = new HashMap<>();
    for (AgentDriver driver : drivers) {
      Objects.requireNonNull(driver, "driver");
      String id = Objects.requireNonNull(driver.id(), "driver.id");
      if (map.put(id, driver) != null) {
        throw new IllegalArgumentException("duplicate driver id: " + id);
      }
    }
    this.drivers = Map.copyOf(map);
  }

  public DriverRegistry(AgentDriver... drivers) {
    this(List.of(drivers));
  }

  public AgentDriver of(BranchSettings settings) {
    Objects.requireNonNull(settings, "settings");
    return of(settings.execution().driver());
  }

  public AgentDriver of(String driverId) {
    Objects.requireNonNull(driverId, "driverId");
    AgentDriver driver = drivers.get(driverId);
    return driver != null ? driver : uninstalled;
  }

  public void validate(ExecutionSelection selection) {
    Objects.requireNonNull(selection, "selection");
    AgentDriver driver = drivers.get(selection.driver());
    if (driver == null) {
      throw new IllegalArgumentException("driver is not installed: " + selection.driver());
    }
    driver.validateSettings(selection.settingsJson());
  }

  public DriverCapabilities capabilities(String driverId) {
    Objects.requireNonNull(driverId, "driverId");
    AgentDriver driver = drivers.get(driverId);
    if (driver == null) {
      throw new IllegalArgumentException("driver is not installed: " + driverId);
    }
    return driver.capabilities();
  }

  /** 汇总全部 Driver 注册的 Work 处理器；内核 THREAD / TOOL 由组合根另行装配。 */
  public Map<WorkTargetType, Consumer<ClaimedWork>> workHandlers() {
    Map<WorkTargetType, Consumer<ClaimedWork>> handlers = new LinkedHashMap<>();
    for (AgentDriver driver : drivers.values()) {
      for (DriverWork work : driver.works()) {
        Objects.requireNonNull(work, "driver work");
        WorkTargetType type = work.type();
        if (WorkTargetType.THREAD.equals(type) || WorkTargetType.TOOL.equals(type)) {
          throw new IllegalArgumentException(
              "driver " + driver.id() + " must not register kernel work type " + type);
        }
        if (handlers.putIfAbsent(type, work::process) != null) {
          throw new IllegalArgumentException("duplicate driver work type: " + type);
        }
      }
    }
    return Map.copyOf(handlers);
  }

  /** Stop 提交后 best-effort 取消；在 DriverRegistry 边界只串行调用，不改变 Stop 已提交事实。 */
  public void cancelWork(UUID executionId) {
    Objects.requireNonNull(executionId, "executionId");
    for (AgentDriver driver : drivers.values()) {
      driver.cancelWork(executionId);
    }
  }

  /** 本机是否仍有任一 Driver 执行。 */
  public boolean hasActiveWork() {
    for (AgentDriver driver : drivers.values()) {
      if (driver.hasActiveWork()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public List<WorkTarget> lockForDeletion(HarnessStore.Transaction tx, List<UUID> threadIds) {
    List<WorkTarget> targets = new ArrayList<>();
    for (AgentDriver driver : orderedDrivers()) {
      targets.addAll(driver.lockThreadStateForDeletion(tx, threadIds));
    }
    return List.copyOf(targets);
  }

  @Override
  public void deleteLocked(
      HarnessStore.Transaction tx, List<UUID> threadIds, List<WorkTarget> lockedWorkTargets) {
    for (AgentDriver driver : orderedDrivers()) {
      driver.deleteLockedThreadState(tx, threadIds, lockedWorkTargets);
    }
  }

  private List<AgentDriver> orderedDrivers() {
    return drivers.values().stream()
        .sorted((left, right) -> left.id().compareTo(right.id()))
        .toList();
  }
}
