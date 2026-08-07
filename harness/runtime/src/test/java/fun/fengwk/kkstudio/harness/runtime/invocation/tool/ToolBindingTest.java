package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolType;

import java.util.UUID;

/** ToolBinding 的 type/descriptor/environment route 不变式。 */
class ToolBindingTest {

  private static final EnvironmentId ENV_ID = new EnvironmentId(UUID.randomUUID().toString());

  @Test
  void acceptsPlatformAndEnvironmentBindings() {
    ToolBinding platform =
        new ToolBinding(descriptor("bash", ToolType.PLATFORM), ToolType.PLATFORM, null);
    assertEquals(ToolType.PLATFORM, platform.type());
    assertEquals("bash", platform.descriptor().name());
    assertNull(platform.environmentId());

    ToolBinding environment =
        new ToolBinding(descriptor("fs", ToolType.ENVIRONMENT), ToolType.ENVIRONMENT, ENV_ID);
    assertEquals(ENV_ID, environment.environmentId());
  }

  @Test
  void rejectsRouteMismatchAndDescriptorMismatch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(descriptor("bash", ToolType.PLATFORM), ToolType.PLATFORM, ENV_ID));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(descriptor("fs", ToolType.ENVIRONMENT), ToolType.ENVIRONMENT, null));
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
