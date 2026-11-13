package fun.fengwk.kkstudio.web.controller;

import static fun.fengwk.kkstudio.platformtest.harness.HarnessRuntimeTestFixtures.jsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelAbilitiesDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupMemberDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelLimitDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelVariantDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelUpdateDTO;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Provider、Model、Agent Catalog Controller 的 HTTP 契约。 */
@AutoConfigureMockMvc
public class AgentProviderModelControllerTest extends WebPostgresTestSupport {

  /** 用于证明 wire modelId 与逻辑 name 相互独立的固定值。 */
  private static final String WIRE_MODEL_ID = "wire-model-id";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  /** 远端模型发现：成功返回排序 id，缺少 baseUrl 为 400，上游非 2xx 为 502。 */
  @Test
  public void shouldListProviderRemoteModels() throws Exception {
    HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger upstreamStatus = new AtomicInteger(200);
    upstream.createContext(
        "/v1/models",
        exchange -> {
          byte[] body =
              "{\"data\":[{\"id\":\"m-b\"},{\"id\":\"m-a\"}]}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(upstreamStatus.get(), body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    upstream.start();
    try {
      String suffix = Long.toString(System.nanoTime());
      String reachable = "remote-" + suffix;
      createProvider(reachable, "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1");
      mockMvc
          .perform(get("/api/platform/providers/{name}/remote-models", reachable))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0]").value("m-a"))
          .andExpect(jsonPath("$[1]").value("m-b"));

      upstreamStatus.set(401);
      mockMvc
          .perform(get("/api/platform/providers/{name}/remote-models", reachable))
          .andExpect(status().isBadGateway())
          .andExpect(jsonPath("$.code").value("upstream_failed"));

      String noBaseUrl = "remote-nourl-" + suffix;
      createProvider(noBaseUrl, null);
      mockMvc
          .perform(get("/api/platform/providers/{name}/remote-models", noBaseUrl))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("validation"));
    } finally {
      upstream.stop(0);
    }
  }

  private void createProvider(String name, String baseUrl) throws Exception {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("name", name);
    body.put("providerType", "openai");
    body.set("credential", objectMapper.valueToTree(Map.of("operation", "SET", "value", "k")));
    if (baseUrl != null) {
      body.put("baseUrl", baseUrl);
    }
    mockMvc
        .perform(
            post("/api/platform/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isCreated());
  }

  @Test
  public void shouldUseNameBasedIdentitiesAndEnforceCatalogGuards() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    String providerName = "provider-" + suffix;
    String modelName = "vendor/model with space-" + suffix;
    String agentName = "agent-" + suffix;

    ProviderCreateDTO provider = new ProviderCreateDTO();
    provider.setName(providerName);
    provider.setDescription("provider description");
    provider.setProviderType(ProviderType.OPENAI);
    provider.setCredential(SecretUpdate.set("secret-value"));
    provider.setModelCallTimeoutMillis(120_000L);
    provider.setModelCallIdleTimeoutMillis(3_000L);
    ObjectNode providerBody = objectMapper.valueToTree(provider);
    providerBody.put("modelCallTimeoutMillis", provider.getModelCallTimeoutMillis().toString());
    providerBody.put(
        "modelCallIdleTimeoutMillis", provider.getModelCallIdleTimeoutMillis().toString());
    JsonNode providerData =
        data(
            mockMvc
                .perform(
                    post("/api/platform/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(providerBody.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(providerName))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.credential.configured").value(true))
                .andExpect(jsonPath("$.configured").doesNotExist())
                .andExpect(jsonPath("$.version").value("0"))
                .andReturn());
    assertEquals(providerName, providerData.path("name").asText());
    assertFalse(providerData.has("id"));
    assertEquals(true, providerData.path("credential").path("configured").asBoolean());

    ProviderCreateDTO duplicateProvider = new ProviderCreateDTO();
    duplicateProvider.setName(providerName);
    duplicateProvider.setProviderType(ProviderType.OPENAI);
    mockMvc
        .perform(
            post("/api/platform/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(duplicateProvider)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"))
        .andExpect(jsonPath("$.errors.resource").doesNotExist());

    SingleModelCreateDTO model = new SingleModelCreateDTO();
    model.setProviderName(providerName);
    model.setName(modelName);
    configureExecutableModel(model);
    // 其余字段合法时缺少联合判别字段 type 必须失败，不能由 fixture 掩盖必填字段契约。
    ObjectNode missingKind = objectMapper.valueToTree(model);
    missingKind.remove("type");
    mockMvc
        .perform(
            post("/api/platform/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(missingKind.toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    JsonNode modelData =
        data(
            mockMvc
                .perform(
                    post("/api/platform/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(model)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerName").value(providerName))
                .andExpect(jsonPath("$.name").value(modelName))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.modelId").value(WIRE_MODEL_ID))
                .andExpect(jsonPath("$.version").value("0"))
                .andReturn());
    assertEquals(providerName, modelData.path("providerName").asText());
    assertEquals(modelName, modelData.path("name").asText());
    assertEquals(WIRE_MODEL_ID, modelData.path("modelId").asText());
    assertFalse(modelData.has("id"));

    AgentConfigDTO agentConfig = new AgentConfigDTO();
    agentConfig.setTools(List.of());
    agentConfig.setSkills(List.of());
    agentConfig.setSubagents(List.of());
    AgentCreateDTO agent = new AgentCreateDTO();
    agent.setName(agentName);
    agent.setExecution(execution(modelName));
    agent.setConfig(agentConfig);
    JsonNode agentData =
        data(
            mockMvc
                .perform(
                    post("/api/platform/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(agent)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(agentName))
                .andExpect(jsonPath("$.execution.driver").value("builtin"))
                .andExpect(jsonPath("$.execution.settings.modelName").value(modelName))
                .andExpect(jsonPath("$.execution.settings.variant").value("default"))
                .andExpect(jsonPath("$.model").doesNotExist())
                .andExpect(jsonPath("$.variant").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.modelId").doesNotExist())
                .andExpect(jsonPath("$.config.tools").isEmpty())
                .andExpect(jsonPath("$.config.skills").isEmpty())
                .andExpect(jsonPath("$.config.subagents").isEmpty())
                .andExpect(jsonPath("$.version").value("0"))
                .andReturn());
    assertEquals(agentName, agentData.path("name").asText());
    assertEquals(
        modelName, agentData.path("execution").path("settings").path("modelName").asText());
    assertFalse(agentData.has("id"));
    assertFalse(agentData.has("modelId"));

    JsonNode providerPage =
        data(
            mockMvc.perform(get("/api/platform/providers")).andExpect(status().isOk()).andReturn());
    JsonNode listedProvider = findResult(providerPage, providerName);
    assertFalse(listedProvider.has("id"));
    // 凭据只以 SecretConfiguredDTO 形式回读，绝不回显值。
    assertTrue(listedProvider.path("credential").path("configured").asBoolean());
    assertFalse(listedProvider.has("configured"));

    JsonNode modelPage =
        data(mockMvc.perform(get("/api/platform/models")).andExpect(status().isOk()).andReturn());
    JsonNode listedModel = findResult(modelPage, modelName);
    assertEquals(providerName, listedModel.path("providerName").asText());
    assertEquals(WIRE_MODEL_ID, listedModel.path("modelId").asText());
    assertFalse(listedModel.has("id"));

    JsonNode agentPage =
        data(mockMvc.perform(get("/api/platform/agents")).andExpect(status().isOk()).andReturn());
    JsonNode listedAgent = findAgent(agentPage, agentName);
    assertEquals(
        modelName, listedAgent.path("execution").path("settings").path("modelName").asText());
    assertFalse(listedAgent.has("model"));
    assertFalse(listedAgent.has("variant"));
    assertFalse(listedAgent.has("id"));
    assertFalse(listedAgent.has("modelId"));

    ProviderUpdateDTO providerUpdate = new ProviderUpdateDTO();
    providerUpdate.setDescription("updated provider");
    providerUpdate.setCredential(SecretUpdate.keep());
    providerUpdate.setModelCallTimeoutMillis(1_800_000L);
    providerUpdate.setModelCallIdleTimeoutMillis(120_000L);
    providerUpdate.setExpectedVersion(0L);
    ObjectNode providerUpdateBody = objectMapper.valueToTree(providerUpdate);
    providerUpdateBody.put("name", "renamed-" + providerName);
    mockMvc
        .perform(
            put("/api/platform/providers/{name}", providerName)
                .contentType(MediaType.APPLICATION_JSON)
                .content(providerUpdateBody.toString()))
        .andExpect(status().isBadRequest());
    JsonNode updatedProvider =
        data(
            mockMvc
                .perform(
                    put("/api/platform/providers/{name}", providerName)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(providerUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(providerName))
                .andExpect(jsonPath("$.description").value("updated provider"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.credential.configured").value(true))
                .andExpect(jsonPath("$.version").value("1"))
                .andReturn());
    assertEquals(providerName, updatedProvider.path("name").asText());
    assertFalse(updatedProvider.has("id"));
    assertTrue(updatedProvider.path("credential").path("configured").asBoolean());

    providerUpdate.setExpectedVersion(0L);
    mockMvc
        .perform(
            put("/api/platform/providers/{name}", providerName)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(providerUpdate)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"))
        .andExpect(jsonPath("$.errors.resource").doesNotExist())
        .andExpect(jsonPath("$.errors.expectedVersion").value("0"))
        .andExpect(jsonPath("$.errors.actualVersion").value("1"));

    SingleModelUpdateDTO modelUpdate = new SingleModelUpdateDTO();
    modelUpdate.setProviderName(providerName);
    modelUpdate.setName(modelName);
    modelUpdate.setModelId(WIRE_MODEL_ID);
    modelUpdate.setDescription("updated model");
    modelUpdate.setConfig(model.getConfig());
    modelUpdate.setExpectedVersion(0L);
    ObjectNode modelUpdateBody = objectMapper.valueToTree(modelUpdate);
    modelUpdateBody.put("unknownField", "1");
    // 全局模型 PUT 拒绝未知字段。
    mockMvc
        .perform(
            put(modelPath(providerName, modelName))
                .contentType(MediaType.APPLICATION_JSON)
                .content(modelUpdateBody.toString()))
        .andExpect(status().isBadRequest());
    // 测试意图：验证 PUT /api/platform/models/{*modelName} 使用 body 中的 expectedVersion CAS
    // 更新可变属性，返回 200 OK。
    JsonNode updatedModel =
        data(
            mockMvc
                .perform(
                    put(modelPath(providerName, modelName))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(modelUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerName").value(providerName))
                .andExpect(jsonPath("$.name").value(modelName))
                .andExpect(jsonPath("$.description").value("updated model"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.modelId").value(WIRE_MODEL_ID))
                .andExpect(jsonPath("$.version").value("1"))
                .andReturn());
    assertEquals(providerName, updatedModel.path("providerName").asText());
    assertEquals(modelName, updatedModel.path("name").asText());
    assertEquals(WIRE_MODEL_ID, updatedModel.path("modelId").asText());

    // 测试意图：验证 PUT /api/platform/models/{*modelName} 在 expectedVersion 过期时触发 CAS
    // 版本冲突，返回 409 Conflict。
    modelUpdate.setExpectedVersion(0L);
    mockMvc
        .perform(
            put(modelPath(providerName, modelName))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(modelUpdate)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"))
        .andExpect(jsonPath("$.errors.resource").doesNotExist())
        .andExpect(jsonPath("$.errors.expectedVersion").value("0"))
        .andExpect(jsonPath("$.errors.actualVersion").value("1"));

    AgentUpdateDTO agentUpdate = new AgentUpdateDTO();
    agentUpdate.setDescription("updated agent");
    agentUpdate.setSystemPrompt("updated prompt");
    agentUpdate.setExecution(execution(modelName));
    agentUpdate.setConfig(agentConfig);
    agentUpdate.setExpectedVersion(0L);
    ObjectNode agentUpdateBody = objectMapper.valueToTree(agentUpdate);
    agentUpdateBody.put("name", "renamed-" + agentName);
    mockMvc
        .perform(
            put("/api/platform/agents/{name}", agentName)
                .contentType(MediaType.APPLICATION_JSON)
                .content(agentUpdateBody.toString()))
        .andExpect(status().isBadRequest());
    JsonNode updatedAgent =
        data(
            mockMvc
                .perform(
                    put("/api/platform/agents/{name}", agentName)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(agentUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(agentName))
                .andExpect(jsonPath("$.execution.driver").value("builtin"))
                .andExpect(jsonPath("$.execution.settings.modelName").value(modelName))
                .andExpect(jsonPath("$.execution.settings.variant").value("default"))
                .andExpect(jsonPath("$.model").doesNotExist())
                .andExpect(jsonPath("$.variant").doesNotExist())
                .andExpect(jsonPath("$.description").value("updated agent"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.modelId").doesNotExist())
                .andExpect(jsonPath("$.version").value("1"))
                .andReturn());
    assertEquals(agentName, updatedAgent.path("name").asText());
    assertEquals(
        modelName, updatedAgent.path("execution").path("settings").path("modelName").asText());

    agentUpdate.setExpectedVersion(0L);
    mockMvc
        .perform(
            put("/api/platform/agents/{name}", agentName)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(agentUpdate)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"))
        .andExpect(jsonPath("$.errors.resource").doesNotExist())
        .andExpect(jsonPath("$.errors.expectedVersion").value("0"))
        .andExpect(jsonPath("$.errors.actualVersion").value("1"));

    mockMvc
        .perform(
            delete("/api/platform/providers/{name}", providerName).param("expectedVersion", "1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("in_use"))
        .andExpect(jsonPath("$.errors.resource").doesNotExist());
    // 测试意图：验证 DELETE /api/platform/models/{*modelName}?expectedVersion=... 在被 Agent
    // 引用时拒绝删除，返回 409 Conflict。
    mockMvc
        .perform(delete(modelPath(providerName, modelName)).param("expectedVersion", "1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("in_use"))
        .andExpect(jsonPath("$.errors.resource").doesNotExist());

    mockMvc
        .perform(delete("/api/platform/agents/{name}", agentName).param("expectedVersion", "1"))
        .andExpect(status().isNoContent());
    // 测试意图：验证 DELETE /api/platform/models/{*modelName}?expectedVersion=... 解除引用后成功
    // CAS 硬删除，返回 204 NoContent。
    mockMvc
        .perform(delete(modelPath(providerName, modelName)).param("expectedVersion", "1"))
        .andExpect(status().isNoContent());
    // catch-all 路由将集合路径解析为空名称；不接受 query 参数代替路径身份。
    mockMvc
        .perform(
            put("/api/platform/models")
                .param("providerName", providerName)
                .param("modelName", modelName)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(modelUpdate)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    mockMvc
        .perform(
            delete("/api/platform/models")
                .param("providerName", providerName)
                .param("modelName", modelName)
                .param("expectedVersion", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    mockMvc
        .perform(
            delete("/api/platform/providers/{name}", providerName).param("expectedVersion", "1"))
        .andExpect(status().isNoContent());
  }

  /** 可空 Variant 必须在真实 HTTP 响应中保留，以便客户端原样执行完整 PUT。 */
  @Test
  public void shouldRoundTripBuiltinExecutionWithNullVariant() throws Exception {
    String name = "null-variant-agent-" + System.nanoTime();
    String body =
        """
        {"name":"%s","execution":{"driver":"builtin",
        "settings":{"modelName":"acceptance-stub","variant":null}},
        "config":{"tools":[],"skills":[],"subagents":[]}}
        """
            .formatted(name);
    JsonNode created =
        data(
            mockMvc
                .perform(
                    post("/api/platform/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn());
    JsonNode settings = created.path("execution").path("settings");
    assertTrue(settings.has("variant"));
    assertTrue(settings.path("variant").isNull());
    ObjectNode update = objectMapper.createObjectNode();
    update.set("execution", created.path("execution"));
    update.set("config", created.path("config"));
    update.put("expectedVersion", created.path("version").asText());
    JsonNode updated =
        data(
            mockMvc
                .perform(
                    put("/api/platform/agents/{name}", name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update.toString()))
                .andExpect(status().isOk())
                .andReturn());
    assertTrue(updated.path("execution").path("settings").has("variant"));
    assertTrue(updated.path("execution").path("settings").path("variant").isNull());
  }

  @Test
  public void shouldRejectMalformedModelRefsAndDatabaseIdContracts() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    AgentConfigDTO config = new AgentConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());

    AgentCreateDTO malformed = new AgentCreateDTO();
    malformed.setName("malformed-agent-" + suffix);
    malformed.setExecution(execution(" invalid surrounding whitespace "));
    malformed.setConfig(config);
    mockMvc
        .perform(
            post("/api/platform/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(malformed)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.errors.resource").doesNotExist());

    SingleModelCreateDTO idBasedModel = new SingleModelCreateDTO();
    idBasedModel.setName("id-based-model-" + suffix);
    configureExecutableModel(idBasedModel);
    ObjectNode idBasedModelBody = objectMapper.valueToTree(idBasedModel);
    idBasedModelBody.remove("providerName");
    idBasedModelBody.put("unknownField", "1");
    mockMvc
        .perform(
            post("/api/platform/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(idBasedModelBody.toString()))
        .andExpect(status().isBadRequest());

    AgentCreateDTO idBasedAgent = new AgentCreateDTO();
    idBasedAgent.setName("id-based-agent-" + suffix);
    idBasedAgent.setExecution(execution("acceptance-stub"));
    idBasedAgent.setConfig(config);
    ObjectNode unknownAgentBody = objectMapper.valueToTree(idBasedAgent);
    unknownAgentBody.put("unknownField", "1");
    mockMvc
        .perform(
            post("/api/platform/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(unknownAgentBody.toString()))
        .andExpect(status().isBadRequest());
    ObjectNode idBasedAgentBody = objectMapper.valueToTree(idBasedAgent);
    idBasedAgentBody.put("modelId", "1");
    mockMvc
        .perform(
            post("/api/platform/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(idBasedAgentBody.toString()))
        .andExpect(status().isBadRequest());

    ObjectNode idBasedProviderBody = objectMapper.createObjectNode();
    idBasedProviderBody.put("id", "1");
    idBasedProviderBody.put("providerType", "openai");
    mockMvc
        .perform(
            post("/api/platform/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(idBasedProviderBody.toString()))
        .andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/platform/providers/1")).andExpect(status().isMethodNotAllowed());
    mockMvc.perform(get("/api/platform/models/1")).andExpect(status().isMethodNotAllowed());
    mockMvc.perform(get("/api/platform/agents/1")).andExpect(status().isMethodNotAllowed());
    mockMvc
        .perform(
            put("/api/platform/models/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(delete("/api/platform/models/1").param("expectedVersion", "0"))
        .andExpect(status().isNotFound());
  }

  @Test
  void shouldRenameModelAndCascadeToReferencingAgents() throws Exception {
    String suffix = String.valueOf(System.nanoTime());
    String providerName = "provider-rename-" + suffix;
    String oldModelName = "vendor/old model-" + suffix;
    String newModelName = "vendor/new model-" + suffix;
    String agentName = "agent-cascade-" + suffix;

    ProviderCreateDTO provider = new ProviderCreateDTO();
    provider.setName(providerName);
    provider.setProviderType(ProviderType.OPENAI);
    mockMvc
        .perform(
            post("/api/platform/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(provider)))
        .andExpect(status().isCreated());

    SingleModelCreateDTO model = new SingleModelCreateDTO();
    model.setProviderName(providerName);
    model.setName(oldModelName);
    configureExecutableModel(model);
    JsonNode createdModel =
        data(
            mockMvc
                .perform(
                    post("/api/platform/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(model)))
                .andExpect(status().isCreated())
                .andReturn());

    AgentCreateDTO agent = new AgentCreateDTO();
    agent.setName(agentName);
    agent.setExecution(execution(oldModelName));
    AgentConfigDTO agentConfig = new AgentConfigDTO();
    agentConfig.setTools(List.of());
    agentConfig.setSkills(List.of());
    agentConfig.setSubagents(List.of());
    agent.setConfig(agentConfig);
    mockMvc
        .perform(
            post("/api/platform/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(agent)))
        .andExpect(status().isCreated());

    // 1. 验证通过 PUT /api/platform/models/{*oldModelName} 进行重命名
    SingleModelUpdateDTO renameUpdate = new SingleModelUpdateDTO();
    renameUpdate.setProviderName(providerName);
    renameUpdate.setName(newModelName);
    renameUpdate.setModelId(WIRE_MODEL_ID);
    renameUpdate.setDescription("renamed model description");
    renameUpdate.setConfig(model.getConfig());
    renameUpdate.setExpectedVersion(0L);

    JsonNode renamedResult =
        data(
            mockMvc
                .perform(
                    put(modelPath(providerName, oldModelName))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(renameUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerName").value(providerName))
                .andExpect(jsonPath("$.name").value(newModelName))
                .andExpect(jsonPath("$.version").value("1"))
                .andReturn());
    assertEquals(newModelName, renamedResult.path("name").asText());
    assertEquals(createdModel.path("createdAt").asText(), renamedResult.path("createdAt").asText());

    // 2. 验证旧路径更新返回 404
    mockMvc
        .perform(
            put(modelPath(providerName, oldModelName))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(renameUpdate)))
        .andExpect(status().isNotFound());

    // 3. 验证模型列表已更新为新名称，旧名称不再存在
    JsonNode modelPage =
        data(mockMvc.perform(get("/api/platform/models")).andExpect(status().isOk()).andReturn());
    assertFalse(hasResult(modelPage, oldModelName));
    JsonNode listedRenamedModel = findResult(modelPage, newModelName);
    assertEquals(providerName, listedRenamedModel.path("providerName").asText());
    assertEquals("1", listedRenamedModel.path("version").asText());

    // 4. 验证 Agent 精确引用新全局名称（包含斜杠和空格），且版本递增为 1
    JsonNode agentPage =
        data(mockMvc.perform(get("/api/platform/agents")).andExpect(status().isOk()).andReturn());
    JsonNode listedAgent = findAgent(agentPage, agentName);
    assertEquals(
        newModelName, listedAgent.path("execution").path("settings").path("modelName").asText());
    assertEquals(
        "default", listedAgent.path("execution").path("settings").path("variant").asText());
    assertEquals("1", listedAgent.path("version").asText());

    // 5. 即使目标 Model 属于另一个 Provider，rename 仍返回 409 duplicate。
    String otherModelName = "vendor/other-model-" + suffix;
    SingleModelCreateDTO otherModel = new SingleModelCreateDTO();
    String otherProviderName = "other-provider-" + suffix;
    ProviderCreateDTO otherProvider = new ProviderCreateDTO();
    otherProvider.setName(otherProviderName);
    otherProvider.setProviderType(ProviderType.OPENAI);
    mockMvc
        .perform(
            post("/api/platform/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(otherProvider)))
        .andExpect(status().isCreated());
    otherModel.setProviderName(otherProviderName);
    otherModel.setName(otherModelName);
    configureExecutableModel(otherModel);
    mockMvc
        .perform(
            post("/api/platform/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(otherModel)))
        .andExpect(status().isCreated());

    SingleModelUpdateDTO conflictRename = new SingleModelUpdateDTO();
    conflictRename.setProviderName(providerName);
    conflictRename.setName(otherModelName);
    conflictRename.setModelId(WIRE_MODEL_ID);
    conflictRename.setConfig(model.getConfig());
    conflictRename.setExpectedVersion(1L);
    mockMvc
        .perform(
            put(modelPath(providerName, newModelName))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(conflictRename)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"))
        .andExpect(jsonPath("$.errors.resource").doesNotExist());

    // 全局名称跨 Provider 与 MODEL/GROUP 唯一：合法组也不能占用已有 Model 名。
    ModelGroupMemberDTO member = new ModelGroupMemberDTO();
    member.setModelName(otherModelName);
    member.setVariant("default");
    ModelGroupConfigDTO group = new ModelGroupConfigDTO();
    group.setMembers(List.of(member));
    group.setFallbackStatusCodes(List.of(429));
    group.setCooldownSeconds(0);
    ModelGroupCreateDTO duplicateGroup = new ModelGroupCreateDTO();
    duplicateGroup.setName(newModelName);
    duplicateGroup.setGroup(group);
    mockMvc
        .perform(
            post("/api/platform/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(duplicateGroup)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"));
    otherModel.setName(newModelName);
    mockMvc
        .perform(
            post("/api/platform/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(otherModel)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"));

    // 验证目标冲突回滚后原模型及 Agent 保持冲突尝试前状态
    JsonNode agentPageAfterConflict =
        data(mockMvc.perform(get("/api/platform/agents")).andExpect(status().isOk()).andReturn());
    JsonNode agentAfterConflict = findAgent(agentPageAfterConflict, agentName);
    assertEquals(
        newModelName,
        agentAfterConflict.path("execution").path("settings").path("modelName").asText());
    assertEquals("1", agentAfterConflict.path("version").asText());

    // 6. 验证 expectedVersion 过期时触发 CAS 版本冲突 409
    SingleModelUpdateDTO staleRename = new SingleModelUpdateDTO();
    staleRename.setProviderName(providerName);
    staleRename.setName("vendor/stale-model-" + suffix);
    staleRename.setModelId(WIRE_MODEL_ID);
    staleRename.setConfig(model.getConfig());
    staleRename.setExpectedVersion(0L);
    mockMvc
        .perform(
            put(modelPath(providerName, newModelName))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(staleRename)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"))
        .andExpect(jsonPath("$.errors.resource").doesNotExist());

    // 7. 验证非法名称返回 400 validation
    SingleModelUpdateDTO invalidRename = new SingleModelUpdateDTO();
    invalidRename.setProviderName(providerName);
    invalidRename.setName("   ");
    invalidRename.setModelId(WIRE_MODEL_ID);
    invalidRename.setConfig(model.getConfig());
    invalidRename.setExpectedVersion(1L);
    mockMvc
        .perform(
            put(modelPath(providerName, newModelName))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRename)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.errors.resource").doesNotExist());
  }

  /** Agent 配置中选择未知工具时，校验器在产生处给出的 validation 直接到达 HTTP 边界。 */
  @Test
  void shouldRejectAgentConfigSelectionsAtTheHttpBoundary() throws Exception {
    String agentName = "config-boundary-" + System.nanoTime();
    AgentConfigDTO createConfig = new AgentConfigDTO();
    createConfig.setTools(List.of());
    createConfig.setSkills(List.of());
    createConfig.setSubagents(List.of());
    AgentCreateDTO create = new AgentCreateDTO();
    create.setName(agentName);
    create.setExecution(execution("acceptance-stub"));
    create.setConfig(createConfig);
    mockMvc
        .perform(
            post("/api/platform/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
        .andExpect(status().isCreated());

    AgentConfigDTO config = new AgentConfigDTO();
    config.setTools(List.of("missing_tool"));
    config.setSkills(List.of());
    config.setSubagents(List.of());
    AgentUpdateDTO update = new AgentUpdateDTO();
    update.setExpectedVersion(0L);
    update.setExecution(execution("acceptance-stub"));
    update.setConfig(config);

    mockMvc
        .perform(
            put("/api/platform/agents/{name}", agentName)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("unknown agent tool: missing_tool"));
  }

  /** 组配置的结构错误由共享 codec 在产生处按 validation 拒绝，而不是变成内部错误。 */
  @Test
  void shouldRejectInvalidModelGroupShapeAtTheHttpBoundary() throws Exception {
    ModelGroupMemberDTO member = new ModelGroupMemberDTO();
    member.setModelName("p/m");
    member.setVariant("default");
    ModelGroupConfigDTO group = new ModelGroupConfigDTO();
    group.setMembers(List.of(member));
    group.setFallbackStatusCodes(List.of(429, 429));
    group.setCooldownSeconds(0);
    ModelGroupCreateDTO create = new ModelGroupCreateDTO();
    create.setName("bad-group-" + System.nanoTime());
    create.setGroup(group);

    mockMvc
        .perform(
            post("/api/platform/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(
            jsonPath("$.detail")
                .value("group.fallbackStatusCodes must not contain duplicate statuses"));
  }

  /** Provider 创建只接受 SET 凭据：KEEP/CLEAR 属于更新语义，按 validation 拒绝。 */
  @Test
  void shouldRejectNonSetCredentialOnProviderCreate() throws Exception {
    for (String operation : List.of("KEEP", "CLEAR")) {
      ObjectNode body = objectMapper.createObjectNode();
      body.put("name", "provider-" + operation.toLowerCase() + "-" + System.nanoTime());
      body.put("providerType", "openai");
      body.set("credential", objectMapper.valueToTree(Map.of("operation", operation)));
      mockMvc
          .perform(
              post("/api/platform/providers")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body.toString()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("validation"))
          .andExpect(jsonPath("$.detail").value("credential must use the SET operation on create"));
    }
  }

  private static HarnessExecutionSelectionDTO execution(String modelName) {
    HarnessExecutionSelectionDTO execution = new HarnessExecutionSelectionDTO();
    execution.setDriver("builtin");
    execution.setSettings(jsonObject(Map.of("modelName", modelName, "variant", "default")));
    return execution;
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private static boolean hasResult(JsonNode page, String name) {
    for (JsonNode result : page) {
      if (name.equals(result.path("name").asText())) {
        return true;
      }
    }
    return false;
  }

  private static JsonNode findAgent(JsonNode agents, String name) {
    for (JsonNode agent : agents) {
      if (name.equals(agent.path("name").asText())) {
        return agent;
      }
    }
    throw new AssertionError("missing agent: " + name);
  }

  private static JsonNode findResult(JsonNode page, String name) {
    for (JsonNode result : page) {
      if (name.equals(result.path("name").asText())) {
        return result;
      }
    }
    throw new AssertionError("missing page result: " + name);
  }

  private static URI modelPath(String providerName, String modelName) {
    // provider 不是路径身份的一部分；URI 预编码保留名称中的空格及斜杠。
    return URI.create(
        "/api/platform/models/" + UriUtils.encodePath(modelName, StandardCharsets.UTF_8));
  }

  private static void configureExecutableModel(SingleModelCreateDTO model) {
    // wire 模型标识与逻辑 name 独立：这里刻意取不同值，证明 API 契约同时暴露二者。
    model.setModelId(WIRE_MODEL_ID);
    ModelConfigDTO config = new ModelConfigDTO();
    ModelLimitDTO limit = new ModelLimitDTO();
    limit.setContext(32768);
    limit.setOutput(4096);
    config.setLimit(limit);
    ModelAbilitiesDTO abilities = new ModelAbilitiesDTO();
    abilities.setTools(true);
    abilities.setReasoning(false);
    abilities.setInputModalities(List.of(ModelInputModality.TEXT));
    config.setAbilities(abilities);
    ModelPricingDTO pricing = new ModelPricingDTO();
    ModelRateSetDTO base = new ModelRateSetDTO();
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
    ModelVariantDTO variant = new ModelVariantDTO();
    variant.setId("default");
    variant.setPromptCacheRetention(PromptCacheRetention.SHORT);
    config.setVariants(List.of(variant));
    config.setDefaultVariant("default");
    model.setConfig(config);
  }
}
