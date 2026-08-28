package fun.fengwk.kkstudio.harness.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentCapabilityToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BuiltinHarnessContributor 的全面目录冻结与完整能力清单测试。
 *
 * <p>验证 exact inventory (17 tools: 12 environment + 2 host + 3 declarative),
 * backend/visibility/capability 映射, stable IDs, goal state ownership/projector, 和全局唯一性。
 */
class BuiltinHarnessContributorTest {

  @Test
  void contributorDescriptorMatchesSpecification() {
    BuiltinHarnessContributor contributor =
        new BuiltinHarnessContributor(stubTool("load_skill"), stubTool("task"));
    ContributorDescriptor descriptor = contributor.descriptor();

    assertEquals(new ContributorId("builtin"), descriptor.id());
    assertEquals("Built-in", descriptor.name());
    assertEquals("1", descriptor.version());
    assertTrue(descriptor.requires().isEmpty());
  }

  @Test
  void nullConstructorArgumentsAreRejected() {
    assertThrows(
        NullPointerException.class, () -> new BuiltinHarnessContributor(null, stubTool("task")));
    assertThrows(
        NullPointerException.class,
        () -> new BuiltinHarnessContributor(stubTool("load_skill"), null));
  }

  @Test
  void constructorRejectsIncorrectOrSwappedHostTools() {
    // Swapped tools
    assertThrows(
        IllegalArgumentException.class,
        () -> new BuiltinHarnessContributor(stubTool("task"), stubTool("load_skill")));

    // Wrong load_skill name
    assertThrows(
        IllegalArgumentException.class,
        () -> new BuiltinHarnessContributor(stubTool("other"), stubTool("task")));

    // Wrong task name
    assertThrows(
        IllegalArgumentException.class,
        () -> new BuiltinHarnessContributor(stubTool("load_skill"), stubTool("other")));
  }

  @Test
  void catalogFreezesExactInventoryOf17ToolsAndAssociatedCapabilities() {
    Tool loadSkill = stubTool("load_skill");
    Tool task = stubTool("task");
    BuiltinHarnessContributor contributor = new BuiltinHarnessContributor(loadSkill, task);

    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));

    // Descriptors
    assertEquals(1, catalog.descriptors().size());
    assertEquals(contributor.descriptor(), catalog.descriptors().get(0));
    assertTrue(catalog.findDescriptor(new ContributorId("builtin")).isPresent());

    // Tools inventory: exactly 17 tools
    List<ToolContribution> tools = catalog.tools();
    assertEquals(17, tools.size(), "exact total 17 tools expected");

    // Selectable tools: 12 environment + 3 goal = 15 tools (load_skill and task are INTERNAL)
    List<ToolContribution> selectables = catalog.selectableTools();
    assertEquals(15, selectables.size(), "exact 15 selectable tools expected");

    // 12 Environment Capability tools
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
        EnvironmentCapabilityIds.FS_APPLY_EDIT,
        ToolSideEffect.NON_IDEMPOTENT,
        Duration.ofMinutes(1));
    assertEnvironmentTool(
        catalog,
        "apply_patch",
        BuiltinToolIds.APPLY_PATCH,
        "environment.apply-patch",
        EnvironmentCapabilityIds.FS_APPLY_PATCH,
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
        EnvironmentCapabilityIds.FS_SEARCH,
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
    assertEnvironmentTool(
        catalog,
        "mcp_list_tools",
        BuiltinToolIds.MCP_LIST_TOOLS,
        "environment.mcp-list-tools",
        EnvironmentCapabilityIds.MCP_LIST,
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
    assertEnvironmentTool(
        catalog,
        "mcp_call_tool",
        BuiltinToolIds.MCP_CALL_TOOL,
        "environment.mcp-call-tool",
        EnvironmentCapabilityIds.MCP_CALL,
        ToolSideEffect.NON_IDEMPOTENT,
        Duration.ofMinutes(5));

    // 2 Host tools (INTERNAL visibility)
    assertHostTool(catalog, "load_skill", BuiltinToolIds.LOAD_SKILL, "runtime.load-skill");
    assertHostTool(catalog, "task", BuiltinToolIds.TASK, "runtime.task");

    // 3 Goal Declarative tools (SELECTABLE visibility)
    assertDeclarativeTool(
        catalog,
        "create_goal",
        BuiltinToolIds.GOAL_CREATE,
        "goal.create",
        ToolSideEffect.IDEMPOTENT);
    assertDeclarativeTool(
        catalog, "get_goal", BuiltinToolIds.GOAL_GET, "goal.get", ToolSideEffect.READ_ONLY);
    assertDeclarativeTool(
        catalog,
        "update_goal",
        BuiltinToolIds.GOAL_UPDATE,
        "goal.update",
        ToolSideEffect.IDEMPOTENT);

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
    for (ToolContribution tool : tools) {
      assertTrue(names.add(tool.definition().descriptor().name()));
      assertTrue(agentToolIds.add(tool.definition().id()));
      assertTrue(contributionIds.add(tool.id()));
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
    assertEquals(AgentToolBackend.ENVIRONMENT_CAPABILITY, tool.definition().backend());
    assertEquals(ToolVisibility.SELECTABLE, tool.definition().visibility());
    assertEquals(new ContributionId(new ContributorId("builtin"), localName), tool.id());

    ToolDescriptor descriptor = tool.definition().descriptor();
    assertEquals(toolName, descriptor.name());
    assertEquals("1", descriptor.version());
    assertEquals(toolName, descriptor.rendererKey());
    assertEquals(sideEffect, descriptor.sideEffect());
    assertEquals(timeout, descriptor.timeout());
    assertFalse(descriptor.description().isBlank());

    EnvironmentCapabilityDescriptor capability = EnvironmentCapabilityCatalog.require(capabilityId);
    assertEquals(capability.inputSchema(), descriptor.inputSchema());
    assertEquals(capability.timeout(), descriptor.timeout());

    EnvironmentCapabilityToolContribution envTool = (EnvironmentCapabilityToolContribution) tool;
    assertEquals(capability, envTool.capability());

    // Lookup by AgentToolId and ContributionId
    assertEquals(tool, catalog.findTool(agentToolId).orElseThrow());
    assertEquals(tool, catalog.findTool(tool.id()).orElseThrow());
  }

  private static void assertHostTool(
      HarnessCatalog catalog, String toolName, AgentToolId agentToolId, String localName) {
    ToolContribution tool = catalog.findTool(toolName).orElseThrow();
    assertEquals(agentToolId, tool.definition().id());
    assertEquals(AgentToolBackend.HOST, tool.definition().backend());
    assertEquals(ToolVisibility.INTERNAL, tool.definition().visibility());
    assertEquals(new ContributionId(new ContributorId("builtin"), localName), tool.id());

    assertEquals(tool, catalog.findTool(agentToolId).orElseThrow());
    assertEquals(tool, catalog.findTool(tool.id()).orElseThrow());
  }

  private static void assertDeclarativeTool(
      HarnessCatalog catalog,
      String toolName,
      AgentToolId agentToolId,
      String localName,
      ToolSideEffect sideEffect) {
    ToolContribution tool = catalog.findTool(toolName).orElseThrow();
    assertEquals(agentToolId, tool.definition().id());
    assertEquals(AgentToolBackend.DECLARATIVE, tool.definition().backend());
    assertEquals(ToolVisibility.SELECTABLE, tool.definition().visibility());
    assertEquals(new ContributionId(new ContributorId("builtin"), localName), tool.id());

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

  private static Tool stubTool(String name) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            name,
            "1",
            name + " description",
            name,
            new ToolParamsSchema(null, Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1));
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
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
