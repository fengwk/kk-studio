package fun.fengwk.kkstudio.harness.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.testing.FakeAgentDriver;

class DriverRegistryTest {
  @Test
  void routesThreadSettingsOnlyToRegisteredDrivers() {
    var externalDriver = new FakeAgentDriver("external");
    var registry = new DriverRegistry(new FakeAgentDriver("builtin"), externalDriver);

    // 从已存储的 Thread 设置路由也必须拒绝缺失的 Driver，而非返回表示空闲的替身。
    var missingSettings =
        new BranchSettings("agent", new ExecutionSelection("missing", "{}"), null);
    var error = assertThrows(DomainException.class, () -> registry.of(missingSettings));
    assertEquals(DomainErrorCode.VALIDATION, error.code());
    assertEquals("execution", error.resource());
    assertEquals("driver is not installed: missing", error.getMessage());

    var externalSettings =
        new BranchSettings("agent", new ExecutionSelection("external", "{}"), null);
    assertSame(externalDriver, registry.of(externalSettings));
  }
}
