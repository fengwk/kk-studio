package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpTool;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelRateSetDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 端到端证明动态 MCP 工具在生产 Runtime 路径上的完整生命周期：
 *
 * <p>1. 通过 Streamable HTTP 协议发现动态 MCP 工具并持久化到 PostgreSQL；<br>
 * 2. 生产 {@link RuntimeToolCatalog} 与 HTTP 接口按模型可见 name 暴露该动态工具；<br>
 * 3. Agent 定义校验接受该动态模型可见 name，拒绝非法工具；<br>
 * 4. 生产装配的 {@link DatabaseTurnResolver} 把该动态贡献规划为冻结的 {@link ToolBinding}；<br>
 * 5. 生产装配的 {@link ToolGateway} 正确派发并两阶段激活执行，真实调用假 MCP Server 并完成终端断言。
 */
@AutoConfigureMockMvc
public class StudioMcpRuntimeToolIntegrationTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private McpServerRepository mcpServerRepository;
  @Autowired private RuntimeToolCatalog runtimeToolCatalog;
  @Autowired private DatabaseTurnResolver databaseTurnResolver;
  @Autowired private ToolGateway toolGateway;
  @Autowired private HarnessStore harnessStore;

  private FakeStreamableHttpMcpServer fakeServer;

  @BeforeEach
  void setUpServer() throws IOException {
    fakeServer = new FakeStreamableHttpMcpServer();
  }

  @AfterEach
  void tearDownServer() {
    if (fakeServer != null) {
      fakeServer.close();
    }
  }

  /** 验证动态 MCP 工具从发现、暴露、校验、规划到 ToolGateway 执行的完整生产路径。 */
  @Test
  public void fullDynamicMcpToolLifecycleFromDiscoveryToExecution() throws Exception {
    // 1. 启动 FakeStreamableHttpMcpServer 并注册带 inputSchema 的 echo 工具
    String schemaJson =
        """
        {
          "type": "object",
          "properties": {
            "message": {
              "type": "string",
              "description": "text to echo"
            }
          },
          "required": ["message"]
        }
        """;
    fakeServer.addTool("echo", "Echo text", schemaJson);

    // 2. 通过 HTTP POST /api/mcp-servers 创建 Server，再显式发现入库
    String serverName = "mcp_prod_" + (System.currentTimeMillis() % 1_000_000_000L);

    McpServerCreateDTO createMcp = new McpServerCreateDTO();
    createMcp.setName(serverName);
    createMcp.setUrl(fakeServer.endpointUrl());
    createMcp.setHeaders(Map.of("Authorization", "Bearer secret-bearer-token-do-not-leak"));
    createMcp.setEnabled(true);
    createMcp.setTimeoutMillis(15000L);

    mockMvc
        .perform(
            post("/api/mcp-servers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createMcp)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.name").value(serverName))
        .andExpect(jsonPath("$.data.version").value("0"))
        .andExpect(jsonPath("$.data.discoveryStatus").value("UNVERIFIED"));

    mockMvc
        .perform(post("/api/mcp-servers/{name}/discover", serverName).param("expectedVersion", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.discoveryStatus").value("AVAILABLE"))
        .andExpect(jsonPath("$.data.toolCount").value(1));

    // 从数据库中查询已持久化的动态工具行；其模型可见 name 就是唯一 Agent 侧身份与主键
    List<McpTool> persistedTools = mcpServerRepository.listTools(serverName);
    assertEquals(1, persistedTools.size());
    McpTool persistedTool = persistedTools.get(0);
    assertEquals("echo", persistedTool.sourceName());
    String modelName = persistedTool.name();
    assertEquals("mcp_" + serverName + "_echo", modelName);

    // 校验生产 RuntimeToolCatalog 也立即按该 name 查询到该工具
    assertTrue(runtimeToolCatalog.findTool(modelName).isPresent());

    // 3. 验证 GET /api/tools 只按 name 暴露该动态工具元数据（不含 id/version）
    mockMvc
        .perform(get("/api/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[?(@.name == '" + modelName + "')].name").value(modelName))
        .andExpect(
            jsonPath("$.data[?(@.name == '" + modelName + "')].description").value("Echo text"))
        .andExpect(jsonPath("$.data[?(@.name == '" + modelName + "')].id").doesNotExist())
        .andExpect(jsonPath("$.data[?(@.name == '" + modelName + "')].version").doesNotExist());

    // 4. 创建 Provider / Model / Agent catalog 事实，证明 Agent 定义校验器接受该 MCP 工具
    String suffix = Long.toString(System.nanoTime());
    String providerName = "provider-" + suffix;
    String configuredModelName = "model-" + suffix;
    String agentName = "agent-" + suffix;

    AgentProviderCreateDTO providerDto = new AgentProviderCreateDTO();
    providerDto.setName(providerName);
    providerDto.setDescription("provider description");
    providerDto.setProviderType("openai");
    providerDto.setCredential("secret-credential-value");
    providerDto.setModelCallTimeoutMillis(120000L);
    providerDto.setModelCallIdleTimeoutMillis(3000L);
    ObjectNode providerBody = objectMapper.valueToTree(providerDto);
    providerBody.put("credential", providerDto.getCredential());

    mockMvc
        .perform(
            post("/api/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(providerBody.toString()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.name").value(providerName));

    AgentModelCreateDTO modelDto = new AgentModelCreateDTO();
    modelDto.setKind(AgentModelKind.MODEL);
    modelDto.setProviderName(providerName);
    modelDto.setName(configuredModelName);
    configureExecutableModel(modelDto);

    mockMvc
        .perform(
            post("/api/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(modelDto)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.name").value(configuredModelName));

    // 4.1 负向测试：证明未知工具名会被校验器确定性拒绝
    AgentDefinitionConfigDTO invalidConfig = new AgentDefinitionConfigDTO();
    invalidConfig.setTools(List.of("unknown_invalid_tool"));
    invalidConfig.setSkills(List.of());
    invalidConfig.setSubagents(List.of());
    AgentDefinitionCreateDTO invalidAgent = new AgentDefinitionCreateDTO();
    invalidAgent.setName(agentName + "-invalid");
    invalidAgent.setModel(configuredModelName);
    invalidAgent.setVariant("default");
    invalidAgent.setConfig(invalidConfig);

    mockMvc
        .perform(
            post("/api/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidAgent)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));

    // 4.2 正向测试：Agent 定义校验器接受动态 MCP 工具名
    AgentDefinitionConfigDTO validConfig = new AgentDefinitionConfigDTO();
    validConfig.setTools(List.of(modelName));
    validConfig.setSkills(List.of());
    validConfig.setSubagents(List.of());
    AgentDefinitionCreateDTO agentDto = new AgentDefinitionCreateDTO();
    agentDto.setName(agentName);
    agentDto.setModel(configuredModelName);
    agentDto.setVariant("default");
    agentDto.setConfig(validConfig);

    mockMvc
        .perform(
            post("/api/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(agentDto)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.name").value(agentName))
        .andExpect(jsonPath("$.data.config.tools[0]").value(modelName));

    // 5. 验证 Spring 装配的 DatabaseTurnResolver 规划该动态贡献为冻结的 ToolBinding
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    UUID sessionId = UUID.randomUUID();
    UUID rootEntryId = UUID.randomUUID();
    Session session = new Session(sessionId, "session", now);
    BranchSettings branchSettings =
        new BranchSettings(agentName, new ModelSelection(configuredModelName, "default"), null);
    Entry rootEntry = new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings), now);

    harnessStore.transaction(
        tx -> {
          tx.insertSession(session);
          tx.insertEntry(rootEntry);
          return null;
        });

    EntryPath entryPath = new EntryPath(List.of(rootEntry));

    UUID threadId = UUID.randomUUID();
    TurnResolver.Result resolverResult = databaseTurnResolver.resolve(threadId, entryPath, null);
    TurnResolver.Resolved resolved = assertInstanceOf(TurnResolver.Resolved.class, resolverResult);
    ModelRequestSpec spec = resolved.spec();
    assertNotNull(spec);

    List<ToolBinding> toolBindings = spec.toolBindings();
    assertEquals(1, toolBindings.size());
    ToolBinding mcpBinding =
        toolBindings.stream()
            .filter(binding -> binding.definition().descriptor().name().equals(modelName))
            .findFirst()
            .orElseThrow();
    assertEquals(modelName, mcpBinding.definition().descriptor().name());
    assertEquals(EnvironmentSupport.NONE, mcpBinding.environmentSupport());
    assertNull(mcpBinding.environmentId());
    // ContributionId 仅对模型可见 name 做 canonical 字符转换，不引入隐藏 UUID。
    assertEquals(McpToolCatalog.CONTRIBUTOR_ID.value(), mcpBinding.contributor().contributorId());
    assertEquals(McpToolCatalog.localName(modelName), mcpBinding.contributor().localName());

    // 6. 验证 Spring 装配的 ToolGateway 执行该动态 ToolBinding
    String callId = "call-" + UUID.randomUUID();
    String argumentsJson = "{\"message\":\"hello from full web runtime test\"}";
    ToolCall toolCall = new ToolCall(callId, modelName, argumentsJson);
    ToolInvocationRequest invocationRequest = new ToolInvocationRequest(toolCall, mcpBinding);
    assertInstanceOf(ToolGateway.Allow.class, toolGateway.preflight(invocationRequest));

    UUID invocationId = UUID.randomUUID();
    RecordingRemoteExecutions remoteExecutions = new RecordingRemoteExecutions();
    ToolGateway.Execution execution =
        new ToolGateway.Execution(
            invocationId,
            threadId,
            rootEntryId,
            1,
            invocationRequest,
            ToolApproval.notRequired(),
            remoteExecutions);

    // admit 只做本地检查并持有资源；阻塞执行返回唯一终态，之后释放资源。
    ToolGateway.Admitted admitted =
        assertInstanceOf(ToolGateway.Admitted.class, toolGateway.admit(execution));
    ToolGateway.Completion completion;
    try (ToolGateway.Run run = admitted.run()) {
      completion = run.execute(partial -> {});
    }
    ToolResult terminalResult =
        assertInstanceOf(ToolGateway.Succeeded.class, completion).success().result();
    assertNotNull(terminalResult);
    assertEquals(callId, terminalResult.toolCallId());
    assertFalse(terminalResult.error());
    assertFalse(terminalResult.contents().isEmpty());

    TextResultContent textContent =
        assertInstanceOf(TextResultContent.class, terminalResult.contents().get(0));
    assertTrue(textContent.text().contains("hello from full web runtime test"));

    // 验证假 MCP Server 确实收到了对应的 tools/call
    assertEquals(1, fakeServer.toolCallCount());
    assertTrue(fakeServer.receivedToolCallNames().contains("echo"));
    assertTrue(
        remoteExecutions.started.isEmpty(),
        "host MCP tool does not register a remote Environment execution");
  }

  /** 显式记录观察者，避免用生产 no-op 代替本测试的登记边界。 */
  private static final class RecordingRemoteExecutions
      implements ToolGateway.RemoteExecutionObserver {
    private final List<UUID> started = new ArrayList<>();

    @Override
    public boolean tryStart(UUID remoteExecutionId) {
      started.add(remoteExecutionId);
      return true;
    }

    @Override
    public void confirmExit(UUID remoteExecutionId) {}

    @Override
    public void confirmNotStarted(UUID remoteExecutionId) {}
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private static void configureExecutableModel(AgentModelCreateDTO model) {
    model.setModelId(model.getName());
    AgentModelConfigDTO config = new AgentModelConfigDTO();
    AgentModelLimitDTO limit = new AgentModelLimitDTO();
    limit.setContext(32768);
    limit.setOutput(4096);
    config.setLimit(limit);
    AgentModelAbilitiesDTO abilities = new AgentModelAbilitiesDTO();
    abilities.setTools(true);
    abilities.setReasoning(false);
    abilities.setInputModalities(List.of(AgentModelInputModality.TEXT));
    config.setAbilities(abilities);
    AgentModelPricingDTO pricing = new AgentModelPricingDTO();
    AgentModelRateSetDTO base = new AgentModelRateSetDTO();
    base.setInput(BigDecimal.ZERO);
    base.setOutput(BigDecimal.ZERO);
    base.setCacheRead(BigDecimal.ZERO);
    base.setCacheWrite(BigDecimal.ZERO);
    base.setCacheWriteByTtl(Map.of());
    base.setReasoning(null);
    pricing.setBase(base);
    pricing.setOverrides(List.of());
    pricing.setMultipliers(List.of());
    config.setPricing(pricing);
    AgentModelVariantDTO variant = new AgentModelVariantDTO();
    variant.setId("default");
    config.setVariants(List.of(variant));
    config.setDefaultVariant("default");
    model.setConfig(config);
  }
}
