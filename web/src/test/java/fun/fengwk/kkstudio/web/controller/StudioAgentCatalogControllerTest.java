package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.math.BigDecimal;
import java.util.List;

/** Provider、Model、Agent Catalog Controller 的 HTTP 契约。 */
@AutoConfigureMockMvc
public class StudioAgentCatalogControllerTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  public void shouldUseNameBasedIdentitiesAndEnforceCatalogGuards() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    String providerName = "provider-" + suffix;
    String modelName = "model-" + suffix;
    String agentName = "agent-" + suffix;

    AgentProviderCreateDTO provider = new AgentProviderCreateDTO();
    provider.setName(providerName);
    provider.setDescription("provider description");
    provider.setProviderType("openai");
    provider.setCredential("secret-value");
    provider.setModelCallTimeoutMillis(120_000L);
    provider.setModelCallIdleTimeoutMillis(3_000L);
    ObjectNode providerBody = objectMapper.valueToTree(provider);
    providerBody.put("credential", provider.getCredential());
    JsonNode providerData =
        data(
            mockMvc
                .perform(
                    post("/api/ai/catalog/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(providerBody.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(providerName))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.credential").doesNotExist())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.version").value("0"))
                .andReturn());
    assertEquals(providerName, providerData.path("name").asText());
    assertFalse(providerData.has("id"));
    assertFalse(providerData.has("credential"));

    AgentProviderCreateDTO duplicateProvider = new AgentProviderCreateDTO();
    duplicateProvider.setName(providerName);
    duplicateProvider.setProviderType("openai");
    mockMvc
        .perform(
            post("/api/ai/catalog/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(duplicateProvider)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"))
        .andExpect(jsonPath("$.errors.resource").value("agent_provider"));

    AgentModelCreateDTO model = new AgentModelCreateDTO();
    model.setProviderName(providerName);
    model.setName(modelName);
    configureExecutableModel(model);
    JsonNode modelData =
        data(
            mockMvc
                .perform(
                    post("/api/ai/catalog/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(model)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.providerName").value(providerName))
                .andExpect(jsonPath("$.data.name").value(modelName))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.providerId").doesNotExist())
                .andExpect(jsonPath("$.data.modelId").doesNotExist())
                .andExpect(jsonPath("$.data.version").value("0"))
                .andReturn());
    assertEquals(providerName, modelData.path("providerName").asText());
    assertEquals(modelName, modelData.path("name").asText());
    assertFalse(modelData.has("id"));
    assertFalse(modelData.has("providerId"));
    assertFalse(modelData.has("modelId"));

    AgentDefinitionConfigDTO agentConfig = new AgentDefinitionConfigDTO();
    agentConfig.setToolIds(List.of());
    agentConfig.setSkills(List.of());
    agentConfig.setSubagents(List.of());
    AgentDefinitionCreateDTO agent = new AgentDefinitionCreateDTO();
    agent.setName(agentName);
    agent.setModel(providerName + "/" + modelName);
    agent.setVariant("default");
    agent.setConfig(agentConfig);
    JsonNode agentData =
        data(
            mockMvc
                .perform(
                    post("/api/ai/catalog/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(agent)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(agentName))
                .andExpect(jsonPath("$.data.model").value(providerName + "/" + modelName))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.modelId").doesNotExist())
                .andExpect(jsonPath("$.data.providerId").doesNotExist())
                .andExpect(jsonPath("$.data.config.toolIds").isEmpty())
                .andExpect(jsonPath("$.data.config.skills").isEmpty())
                .andExpect(jsonPath("$.data.config.subagents").isEmpty())
                .andExpect(jsonPath("$.data.version").value("0"))
                .andReturn());
    assertEquals(agentName, agentData.path("name").asText());
    assertEquals(providerName + "/" + modelName, agentData.path("model").asText());
    assertFalse(agentData.has("id"));
    assertFalse(agentData.has("modelId"));
    assertFalse(agentData.has("providerId"));

    JsonNode providerPage =
        data(
            mockMvc
                .perform(get("/api/ai/catalog/providers"))
                .andExpect(status().isOk())
                .andReturn());
    JsonNode listedProvider = findResult(providerPage, providerName);
    assertFalse(listedProvider.has("id"));
    assertFalse(listedProvider.has("credential"));

    JsonNode modelPage =
        data(mockMvc.perform(get("/api/ai/catalog/models")).andExpect(status().isOk()).andReturn());
    JsonNode listedModel = findResult(modelPage, modelName);
    assertEquals(providerName, listedModel.path("providerName").asText());
    assertFalse(listedModel.has("id"));
    assertFalse(listedModel.has("providerId"));
    assertFalse(listedModel.has("modelId"));

    JsonNode agentPage =
        data(mockMvc.perform(get("/api/ai/catalog/agents")).andExpect(status().isOk()).andReturn());
    JsonNode listedAgent = findResult(agentPage, agentName);
    assertEquals(providerName + "/" + modelName, listedAgent.path("model").asText());
    assertFalse(listedAgent.has("id"));
    assertFalse(listedAgent.has("modelId"));
    assertFalse(listedAgent.has("providerId"));

    AgentProviderUpdateDTO providerUpdate = new AgentProviderUpdateDTO();
    providerUpdate.setDescription("updated provider");
    providerUpdate.setProviderType("openai");
    providerUpdate.setExpectedVersion("0");
    ObjectNode providerUpdateBody = objectMapper.valueToTree(providerUpdate);
    providerUpdateBody.put("name", "renamed-" + providerName);
    mockMvc
        .perform(
            put("/api/ai/catalog/providers/{name}", providerName)
                .contentType(MediaType.APPLICATION_JSON)
                .content(providerUpdateBody.toString()))
        .andExpect(status().isBadRequest());
    JsonNode updatedProvider =
        data(
            mockMvc
                .perform(
                    put("/api/ai/catalog/providers/{name}", providerName)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(providerUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(providerName))
                .andExpect(jsonPath("$.data.description").value("updated provider"))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.credential").doesNotExist())
                .andExpect(jsonPath("$.data.version").value("1"))
                .andReturn());
    assertEquals(providerName, updatedProvider.path("name").asText());
    assertFalse(updatedProvider.has("id"));
    assertFalse(updatedProvider.has("credential"));

    providerUpdate.setExpectedVersion("0");
    mockMvc
        .perform(
            put("/api/ai/catalog/providers/{name}", providerName)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(providerUpdate)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"))
        .andExpect(jsonPath("$.errors.resource").value("agent_provider"))
        .andExpect(jsonPath("$.errors.expectedVersion").value("0"))
        .andExpect(jsonPath("$.errors.actualVersion").value("1"));

    AgentModelUpdateDTO modelUpdate = new AgentModelUpdateDTO();
    modelUpdate.setDescription("updated model");
    modelUpdate.setConfig(model.getConfig());
    modelUpdate.setExpectedVersion("0");
    ObjectNode modelUpdateBody = objectMapper.valueToTree(modelUpdate);
    modelUpdateBody.put("providerName", "renamed-" + providerName);
    modelUpdateBody.put("name", "renamed-" + modelName);
    mockMvc
        .perform(
            put("/api/ai/catalog/models")
                .param("providerName", providerName)
                .param("modelName", modelName)
                .contentType(MediaType.APPLICATION_JSON)
                .content(modelUpdateBody.toString()))
        .andExpect(status().isBadRequest());
    JsonNode updatedModel =
        data(
            mockMvc
                .perform(
                    put("/api/ai/catalog/models")
                        .param("providerName", providerName)
                        .param("modelName", modelName)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(modelUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerName").value(providerName))
                .andExpect(jsonPath("$.data.name").value(modelName))
                .andExpect(jsonPath("$.data.description").value("updated model"))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.providerId").doesNotExist())
                .andExpect(jsonPath("$.data.modelId").doesNotExist())
                .andExpect(jsonPath("$.data.version").value("1"))
                .andReturn());
    assertEquals(providerName, updatedModel.path("providerName").asText());
    assertEquals(modelName, updatedModel.path("name").asText());

    modelUpdate.setExpectedVersion("0");
    mockMvc
        .perform(
            put("/api/ai/catalog/models")
                .param("providerName", providerName)
                .param("modelName", modelName)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(modelUpdate)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"))
        .andExpect(jsonPath("$.errors.resource").value("agent_model"))
        .andExpect(jsonPath("$.errors.expectedVersion").value("0"))
        .andExpect(jsonPath("$.errors.actualVersion").value("1"));

    AgentDefinitionUpdateDTO agentUpdate = new AgentDefinitionUpdateDTO();
    agentUpdate.setDescription("updated agent");
    agentUpdate.setSystemPrompt("updated prompt");
    agentUpdate.setModel(providerName + "/" + modelName);
    agentUpdate.setVariant("default");
    agentUpdate.setConfig(agentConfig);
    agentUpdate.setExpectedVersion("0");
    ObjectNode agentUpdateBody = objectMapper.valueToTree(agentUpdate);
    agentUpdateBody.put("name", "renamed-" + agentName);
    mockMvc
        .perform(
            put("/api/ai/catalog/agents/{name}", agentName)
                .contentType(MediaType.APPLICATION_JSON)
                .content(agentUpdateBody.toString()))
        .andExpect(status().isBadRequest());
    JsonNode updatedAgent =
        data(
            mockMvc
                .perform(
                    put("/api/ai/catalog/agents/{name}", agentName)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(agentUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(agentName))
                .andExpect(jsonPath("$.data.model").value(providerName + "/" + modelName))
                .andExpect(jsonPath("$.data.description").value("updated agent"))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.modelId").doesNotExist())
                .andExpect(jsonPath("$.data.providerId").doesNotExist())
                .andExpect(jsonPath("$.data.version").value("1"))
                .andReturn());
    assertEquals(agentName, updatedAgent.path("name").asText());
    assertEquals(providerName + "/" + modelName, updatedAgent.path("model").asText());

    agentUpdate.setExpectedVersion("0");
    mockMvc
        .perform(
            put("/api/ai/catalog/agents/{name}", agentName)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(agentUpdate)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"))
        .andExpect(jsonPath("$.errors.resource").value("agent_definition"))
        .andExpect(jsonPath("$.errors.expectedVersion").value("0"))
        .andExpect(jsonPath("$.errors.actualVersion").value("1"));

    mockMvc
        .perform(
            delete("/api/ai/catalog/providers/{name}", providerName).param("expectedVersion", "1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("in_use"))
        .andExpect(jsonPath("$.errors.resource").value("agent_provider"));
    mockMvc
        .perform(
            delete("/api/ai/catalog/models")
                .param("providerName", providerName)
                .param("modelName", modelName)
                .param("expectedVersion", "1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("in_use"))
        .andExpect(jsonPath("$.errors.resource").value("agent_model"));

    mockMvc
        .perform(delete("/api/ai/catalog/agents/{name}", agentName).param("expectedVersion", "1"))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            delete("/api/ai/catalog/models")
                .param("providerName", providerName)
                .param("modelName", modelName)
                .param("expectedVersion", "1"))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            delete("/api/ai/catalog/providers/{name}", providerName).param("expectedVersion", "1"))
        .andExpect(status().isNoContent());
  }

  @Test
  public void shouldRejectMalformedModelRefsAndDatabaseIdContracts() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setToolIds(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());

    AgentDefinitionCreateDTO malformed = new AgentDefinitionCreateDTO();
    malformed.setName("malformed-agent-" + suffix);
    malformed.setModel("provider-without-model");
    malformed.setVariant("default");
    malformed.setConfig(config);
    mockMvc
        .perform(
            post("/api/ai/catalog/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(malformed)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.errors.resource").value("agent_definition"));

    AgentModelCreateDTO idBasedModel = new AgentModelCreateDTO();
    idBasedModel.setName("id-based-model-" + suffix);
    configureExecutableModel(idBasedModel);
    ObjectNode idBasedModelBody = objectMapper.valueToTree(idBasedModel);
    idBasedModelBody.remove("providerName");
    idBasedModelBody.put("providerId", "1");
    mockMvc
        .perform(
            post("/api/ai/catalog/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(idBasedModelBody.toString()))
        .andExpect(status().isBadRequest());

    AgentDefinitionCreateDTO idBasedAgent = new AgentDefinitionCreateDTO();
    idBasedAgent.setName("id-based-agent-" + suffix);
    idBasedAgent.setVariant("default");
    idBasedAgent.setConfig(config);
    ObjectNode idBasedAgentBody = objectMapper.valueToTree(idBasedAgent);
    idBasedAgentBody.remove("model");
    idBasedAgentBody.put("modelId", "1");
    mockMvc
        .perform(
            post("/api/ai/catalog/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(idBasedAgentBody.toString()))
        .andExpect(status().isBadRequest());

    ObjectNode idBasedProviderBody = objectMapper.createObjectNode();
    idBasedProviderBody.put("id", "1");
    idBasedProviderBody.put("providerType", "openai");
    mockMvc
        .perform(
            post("/api/ai/catalog/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(idBasedProviderBody.toString()))
        .andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/ai/catalog/providers/1")).andExpect(status().isMethodNotAllowed());
    mockMvc.perform(get("/api/ai/catalog/models/1")).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/ai/catalog/agents/1")).andExpect(status().isMethodNotAllowed());
    mockMvc
        .perform(
            put("/api/ai/catalog/models/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(delete("/api/ai/catalog/models/1").param("expectedVersion", "0"))
        .andExpect(status().isNotFound());
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private static JsonNode findResult(JsonNode page, String name) {
    for (JsonNode result : page.path("results")) {
      if (name.equals(result.path("name").asText())) {
        return result;
      }
    }
    throw new AssertionError("missing page result: " + name);
  }

  private static void configureExecutableModel(AgentModelCreateDTO model) {
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
