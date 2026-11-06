package fun.fengwk.kkstudio.harness.builtin.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;

class DriverValidationTest {

  /** 客户端选择不存在的 Driver 或无效 settings，必须在接受事务前返回校验错误。 */
  @Test
  void requestExecutionIsValidatedWithoutChangingStoredSettingsParser() {
    DriverRegistry drivers =
        new DriverRegistry(
            new BuiltinAgentDriver(mock(BuiltinStore.class), mock(TurnResolver.class)));
    assertEquals(
        DomainErrorCode.VALIDATION,
        assertThrows(
                DomainException.class,
                () -> drivers.validate(new ExecutionSelection("absent", "{}")))
            .code());
    assertEquals(
        DomainErrorCode.VALIDATION,
        assertThrows(
                DomainException.class,
                () -> drivers.validate(new ExecutionSelection("builtin", "{}")))
            .code());
    assertThrows(IllegalArgumentException.class, () -> BuiltinExecution.parseSettings("{}"));
    drivers.validate(
        new ExecutionSelection("builtin", "{\"modelName\":\"model\",\"variant\":null}"));
  }
}
