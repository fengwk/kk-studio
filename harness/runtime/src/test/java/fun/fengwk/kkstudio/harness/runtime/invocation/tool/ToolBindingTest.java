package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

/**
 * ToolBinding 的 definition/contributor/environmentRequired/environmentId/environmentName 契约与不变式。
 */
class ToolBindingTest {

  private static final EnvironmentId ENV_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  @Test
  void exposesOnlyDefinitionContributorEnvironmentRequiredAndEnvironmentComponents() {
    // 反射契约锁定 durable binding 的五个组件（environmentName 是历史投影判定 native 资格的冻结事实）。
    assertTrue(ToolBinding.class.isRecord());
    RecordComponent[] components = ToolBinding.class.getRecordComponents();
    assertEquals(
        List.of(
            "definition", "contributor", "environmentRequired", "environmentId", "environmentName"),
        Arrays.stream(components).map(RecordComponent::getName).toList());
    assertEquals(AgentToolDefinition.class, components[0].getType());
    assertEquals(ContributorBinding.class, components[1].getType());
    assertEquals(boolean.class, components[2].getType());
    assertEquals(EnvironmentId.class, components[3].getType());
    assertEquals(String.class, components[4].getType());
  }

  @Test
  void acceptsValidEnvironmentRequiredAndUnrequiredBindingShapes() {
    // 验证 environmentRequired=false/true（含未选择环境的 null 路由）三种合法形态均能正确构造。
    ToolBinding host =
        new ToolBinding(
            definition("bash"), contributorProvenance("core", "bash", List.of()), false, null);
    assertEquals("bash", host.descriptor().name());
    assertFalse(host.environmentRequired());
    assertNull(host.environmentId());
    assertEquals("core", host.contributor().contributorId());

    ToolBinding environment =
        new ToolBinding(
            definition("fs"),
            contributorProvenance("base", "read", List.of()),
            true,
            ENV_ID,
            "dev");
    assertEquals("fs", environment.descriptor().name());
    assertTrue(environment.environmentRequired());
    assertEquals(ENV_ID, environment.environmentId());
    assertEquals("dev", environment.environmentName());
    assertEquals("base", environment.contributor().contributorId());

    // branch 未选择 Environment：工具保持 environmentRequired，但路由身份为 null，调用时才失败。
    ToolBinding unselected =
        new ToolBinding(
            definition("fs"), contributorProvenance("base", "read", List.of()), true, null);
    assertTrue(unselected.environmentRequired());
    assertNull(unselected.environmentId());
    // 非环境工具与未提供环境名的路径共用同一 4 参便捷构造：冻结名为 null。
    assertNull(unselected.environmentName());
  }

  @Test
  void permitsCoexistenceOfStateAccessesAndEnvironment() {
    // 允许 stateAccesses 与 environment 在同一 binding 中并存。
    ContributorBinding statefulContributor = declarativeProvenance();
    ToolBinding combined =
        new ToolBinding(definition("state_tool"), statefulContributor, true, ENV_ID);
    assertTrue(combined.environmentRequired());
    assertEquals(ENV_ID, combined.environmentId());
    assertEquals(statefulContributor, combined.contributor());
    assertFalse(combined.contributor().stateAccesses().isEmpty());

    ToolBinding stateWithoutEnv =
        new ToolBinding(definition("state_tool"), statefulContributor, false, null);
    assertFalse(stateWithoutEnv.environmentRequired());
    assertNull(stateWithoutEnv.environmentId());
    assertEquals(statefulContributor, stateWithoutEnv.contributor());
  }

  @Test
  void enforcesEnvironmentRequiredInvariant() {
    ContributorBinding contributor = contributorProvenance("core", "bash", List.of());

    // environmentRequired 为 false 时 environment 必须为 null
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(definition("host-env"), contributor, false, ENV_ID));
    // environmentRequired 为 false 时 environmentName 同样必须为 null
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(definition("host-env"), contributor, false, null, "dev"));
    // environmentName 只能是 null 或非空白
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(definition("fs"), contributor, true, ENV_ID, " "));

    // definition 和 contributor 必须非空
    assertThrows(NullPointerException.class, () -> new ToolBinding(null, contributor, false, null));
    assertThrows(
        NullPointerException.class, () -> new ToolBinding(definition("bash"), null, false, null));
  }

  @Test
  void contributorBindingFreezesCanonicalOwnerAndUniqueStateAccesses() {
    // ContributorBinding 校验规范标识符与唯一的 stateAccess customType。
    ContributorBinding contributor =
        new ContributorBinding(
            "goal",
            "create",
            List.of(new ContributorStateAccess("state", ContributorStateAccessMode.WRITE)));
    ToolBinding binding = new ToolBinding(definition("create_goal"), contributor, false, null);
    assertEquals(contributor, binding.contributor());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ContributorBinding(
                "goal",
                "create",
                List.of(
                    new ContributorStateAccess("state", ContributorStateAccessMode.READ),
                    new ContributorStateAccess("state", ContributorStateAccessMode.WRITE))));
    assertThrows(
        IllegalArgumentException.class, () -> new ContributorBinding("Goal", "create", List.of()));
  }

  private static ToolDescriptor descriptor(String name) {
    return ToolInvocationTestData.descriptor(name);
  }

  private static AgentToolDefinition definition(String name) {
    return new AgentToolDefinition(descriptor(name), ToolVisibility.SELECTABLE);
  }

  private static ContributorBinding contributorProvenance(
      String contributorId, String localName, List<ContributorStateAccess> accesses) {
    return new ContributorBinding(contributorId, localName, accesses);
  }

  private static ContributorBinding declarativeProvenance() {
    return new ContributorBinding(
        "goal",
        "create",
        List.of(new ContributorStateAccess("state", ContributorStateAccessMode.WRITE)));
  }
}
