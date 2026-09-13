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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolSuccess;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpStableIds;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 端到端证明动态 MCP 工具在生产 Runtime 路径上的完整生命周期：
 *
 * <p>1. 通过 Streamable HTTP 协议发现动态 MCP 工具并持久化到 PostgreSQL；<br>
 * 2. 生产 {@link RuntimeToolCatalog} 与 HTTP 接口暴露该动态工具；<br>
 * 3. Agent 定义校验接受该动态 {@link AgentToolId}，拒绝非法工具；<br>
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

    // 2. 通过 HTTP POST /api/ai/mcp-servers 创建 Server，触发显式发现并入库
    String serverName = "mcp_prod_" + (System.currentTimeMillis() % 1_000_000_000L);
    String configJson =
        """
        {
          "type": "remote",
          "url": "%s",
          "headers": {
            "Authorization": "Bearer secret-bearer-token-do-not-leak"
          },
          "timeoutMillis": 15000
        }
        """
            .formatted(fakeServer.endpointUrl());

    McpServerCreateDTO createMcp = new McpServerCreateDTO();
    createMcp.setName(serverName);
    createMcp.setConfigJson(configJson);

    MvcResult createMcpResult =
        mockMvc
            .perform(
                post("/api/ai/mcp-servers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createMcp)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.name").value(serverName))
            .andExpect(jsonPath("$.data.version").value("0"))
            .andExpect(jsonPath("$.data.discoveryStatus").value("UNVERIFIED"))
            .andReturn();

    String serverId = data(createMcpResult).get("id").asText();
    assertNotNull(serverId);

    // 触发显式发现入库
    mockMvc
        .perform(
            post("/api/ai/mcp-servers/{id}/discover", serverId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"0\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.server.discoveryStatus").value("AVAILABLE"));

    // 从数据库中查询已持久化的动态工具行并获得稳定的 AgentToolId
    List<McpTool> persistedTools = mcpServerRepository.listTools(UUID.fromString(serverId));
    assertEquals(1, persistedTools.size());
    McpTool persistedTool = persistedTools.get(0);
    assertEquals("echo", persistedTool.getSourceName());
    AgentToolId agentToolId = McpStableIds.agentToolId(persistedTool.getId());
    assertNotNull(agentToolId);

    // 校验生产 RuntimeToolCatalog 也立即查询到该工具
    assertTrue(runtimeToolCatalog.findTool(agentToolId).isPresent());

    // 3. 验证 GET /api/ai/catalog/tools 正确暴露该动态工具元数据
    mockMvc
        .perform(get("/api/ai/catalog/tools"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data[?(@.id == '" + agentToolId.value() + "')].name")
                .value(persistedTool.getModelName()))
        .andExpect(
            jsonPath("$.data[?(@.id == '" + agentToolId.value() + "')].version").value("0.0"))
        .andExpect(
            jsonPath("$.data[?(@.id == '" + agentToolId.value() + "')].description")
                .value("Echo text"));

    // 4. 创建 Provider / Model / Agent catalog 事实，证明 Agent 定义校验器接受该 MCP 工具
    String suffix = Long.toString(System.nanoTime());
    String providerName = "provider-" + suffix;
    String modelName = "model-" + suffix;
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
            post("/api/ai/catalog/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(providerBody.toString()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.name").value(providerName));

    AgentModelCreateDTO modelDto = new AgentModelCreateDTO();
    modelDto.setProviderName(providerName);
    modelDto.setName(modelName);
    configureExecutableModel(modelDto);

    mockMvc
        .perform(
            post("/api/ai/catalog/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(modelDto)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.name").value(modelName));

    // 4.1 负向测试：证明未知工具 ID 会被校验器确定性拒绝
    AgentDefinitionConfigDTO invalidConfig = new AgentDefinitionConfigDTO();
    invalidConfig.setToolIds(List.of("unknown.invalid.tool.id"));
    invalidConfig.setSkills(List.of());
    invalidConfig.setSubagents(List.of());
    AgentDefinitionCreateDTO invalidAgent = new AgentDefinitionCreateDTO();
    invalidAgent.setName(agentName + "-invalid");
    invalidAgent.setModel(providerName + "/" + modelName);
    invalidAgent.setVariant("default");
    invalidAgent.setConfig(invalidConfig);

    mockMvc
        .perform(
            post("/api/ai/catalog/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidAgent)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));

    // 4.2 正向测试：Agent 定义校验器接受动态 MCP 工具 ID
    AgentDefinitionConfigDTO validConfig = new AgentDefinitionConfigDTO();
    validConfig.setToolIds(List.of(agentToolId.value()));
    validConfig.setSkills(List.of());
    validConfig.setSubagents(List.of());
    AgentDefinitionCreateDTO agentDto = new AgentDefinitionCreateDTO();
    agentDto.setName(agentName);
    agentDto.setModel(providerName + "/" + modelName);
    agentDto.setVariant("default");
    agentDto.setConfig(validConfig);

    mockMvc
        .perform(
            post("/api/ai/catalog/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(agentDto)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.name").value(agentName))
        .andExpect(jsonPath("$.data.config.toolIds[0]").value(agentToolId.value()));

    // 5. 验证 Spring 装配的 DatabaseTurnResolver 规划该动态贡献为冻结的 ToolBinding
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    UUID sessionId = UUID.randomUUID();
    UUID rootEntryId = UUID.randomUUID();
    Session session = new Session(sessionId, "session", now);
    BranchSettings branchSettings =
        new BranchSettings(agentName, new ModelSelection(providerName, modelName, "default"));
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
    ToolBinding mcpBinding = toolBindings.get(0);
    assertEquals(agentToolId, mcpBinding.definition().id());
    assertEquals(persistedTool.getModelName(), mcpBinding.definition().descriptor().name());
    assertEquals("0.0", mcpBinding.definition().descriptor().version());
    assertFalse(mcpBinding.environmentRequired());
    assertNull(mcpBinding.environmentId());
    assertEquals(McpStableIds.CONTRIBUTOR_ID.value(), mcpBinding.contributor().contributorId());
    assertEquals(
        McpStableIds.localName(persistedTool.getId()), mcpBinding.contributor().localName());

    // 6. 验证 Spring 装配的 ToolGateway 执行该动态 ToolBinding
    String callId = "call-" + UUID.randomUUID();
    String argumentsJson = "{\"message\":\"hello from full web runtime test\"}";
    ToolCall toolCall = new ToolCall(callId, persistedTool.getModelName(), argumentsJson);
    ToolInvocationRequest invocationRequest = new ToolInvocationRequest(toolCall, mcpBinding);
    assertInstanceOf(ToolGateway.Allow.class, toolGateway.preflight(invocationRequest));

    UUID invocationId = UUID.randomUUID();
    ToolGateway.Execution execution =
        new ToolGateway.Execution(invocationId, threadId, rootEntryId, 1, invocationRequest);

    CompletableFuture<ToolResult> resultFuture = new CompletableFuture<>();
    ToolGateway.Listener listener =
        new ToolGateway.Listener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onSucceeded(ToolSuccess success) {
            resultFuture.complete(success.result());
          }

          @Override
          public void onFailed(ToolGateway.Failure failure) {
            resultFuture.completeExceptionally(
                new IllegalStateException("tool execution failed: " + failure.error()));
          }

          @Override
          public void onCancelled(ToolInvocationError error) {
            resultFuture.completeExceptionally(
                new IllegalStateException("tool cancelled: " + error));
          }

          @Override
          public void onUnknown(ToolInvocationError error) {
            resultFuture.completeExceptionally(
                new IllegalStateException("tool indeterminate/unknown: " + error));
          }
        };

    ToolGateway.StartResult startResult = toolGateway.start(execution, listener);
    ToolGateway.Started started = assertInstanceOf(ToolGateway.Started.class, startResult);

    // 按照两阶段激活契约打开回调 gate 并启动执行
    started.handle().activate();

    // 确定性等待终端结果（避免任意 sleep）
    ToolResult terminalResult = resultFuture.get(15, TimeUnit.SECONDS);
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
    pricing.setCurrency("USD");
    pricing.setPricingTier("test");
    pricing.setServiceTier("default");
    pricing.setServiceTierMultiplier(BigDecimal.ONE);
    pricing.setVersion("v1");
    pricing.setInputPerMillionTokens(BigDecimal.ZERO);
    pricing.setOutputPerMillionTokens(BigDecimal.ZERO);
    pricing.setCacheReadPerMillionTokens(BigDecimal.ZERO);
    pricing.setCacheWritePerMillionTokens(BigDecimal.ZERO);
    pricing.setCacheWriteLongPerMillionTokens(BigDecimal.ZERO);
    pricing.setReasoningPerMillionTokens(BigDecimal.ZERO);
    config.setPricing(pricing);
    AgentModelVariantDTO variant = new AgentModelVariantDTO();
    variant.setId("default");
    config.setVariants(List.of(variant));
    config.setDefaultVariant("default");
    model.setConfig(config);
  }
}
