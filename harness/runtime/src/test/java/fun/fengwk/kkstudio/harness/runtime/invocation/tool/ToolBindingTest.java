package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolType;

/** ToolBinding 的 type/descriptor/environment route 不变式。 */
class ToolBindingTest {

  private static final EnvironmentName ENV_ID = new EnvironmentName("env-1");

  @Test
  void acceptsPlatformAndEnvironmentBindings() {
    ToolBinding platform =
        new ToolBinding(descriptor("bash", ToolType.PLATFORM), ToolType.PLATFORM, null);
    assertEquals(ToolType.PLATFORM, platform.type());
    assertEquals("bash", platform.descriptor().name());
    assertNull(platform.environmentName());

    ToolBinding environment =
        new ToolBinding(descriptor("fs", ToolType.ENVIRONMENT), ToolType.ENVIRONMENT, ENV_ID);
    assertEquals(ENV_ID, environment.environmentName());
  }

  @Test
  void rejectsRouteMismatchAndDescriptorMismatch() {
    // ENVIRONMENT 携带最新 branch 的 route，可为 null（实际执行时确定性失败）。
    ToolBinding nullRoute =
        new ToolBinding(descriptor("fs", ToolType.ENVIRONMENT), ToolType.ENVIRONMENT, null);
    assertNull(nullRoute.environmentName());
    // PLATFORM 不得携带 route；descriptor.type 必须与 binding type 一致。
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(descriptor("bash", ToolType.PLATFORM), ToolType.PLATFORM, ENV_ID));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(descriptor("bash", ToolType.PLATFORM), ToolType.ENVIRONMENT, ENV_ID));
    assertThrows(NullPointerException.class, () -> new ToolBinding(null, ToolType.PLATFORM, null));
    assertThrows(
        NullPointerException.class,
        () -> new ToolBinding(descriptor("bash", ToolType.PLATFORM), null, null));
  }

  private static ToolDescriptor descriptor(String name, ToolType type) {
    return ToolInvocationTestData.descriptor(name, type);
  }
}
