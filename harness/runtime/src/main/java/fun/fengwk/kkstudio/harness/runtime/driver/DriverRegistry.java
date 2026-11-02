package fun.fengwk.kkstudio.harness.runtime.driver;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ExecutionSelection;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 集中管理全部装配的 AgentDriver，按 Thread 的执行选择路由到对应的 Driver。 */
public final class DriverRegistry {

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
}
