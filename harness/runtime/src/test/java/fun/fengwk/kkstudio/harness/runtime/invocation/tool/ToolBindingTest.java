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

/** ToolBinding 的 definition/contributor/environment 不变式；descriptor 只表示模型契约。 */
class ToolBindingTest {

  private static final EnvironmentBinding ENV_ID = EnvironmentBindings.binding("env-1");

  @Test
  void exposesOnlyDefinitionContributorAndEnvironmentRecordComponents() {
    // 反射契约锁定 durable binding 的三个组件，防止旧 type/plugin 字段回归。
    assertTrue(ToolBinding.class.isRecord());
    RecordComponent[] components = ToolBinding.class.getRecordComponents();
    assertEquals(
        List.of("definition", "contributor", "environment"),
        Arrays.stream(components).map(RecordComponent::getName).toList());
    assertEquals(AgentToolDefinition.class, components[0].getType());
    assertEquals(ContributorBinding.class, components[1].getType());
    assertEquals(EnvironmentBinding.class, components[2].getType());
  }

  /** 三种 backend 都必须保留完整 definition，且 descriptor convenience accessor 不改变冻结对象。 */
  @Test
  void acceptsEveryBackendBindingShape() {
    ToolBinding host =
        new ToolBinding(
            definition("bash", AgentToolBackend.HOST),
            contributorProvenance("core", "bash", List.of()),
            null);
    assertEquals(AgentToolBackend.HOST, host.definition().backend());
    assertEquals("bash", host.descriptor().name());
    assertNull(host.environment());
    assertEquals("core", host.contributor().contributorId());

    ToolBinding declarative =
        new ToolBinding(
            definition("create_goal", AgentToolBackend.DECLARATIVE), declarativeProvenance(), null);
    assertEquals(AgentToolBackend.DECLARATIVE, declarative.definition().backend());
    assertEquals("create_goal", declarative.descriptor().name());
    assertEquals(declarativeProvenance(), declarative.contributor());
    assertNull(declarative.environment());

    ToolBinding environment =
        new ToolBinding(
            definition("fs", AgentToolBackend.ENVIRONMENT_CAPABILITY),
            contributorProvenance("base", "read", List.of()),
            ENV_ID);
    assertEquals(AgentToolBackend.ENVIRONMENT_CAPABILITY, environment.definition().backend());
    assertEquals(ENV_ID, environment.environment());
    assertEquals("base", environment.contributor().contributorId());
  }

  /** backend 是唯一 route，三个 backend 的 payload 组合必须在构造时 fail closed。 */
  @Test
  void enforcesBackendPayloadInvariants() {
    ContributorBinding emptyContributor = contributorProvenance("core", "bash", List.of());
    ContributorBinding statefulContributor = declarativeProvenance();

    // HOST 不允许 environment 或 stateAccesses
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("host-env", AgentToolBackend.HOST), emptyContributor, ENV_ID));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("host-state", AgentToolBackend.HOST), statefulContributor, null));

    // DECLARATIVE 不允许 environment
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("decl-env", AgentToolBackend.DECLARATIVE), statefulContributor, ENV_ID));

    // ENVIRONMENT_CAPABILITY 必须有 environment，且不允许 stateAccesses
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("env-null", AgentToolBackend.ENVIRONMENT_CAPABILITY),
                emptyContributor,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                definition("env-state", AgentToolBackend.ENVIRONMENT_CAPABILITY),
                statefulContributor,
                ENV_ID));

    // definition 和 contributor 必须非空
    assertThrows(NullPointerException.class, () -> new ToolBinding(null, emptyContributor, null));
    assertThrows(
        NullPointerException.class,
        () -> new ToolBinding(definition("host", AgentToolBackend.HOST), null, null));
  }

  @Test
  void contributorBindingFreezesCanonicalOwnerAndUniqueStateAccesses() {
    ContributorBinding contributor =
        new ContributorBinding(
            "goal",
            "create",
            List.of(new ContributorStateAccess("state", ContributorStateAccessMode.WRITE)));
    ToolBinding binding =
        new ToolBinding(definition("create_goal", AgentToolBackend.DECLARATIVE), contributor, null);
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

  private static AgentToolDefinition definition(String name, AgentToolBackend backend) {
    return new AgentToolDefinition(
        new AgentToolId("test." + name.replace('_', '-').toLowerCase()),
        descriptor(name),
        ToolVisibility.SELECTABLE,
        backend);
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
