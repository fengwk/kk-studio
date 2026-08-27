package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolType;

import java.util.List;

/** ToolBinding 的 durable route/environment 不变式；descriptor 只表示模型契约。 */
class ToolBindingTest {

  private static final EnvironmentBinding ENV_ID = EnvironmentBindings.binding("env-1");

  @Test
  void acceptsPlatformAndEnvironmentBindings() {
    ToolBinding platform = new ToolBinding(descriptor("bash"), ToolType.PLATFORM, null);
    assertEquals(ToolType.PLATFORM, platform.type());
    assertEquals("bash", platform.descriptor().name());
    assertNull(platform.environment());

    ToolBinding environment = new ToolBinding(descriptor("fs"), ToolType.ENVIRONMENT, ENV_ID);
    assertEquals(ENV_ID, environment.environment());
  }

  @Test
  void keepsRouteIndependentFromDescriptorAndRejectsInvalidPayloads() {
    // ENVIRONMENT 携带最新 branch 的 route，可为 null（实际执行时确定性失败）。
    ToolBinding nullRoute = new ToolBinding(descriptor("fs"), ToolType.ENVIRONMENT, null);
    assertNull(nullRoute.environment());
    // type 是 binding 的 durable route，不属于 descriptor；同一 descriptor 可以按 route 绑定。
    ToolDescriptor shared = descriptor("shared");
    assertEquals(ToolType.PLATFORM, new ToolBinding(shared, ToolType.PLATFORM, null).type());
    assertEquals(
        ToolType.ENVIRONMENT, new ToolBinding(shared, ToolType.ENVIRONMENT, ENV_ID).type());
    // PLATFORM 不得携带 Environment route。
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(descriptor("bash"), ToolType.PLATFORM, ENV_ID));
    assertThrows(NullPointerException.class, () -> new ToolBinding(null, ToolType.PLATFORM, null));
    assertThrows(NullPointerException.class, () -> new ToolBinding(descriptor("bash"), null, null));
  }

  @Test
  void pluginBindingFreezesCanonicalOwnerAndUniqueStateAccesses() {
    PluginToolBinding plugin =
        new PluginToolBinding(
            "goal", "create", List.of(new PluginStateAccess("state", PluginStateAccessMode.WRITE)));
    ToolBinding binding =
        new ToolBinding(descriptor("create_goal"), ToolType.PLATFORM, null, plugin);
    assertEquals(plugin, binding.plugin());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(descriptor("fs"), ToolType.ENVIRONMENT, ENV_ID, plugin));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginToolBinding(
                "goal",
                "create",
                List.of(
                    new PluginStateAccess("state", PluginStateAccessMode.READ),
                    new PluginStateAccess("state", PluginStateAccessMode.WRITE))));
    assertThrows(
        IllegalArgumentException.class, () -> new PluginToolBinding("Goal", "create", List.of()));
  }

  private static ToolDescriptor descriptor(String name) {
    return ToolInvocationTestData.descriptor(name);
  }
}
