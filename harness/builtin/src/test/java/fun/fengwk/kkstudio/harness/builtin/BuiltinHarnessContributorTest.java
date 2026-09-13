package fun.fengwk.kkstudio.harness.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.environment.EnvironmentCapabilityTool;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BuiltinHarnessContributor 的全面目录冻结与完整能力清单测试。
 *
 * <p>验证 exact inventory (14 tools: 9 environment + 2 internal + 3 goal),
 * visibility/requirements/capability 映射, stable IDs, goal state ownership/projector, 和全局唯一性。
 */
class BuiltinHarnessContributorTest {

  @Test
  void contributorDescriptorMatchesSpecification() {
    BuiltinHarnessContributor contributor =
        new BuiltinHarnessContributor(
            stubTool("load_skill", ToolRequirements.environment()),
            stubTool("task", ToolRequirements.none()));
    ContributorDescriptor descriptor = contributor.descriptor();

    assertEquals(new ContributorId("builtin"), descriptor.id());
    assertEquals("Built-in", descriptor.name());
    assertEquals("1", descriptor.version());
    assertTrue(descriptor.requires().isEmpty());
  }

  @Test
  void nullConstructorArgumentsAreRejected() {
    assertThrows(
        NullPointerException.class,
        () -> new BuiltinHarnessContributor(null, stubTool("task", ToolRequirements.none())));
    assertThrows(
        NullPointerException.class,
        () ->
            new BuiltinHarnessContributor(
                stubTool("load_skill", ToolRequirements.environment()), null));
  }

  @Test
  void constructorRejectsIncorrectOrSwappedTools() {
    // Swapped tools
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuiltinHarnessContributor(
                stubTool("task", ToolRequirements.none()),
                stubTool("load_skill", ToolRequirements.environment())));

    // Wrong load_skill name
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuiltinHarnessContributor(
                stubTool("other", ToolRequirements.environment()),
                stubTool("task", ToolRequirements.none())));

    // Wrong task name
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuiltinHarnessContributor(
                stubTool("load_skill", ToolRequirements.environment()),
                stubTool("other", ToolRequirements.none())));
  }

  @Test
  void catalogFreezesExactInventoryOf14ToolsAndAssociatedCapabilities() {
    Tool loadSkill = stubTool("load_skill", ToolRequirements.environment());
    Tool task = stubTool("task", ToolRequirements.none());
    BuiltinHarnessContributor contributor = new BuiltinHarnessContributor(loadSkill, task);

    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));

    // Descriptors
    assertEquals(1, catalog.descriptors().size());
    assertEquals(contributor.descriptor(), catalog.descriptors().get(0));
    assertTrue(catalog.findDescriptor(new ContributorId("builtin")).isPresent());

    // Tools inventory: exactly 14 tools
    List<ToolContribution> tools = catalog.tools();
    assertEquals(14, tools.size(), "exact total 14 tools expected");

    // Selectable tools: 9 environment + 3 goal = 12 tools (load_skill and task are INTERNAL)
    List<ToolContribution> selectables = catalog.selectableTools();
    assertEquals(12, selectables.size(), "exact 12 selectable tools expected");

    // 9 Environment Capability tools
    assertEnvironmentTool(
        catalog,
        "read",
        BuiltinToolIds.READ,
        "environment.read",
        EnvironmentCapabilityIds.FS_READ,
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(1));
    assertEnvironmentTool(
        catalog,
        "write",
        BuiltinToolIds.WRITE,
        "environment.write",
        EnvironmentCapabilityIds.FS_WRITE,
        ToolSideEffect.IDEMPOTENT,
        Duration.ofMinutes(1));
    assertEnvironmentTool(
        catalog,
        "edit",
        BuiltinToolIds.EDIT,
        "environment.edit",
        EnvironmentCapabilityIds.FS_EDIT,
        ToolSideEffect.NON_IDEMPOTENT,
        Duration.ofMinutes(1));
    assertEnvironmentTool(
        catalog,
        "bash",
        BuiltinToolIds.BASH,
        "environment.bash",
        EnvironmentCapabilityIds.PROCESS_EXEC,
        ToolSideEffect.NON_IDEMPOTENT,
        Duration.ofHours(1));
    assertEnvironmentTool(
        catalog,
        "grep",
        BuiltinToolIds.GREP,
        "environment.grep",
        EnvironmentCapabilityIds.FS_GREP,
        ToolSideEffect.READ_ONLY,
        Duration.ofHours(1));
    assertEnvironmentTool(
        catalog,
        "find",
        BuiltinToolIds.FIND,
        "environment.find",
        EnvironmentCapabilityIds.FS_FIND,
        ToolSideEffect.READ_ONLY,
        Duration.ofHours(1));
    assertEnvironmentTool(
        catalog,
        "lsp_goto_definition",
        BuiltinToolIds.LSP_GOTO_DEFINITION,
        "environment.lsp-goto-definition",
        EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(2));
    assertEnvironmentTool(
        catalog,
        "lsp_workspace_symbols",
        BuiltinToolIds.LSP_WORKSPACE_SYMBOLS,
        "environment.lsp-workspace-symbols",
        EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(2));
    assertEnvironmentTool(
        catalog,
        "lsp_java_decompile",
        BuiltinToolIds.LSP_JAVA_DECOMPILE,
        "environment.lsp-java-decompile",
        EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE,
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(2));

    // 2 Internal tools (INTERNAL visibility)
    assertInternalTool(
        catalog,
        "load_skill",
        BuiltinToolIds.LOAD_SKILL,
        "runtime.load-skill",
        ToolRequirements.environment());
    assertInternalTool(
        catalog, "task", BuiltinToolIds.TASK, "runtime.task", ToolRequirements.none());

    // 3 Goal tools (SELECTABLE visibility)
    assertGoalTool(
        catalog,
        "create_goal",
        BuiltinToolIds.GOAL_CREATE,
        "goal.create",
        ToolSideEffect.IDEMPOTENT,
        StateMode.WRITE);
    assertGoalTool(
        catalog,
        "get_goal",
        BuiltinToolIds.GOAL_GET,
        "goal.get",
        ToolSideEffect.READ_ONLY,
        StateMode.READ);
    assertGoalTool(
        catalog,
        "update_goal",
        BuiltinToolIds.GOAL_UPDATE,
        "goal.update",
        ToolSideEffect.IDEMPOTENT,
        StateMode.WRITE);

    // Custom entry types: exactly goal.state ownership
    assertEquals(1, catalog.customEntryTypes().size());
    assertTrue(catalog.findCustomEntryType(new ContributorId("builtin"), "goal.state").isPresent());
    assertEquals(
        "goal.state-type",
        catalog
            .findCustomEntryType(new ContributorId("builtin"), "goal.state")
            .orElseThrow()
            .id()
            .localName());

    // Context projectors: exactly goal.context
    assertEquals(1, catalog.contextProjectors().size());
    ContributionId projectorId = new ContributionId(new ContributorId("builtin"), "goal.context");
    assertTrue(catalog.findContextProjector(projectorId).isPresent());

    // Ensure all tool names, AgentToolIds and ContributionIds are unique
    Set<String> names = new HashSet<>();
    Set<AgentToolId> agentToolIds = new HashSet<>();
    Set<ContributionId> contributionIds = new HashSet<>();
    for (ToolContribution toolContribution : tools) {
      assertTrue(names.add(toolContribution.definition().descriptor().name()));
      assertTrue(agentToolIds.add(toolContribution.definition().id()));
      assertTrue(contributionIds.add(toolContribution.id()));
    }
  }

  private static void assertEnvironmentTool(
      HarnessCatalog catalog,
      String toolName,
      AgentToolId agentToolId,
      String localName,
      EnvironmentCapabilityId capabilityId,
      ToolSideEffect sideEffect,
      Duration timeout) {
    ToolContribution tool = catalog.findTool(toolName).orElseThrow();
    assertEquals(agentToolId, tool.definition().id());
    assertEquals(ToolVisibility.SELECTABLE, tool.definition().visibility());
    assertEquals(new ContributionId(new ContributorId("builtin"), localName), tool.id());
    assertEquals(ToolRequirements.environment(), tool.requirements());

    assertInstanceOf(EnvironmentCapabilityTool.class, tool.tool());
    EnvironmentCapabilityTool envCapabilityTool = (EnvironmentCapabilityTool) tool.tool();

    ToolDescriptor descriptor = tool.definition().descriptor();
    assertEquals(toolName, descriptor.name());
    // environment tool 的 model contract 版本跟随 capability descriptor 版本（workdir 能力为 "2"）。
    EnvironmentCapabilityDescriptor capability = EnvironmentCapabilityCatalog.require(capabilityId);
    assertEquals(capability.version(), descriptor.version());
    assertEquals(toolName, descriptor.rendererKey());
    assertEquals(sideEffect, descriptor.sideEffect());
    assertEquals(timeout, descriptor.timeout());
    assertFalse(descriptor.description().isBlank());
    if (EnvironmentCapabilityCatalog.requiresWorkdir(capabilityId)) {
      // 模型提示词必须与 schema 的必填绝对 workdir 契约一致，避免生成必然被服务端拒绝的相对或缺省目录调用。
      assertTrue(descriptor.description().contains("Every call must include `workdir`"));
      assertTrue(descriptor.description().contains("expanded absolute directory"));
      assertFalse(descriptor.description().contains("workdir` defaults"));
    }

    assertEquals(capability.inputSchema(), descriptor.inputSchema());
    assertEquals(capability.timeout(), descriptor.timeout());
    assertEquals(capability, envCapabilityTool.capability());

    // Lookup by AgentToolId and ContributionId
    assertEquals(tool, catalog.findTool(agentToolId).orElseThrow());
    assertEquals(tool, catalog.findTool(tool.id()).orElseThrow());
  }

  private static void assertInternalTool(
      HarnessCatalog catalog,
      String toolName,
      AgentToolId agentToolId,
      String localName,
      ToolRequirements expectedRequirements) {
    ToolContribution tool = catalog.findTool(toolName).orElseThrow();
    assertEquals(agentToolId, tool.definition().id());
    assertEquals(ToolVisibility.INTERNAL, tool.definition().visibility());
    assertEquals(new ContributionId(new ContributorId("builtin"), localName), tool.id());
    assertEquals(expectedRequirements, tool.requirements());

    assertEquals(tool, catalog.findTool(agentToolId).orElseThrow());
    assertEquals(tool, catalog.findTool(tool.id()).orElseThrow());
  }

  private static void assertGoalTool(
      HarnessCatalog catalog,
      String toolName,
      AgentToolId agentToolId,
      String localName,
      ToolSideEffect sideEffect,
      StateMode mode) {
    ToolContribution tool = catalog.findTool(toolName).orElseThrow();
    assertEquals(agentToolId, tool.definition().id());
    assertEquals(ToolVisibility.SELECTABLE, tool.definition().visibility());
    assertEquals(new ContributionId(new ContributorId("builtin"), localName), tool.id());
    assertEquals(
        new ToolRequirements(
            false, List.of(new StateDeclaration(BuiltinHarnessContributor.GOAL_STATE_TYPE, mode))),
        tool.requirements());

    ToolDescriptor descriptor = tool.definition().descriptor();
    assertEquals(toolName, descriptor.name());
    assertEquals("2", descriptor.version());
    assertEquals(toolName, descriptor.rendererKey());
    assertEquals(sideEffect, descriptor.sideEffect());
    assertEquals(Duration.ZERO, descriptor.timeout());
    assertFalse(descriptor.description().isBlank());

    assertEquals(tool, catalog.findTool(agentToolId).orElseThrow());
    assertEquals(tool, catalog.findTool(tool.id()).orElseThrow());
  }

  private static Tool stubTool(String name, ToolRequirements requirements) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            name,
            "1",
            name + " description",
            name,
            new InputSchema(null, Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1));
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public ToolRequirements requirements() {
        return requirements;
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        return new ToolExecutionHandle() {
          @Override
          public void cancel() {}

          @Override
          public boolean isCancelled() {
            return false;
          }
        };
      }
    };
  }
}
