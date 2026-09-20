package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

/** ToolBinding 的 definition/contributor/environmentSupport/environmentId/environmentName 契约与不变式。 */
class ToolBindingTest {

  private static final EnvironmentId ENV_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  @Test
  void exposesOnlyDefinitionContributorEnvironmentSupportAndEnvironmentComponents() {
    // 反射契约锁定 durable binding 的五个组件（environmentName 是历史投影判定 native 资格的冻结事实）。
    assertTrue(ToolBinding.class.isRecord());
    RecordComponent[] components = ToolBinding.class.getRecordComponents();
    assertEquals(
        List.of(
            "definition", "contributor", "environmentSupport", "environmentId", "environmentName"),
        Arrays.stream(components).map(RecordComponent::getName).toList());
    assertEquals(AgentToolDefinition.class, components[0].getType());
    assertEquals(ContributorBinding.class, components[1].getType());
    assertEquals(EnvironmentSupport.class, components[2].getType());
    assertEquals(EnvironmentId.class, components[3].getType());
    assertEquals(String.class, components[4].getType());
  }

  /** 测试意图：验证三种 EnvironmentSupport 级别与其允许的环境路由形态均能正确构造。 */
  @Test
  void acceptsValidEnvironmentSupportAndRouteBindingShapes() {
    // NONE 必须不带环境
    ToolBinding host =
        new ToolBinding(
            definition("bash"),
            contributorProvenance("core", "bash", List.of()),
            EnvironmentSupport.NONE,
            null,
            null);
    assertEquals("bash", host.descriptor().name());
    assertEquals(EnvironmentSupport.NONE, host.environmentSupport());
    assertNull(host.environmentId());
    assertNull(host.environmentName());
    assertEquals("core", host.contributor().contributorId());

    // REQUIRED 必须携带成对的 environmentId 与 environmentName
    ToolBinding environment =
        new ToolBinding(
            definition("fs"),
            contributorProvenance("base", "read", List.of()),
            EnvironmentSupport.REQUIRED,
            ENV_ID,
            "dev");
    assertEquals("fs", environment.descriptor().name());
    assertEquals(EnvironmentSupport.REQUIRED, environment.environmentSupport());
    assertEquals(ENV_ID, environment.environmentId());
    assertEquals("dev", environment.environmentName());
    assertEquals("base", environment.contributor().contributorId());

    // OPTIONAL 可以携带成对的环境
    ToolBinding optionalWithEnv =
        new ToolBinding(
            definition("custom"),
            contributorProvenance("base", "custom", List.of()),
            EnvironmentSupport.OPTIONAL,
            ENV_ID,
            "dev");
    assertEquals(EnvironmentSupport.OPTIONAL, optionalWithEnv.environmentSupport());
    assertEquals(ENV_ID, optionalWithEnv.environmentId());
    assertEquals("dev", optionalWithEnv.environmentName());

    // OPTIONAL 也可以不携带环境
    ToolBinding optionalWithoutEnv =
        new ToolBinding(
            definition("custom"),
            contributorProvenance("base", "custom", List.of()),
            EnvironmentSupport.OPTIONAL,
            null,
            null);
    assertEquals(EnvironmentSupport.OPTIONAL, optionalWithoutEnv.environmentSupport());
    assertNull(optionalWithoutEnv.environmentId());
    assertNull(optionalWithoutEnv.environmentName());
  }

  @Test
  void permitsCoexistenceOfStateAccessesAndEnvironment() {
    // 允许 stateAccesses 与 environment 在同一 binding 中并存。
    ContributorBinding statefulContributor = declarativeProvenance();
    ToolBinding combined =
        new ToolBinding(
            definition("state_tool"),
            statefulContributor,
            EnvironmentSupport.REQUIRED,
            ENV_ID,
            "dev");
    assertEquals(EnvironmentSupport.REQUIRED, combined.environmentSupport());
    assertEquals(ENV_ID, combined.environmentId());
    assertEquals(statefulContributor, combined.contributor());
    assertFalse(combined.contributor().stateAccesses().isEmpty());

    ToolBinding stateWithoutEnv =
        new ToolBinding(
            definition("state_tool"), statefulContributor, EnvironmentSupport.NONE, null, null);
    assertEquals(EnvironmentSupport.NONE, stateWithoutEnv.environmentSupport());
    assertNull(stateWithoutEnv.environmentId());
    assertEquals(statefulContributor, stateWithoutEnv.contributor());
  }

  /**
   * 测试意图：严格校验 EnvironmentSupport 与环境属性组合不变式： NONE 带环境被拒；REQUIRED 缺 id 或缺 name 被拒；OPTIONAL id/name
   * 不同步被拒；blank 名称被拒；必要字段非空。
   */
  @Test
  void enforcesEnvironmentSupportInvariants() {
    ContributorBinding contributor = contributorProvenance("core", "bash", List.of());

    // NONE 携带 environmentId 或 environmentName 必须被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("host"), contributor, EnvironmentSupport.NONE, ENV_ID, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(definition("host"), contributor, EnvironmentSupport.NONE, null, "dev"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("host"), contributor, EnvironmentSupport.NONE, ENV_ID, "dev"));

    // REQUIRED 缺少 environmentId 或缺少 environmentName 必须被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("fs"), contributor, EnvironmentSupport.REQUIRED, null, "dev"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("fs"), contributor, EnvironmentSupport.REQUIRED, ENV_ID, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("fs"), contributor, EnvironmentSupport.REQUIRED, null, null));

    // OPTIONAL 必须成对出现或成对缺失：只带 id 或只带 name 必须被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("opt"), contributor, EnvironmentSupport.OPTIONAL, ENV_ID, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("opt"), contributor, EnvironmentSupport.OPTIONAL, null, "dev"));

    // environmentName 只能是 null 或非空白字符串
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("fs"), contributor, EnvironmentSupport.REQUIRED, ENV_ID, " "));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("opt"), contributor, EnvironmentSupport.OPTIONAL, ENV_ID, "   "));

    // definition、contributor 与 environmentSupport 必须非空
    assertThrows(
        NullPointerException.class,
        () -> new ToolBinding(null, contributor, EnvironmentSupport.NONE, null, null));
    assertThrows(
        NullPointerException.class,
        () -> new ToolBinding(definition("bash"), null, EnvironmentSupport.NONE, null, null));
    assertThrows(
        NullPointerException.class,
        () -> new ToolBinding(definition("bash"), contributor, null, null, null));
  }

  @Test
  void contributorBindingFreezesCanonicalOwnerAndUniqueStateAccesses() {
    // ContributorBinding 校验规范标识符与唯一的 stateAccess customType。
    ContributorBinding contributor =
        new ContributorBinding(
            "goal",
            "create",
            List.of(new ContributorStateAccess("state", ContributorStateAccessMode.WRITE)));
    ToolBinding binding =
        new ToolBinding(
            definition("create_goal"), contributor, EnvironmentSupport.NONE, null, null);
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
