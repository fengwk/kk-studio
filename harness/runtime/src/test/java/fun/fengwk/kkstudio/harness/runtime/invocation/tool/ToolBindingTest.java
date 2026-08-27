package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

/** ToolBinding 的 definition/backend/provenance 不变式；descriptor 只表示模型契约。 */
class ToolBindingTest {

  private static final EnvironmentBinding ENV_ID = EnvironmentBindings.binding("env-1");

  @Test
  void exposesOnlyDefinitionEnvironmentAndPluginRecordComponents() {
    // 反射契约锁定 durable binding 的三个组件，防止旧 type 字段或隐式路由状态回归。
    assertTrue(ToolBinding.class.isRecord());
    RecordComponent[] components = ToolBinding.class.getRecordComponents();
    assertEquals(
        List.of("definition", "environment", "plugin"),
        Arrays.stream(components).map(RecordComponent::getName).toList());
    assertEquals(AgentToolDefinition.class, components[0].getType());
    assertEquals(EnvironmentBinding.class, components[1].getType());
    assertEquals(PluginToolBinding.class, components[2].getType());
  }

  /** 三种 backend 都必须保留完整 definition，且 descriptor convenience accessor 不改变冻结对象。 */
  @Test
  void acceptsEveryBackendBindingShape() {
    ToolBinding host = new ToolBinding(definition("bash", AgentToolBackend.HOST), null, null);
    assertEquals(AgentToolBackend.HOST, host.definition().backend());
    assertEquals("bash", host.descriptor().name());
    assertNull(host.environment());
    assertNull(host.plugin());

    ToolBinding plugin =
        new ToolBinding(
            definition("create_goal", AgentToolBackend.PLUGIN), null, pluginProvenance());
    assertEquals(AgentToolBackend.PLUGIN, plugin.definition().backend());
    assertEquals("create_goal", plugin.descriptor().name());
    assertEquals(pluginProvenance(), plugin.plugin());

    ToolBinding environment =
        new ToolBinding(definition("fs", AgentToolBackend.ENVIRONMENT_CAPABILITY), ENV_ID, null);
    assertEquals(AgentToolBackend.ENVIRONMENT_CAPABILITY, environment.definition().backend());
    assertEquals(ENV_ID, environment.environment());

    // ENVIRONMENT_CAPABILITY 可以冻结 null：实际路由时再按 deterministic unavailable 收敛。
    assertNull(
        new ToolBinding(definition("fs-null", AgentToolBackend.ENVIRONMENT_CAPABILITY), null, null)
            .environment());
  }

  /** backend 是唯一 route，三个 backend 的 payload 组合必须在构造时 fail closed。 */
  @Test
  void enforcesBackendPayloadInvariants() {
    PluginToolBinding plugin = pluginProvenance();
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(definition("host-env", AgentToolBackend.HOST), ENV_ID, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(definition("host-plugin", AgentToolBackend.HOST), null, plugin));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(definition("plugin-env", AgentToolBackend.PLUGIN), ENV_ID, plugin));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(definition("plugin-missing", AgentToolBackend.PLUGIN), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("environment-plugin", AgentToolBackend.ENVIRONMENT_CAPABILITY),
                ENV_ID,
                plugin));
    assertThrows(NullPointerException.class, () -> new ToolBinding(null, null, null));
  }

  @Test
  void pluginBindingFreezesCanonicalOwnerAndUniqueStateAccesses() {
    PluginToolBinding plugin =
        new PluginToolBinding(
            "goal", "create", List.of(new PluginStateAccess("state", PluginStateAccessMode.WRITE)));
    ToolBinding binding =
        new ToolBinding(definition("create_goal", AgentToolBackend.PLUGIN), null, plugin);
    assertEquals(plugin, binding.plugin());
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

  private static AgentToolDefinition definition(String name, AgentToolBackend backend) {
    return new AgentToolDefinition(
        new AgentToolId("test." + name.replace('_', '-').toLowerCase()),
        descriptor(name),
        ToolVisibility.SELECTABLE,
        backend);
  }

  private static PluginToolBinding pluginProvenance() {
    return new PluginToolBinding(
        "goal", "create", List.of(new PluginStateAccess("state", PluginStateAccessMode.WRITE)));
  }
}
