package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionType;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.harness.tool.CompositeRuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.harness.tool.HarnessToolCatalogAdapter;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentSkillRefDTO;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/** Agent 配置的校验：工具名必须来自统一 RuntimeToolCatalog 且对应可选择条目，且 skill 名必须遵守有界长度规则。 */
class AgentDefinitionConfigValidatorTest {

  private static final String CUSTOM_TOOL_NAME = "custom_tool";
  private static final String ENVIRONMENT_TOOL_NAME = "read";
  private static final String INTERNAL_TOOL_NAME = "load_skill";

  @Test
  void acceptsEnvironmentToolsAndAgentConfigWithNoEnvironment() {
    // Agent 与 Environment 解耦：没有 Agent environment 也能保存 environment-required 工具，
    // 也可保存绑定固定 Environment 的动态工具；这些约束在执行期按 branch 选择判定。
    Tool fixedEnvironmentTool =
        tool(
            hostDescriptor(CUSTOM_TOOL_NAME),
            ToolRequirements.environment(EnvironmentId.of(UUID.randomUUID())));
    try (Fixture fixture = new Fixture(List.of(hostTool("host_tool"), fixedEnvironmentTool))) {
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setTools(List.of(ENVIRONMENT_TOOL_NAME, "host_tool", CUSTOM_TOOL_NAME));
      config.setSkills(
          List.of(
              new AgentSkillRefDTO("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "dev"),
              new AgentSkillRefDTO("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "ops")));
      config.setSubagents(List.of("reviewer"));

      assertDoesNotThrow(() -> fixture.validator.validate(config));
    }
  }

  @Test
  void acceptsGenericEnvironmentToolRequirements() {
    // environmentRequired 但无固定 Environment 的通用环境能力同样随时可配置。
    try (Fixture fixture = new Fixture(List.of())) {
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setTools(List.of(ENVIRONMENT_TOOL_NAME));
      config.setSkills(List.of());
      config.setSubagents(List.of());

      assertDoesNotThrow(() -> fixture.validator.validate(config));
    }
  }

  @Test
  void rejectsUnknownToolName() {
    try (Fixture fixture = new Fixture(List.of())) {
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setTools(List.of("missing_tool"));
      config.setSkills(List.of());
      config.setSubagents(List.of());
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.validator.validate(config))
              .getMessage()
              .contains("unknown agent tool: missing_tool"));
    }
  }

  @Test
  void rejectsSkillNameExceeding128Characters() {
    try (Fixture fixture = new Fixture(List.of())) {
      String tooLong = "x".repeat(129);
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setTools(List.of());
      config.setSkills(
          List.of(new AgentSkillRefDTO("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", tooLong)));
      config.setSubagents(List.of());
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.validator.validate(config))
              .getMessage()
              .contains("agent skill name must be <= 128 characters"));
    }
  }

  @Test
  void rejectsInternalToolSelection() {
    try (Fixture fixture = new Fixture(List.of())) {
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setTools(List.of(INTERNAL_TOOL_NAME));
      config.setSkills(List.of());
      config.setSubagents(List.of());

      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> fixture.validator.validate(config));
      assertTrue(error.getMessage().contains("internal tool"));
    }
  }

  @Test
  void rejectsSubagentNameExceeding64Characters() {
    try (Fixture fixture = new Fixture(List.of())) {
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setTools(List.of());
      config.setSkills(List.of());
      config.setSubagents(List.of("x".repeat(65)));

      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.validator.validate(config))
              .getMessage()
              .contains("subagent name must be <= 64 characters"));
    }
  }

  @Test
  void rejectsDuplicateHostToolRegistration() {
    // 两个不同 contributor 声明相同 model-visible name 会在统一 catalog 构造边界被拒绝。
    ToolDescriptor descriptor = hostDescriptor("dup");
    HarnessContributor first =
        HarnessContributor.of(
            new ContributorDescriptor(new ContributorId("first"), "First", "1", Set.of()),
            registrar ->
                registrar.registerTool("dup", tool(descriptor), ToolVisibility.SELECTABLE, 0));
    HarnessContributor second =
        HarnessContributor.of(
            new ContributorDescriptor(new ContributorId("second"), "Second", "1", Set.of()),
            registrar ->
                registrar.registerTool("dup", tool(descriptor), ToolVisibility.SELECTABLE, 0));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> HarnessCatalog.from(List.of(first, second)));
    assertTrue(error.getMessage().contains("duplicate tool name"));
  }

  @Test
  void acceptsDynamicMcpToolName() {
    // 意图：验证动态 MCP 工具按 mcp_tool.model_name 聚合到 RuntimeToolCatalog 后能够正常通过 Agent 配置校验
    McpServerRepository repo = mock(McpServerRepository.class);
    McpToolCatalog mcpCatalog = new McpToolCatalog(repo, mock(ExecutorService.class));

    UUID serverId = UUID.randomUUID();
    McpServer server = new McpServer();
    server.setId(serverId);
    server.setName("test-server");
    server.setConnectionType(McpConnectionType.REMOTE);
    server.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
    server.setDiscoveredVersion(1L);
    server.setEnabled(true);
    server.setVersion(1L);
    server.setConnectionConfig("{\"url\":\"http://localhost:8080\",\"headers\":{}}");
    server.setTimeoutMillis(5000L);

    UUID toolId = UUID.randomUUID();
    McpTool mcpTool = new McpTool();
    mcpTool.setId(toolId);
    mcpTool.setServerId(serverId);
    mcpTool.setSourceName("echo");
    mcpTool.setModelName("mcp_test_server_echo");
    mcpTool.setDescription("echo tool");
    mcpTool.setInputSchemaJson(
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}");
    mcpTool.setAvailable(true);
    mcpTool.setSchemaRevision(1L);

    when(repo.getAvailableToolByModelName("mcp_test_server_echo")).thenReturn(Optional.of(mcpTool));
    when(repo.getById(serverId)).thenReturn(Optional.of(server));
    when(repo.listAllServers()).thenReturn(List.of(server));
    when(repo.listAvailableTools(serverId)).thenReturn(List.of(mcpTool));

    HarnessToolCatalogAdapter staticAdapter =
        new HarnessToolCatalogAdapter(HarnessCatalog.from(List.of()));
    RuntimeToolCatalog composite =
        new CompositeRuntimeToolCatalog(List.of(staticAdapter, mcpCatalog));
    AgentDefinitionConfigValidator validator = new AgentDefinitionConfigValidator(composite);

    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of("mcp_test_server_echo"));
    config.setSkills(List.of());
    config.setSubagents(List.of());

    assertDoesNotThrow(() -> validator.validate(config));
  }

  private static Tool hostTool(String name) {
    return tool(hostDescriptor(name));
  }

  private static ToolDescriptor hostDescriptor(String name) {
    return new ToolDescriptor(
        name,
        name + " tool",
        name,
        new InputSchema("", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(5));
  }

  private static Tool tool(ToolDescriptor descriptor) {
    return tool(descriptor, ToolRequirements.none());
  }

  private static Tool tool(ToolDescriptor descriptor, ToolRequirements requirements) {
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
        throw new UnsupportedOperationException();
      }
    };
  }

  private static final class Fixture implements AutoCloseable {
    private final HarnessCatalog catalog;
    private final AgentDefinitionConfigValidator validator;

    private Fixture(List<Tool> tools) {
      List<HarnessContributor> contributors = new ArrayList<>();
      Tool dummyLoadSkill = mock(Tool.class);
      when(dummyLoadSkill.descriptor()).thenReturn(hostDescriptor("load_skill"));
      when(dummyLoadSkill.requirements()).thenReturn(ToolRequirements.none());
      Tool dummyTask = mock(Tool.class);
      when(dummyTask.descriptor()).thenReturn(hostDescriptor("task"));
      when(dummyTask.requirements()).thenReturn(ToolRequirements.none());
      contributors.add(new BuiltinHarnessContributor(dummyLoadSkill, dummyTask));

      if (!tools.isEmpty()) {
        contributors.add(
            HarnessContributor.of(
                new ContributorDescriptor(new ContributorId("test"), "Test", "1", Set.of()),
                registrar -> {
                  for (int i = 0; i < tools.size(); i++) {
                    Tool tool = tools.get(i);
                    registrar.registerTool(
                        "custom-tool" + (i == 0 ? "" : "-" + i),
                        tool,
                        ToolVisibility.SELECTABLE,
                        0);
                  }
                }));
      }
      this.catalog = HarnessCatalog.from(contributors);
      this.validator = new AgentDefinitionConfigValidator(new HarnessToolCatalogAdapter(catalog));
    }

    @Override
    public void close() {}
  }
}
