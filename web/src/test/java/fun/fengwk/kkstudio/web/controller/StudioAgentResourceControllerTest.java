package fun.fengwk.kkstudio.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.model.AgentExecutionPolicyDTO;
import fun.fengwk.kkstudio.share.model.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.model.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelInputModality;
import fun.fengwk.kkstudio.share.model.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.model.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelVariantDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;
import fun.fengwk.kkstudio.web.WebTestApplication;

import java.math.BigDecimal;
import java.util.List;

/** HTTP contract for global Agent resources, string IDs and credential redaction. */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
public class StudioAgentResourceControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  public void shouldUseGlobalAgentResourceRoutes() throws Exception {
    String suffix = Long.toString(System.nanoTime());

    AgentProviderCreateDTO provider = new AgentProviderCreateDTO();
    provider.setName("provider-" + suffix);
    provider.setProviderType("openai");
    provider.setCredential("secret-value");
    provider.setModelCallTimeoutMillis(120_000L);
    provider.setModelCallIdleTimeoutMillis(3_000L);
    String providerId =
        id(
            mockMvc
                .perform(
                    post("/api/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(provider)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.workspaceId").doesNotExist())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.credential").doesNotExist())
                .andExpect(jsonPath("$.data.modelCallTimeoutMillis").value(120000))
                .andExpect(jsonPath("$.data.modelCallIdleTimeoutMillis").value(3000))
                .andReturn()
                .getResponse()
                .getContentAsString());

    AgentModelCreateDTO model = new AgentModelCreateDTO();
    model.setName("model-" + suffix);
    model.setProviderId(providerId);
    configureExecutableModel(model);
    String modelId =
        id(
            mockMvc
                .perform(
                    post("/api/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(model)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.workspaceId").doesNotExist())
                .andExpect(jsonPath("$.data.providerId").value(providerId))
                .andReturn()
                .getResponse()
                .getContentAsString());

    AgentProviderUpdateDTO providerUpdate = new AgentProviderUpdateDTO();
    providerUpdate.setProviderType("openai");
    providerUpdate.setDescription("updated provider");
    mockMvc
        .perform(
            put("/api/providers/{id}", providerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(providerUpdate)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.description").value("updated provider"));

    AgentModelUpdateDTO modelUpdate = new AgentModelUpdateDTO();
    modelUpdate.setDescription("updated model");
    mockMvc
        .perform(
            put("/api/models/{id}", modelId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(modelUpdate)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.description").value("updated model"));

    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setAllowedSubagents(List.of("reviewer"));
    AgentExecutionPolicyDTO policy = new AgentExecutionPolicyDTO();
    policy.setMaxTurns(8);
    config.setExecutionPolicy(policy);
    AgentDefinitionCreateDTO agent = new AgentDefinitionCreateDTO();
    agent.setName("agent-" + suffix);
    agent.setModelId(modelId);
    agent.setConfig(config);
    String agentId =
        id(
            mockMvc
                .perform(
                    post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(agent)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.workspaceId").doesNotExist())
                .andExpect(jsonPath("$.data.modelId").value(modelId))
                .andExpect(jsonPath("$.data.config.tools").isEmpty())
                .andExpect(jsonPath("$.data.config.skills").isEmpty())
                .andExpect(jsonPath("$.data.config.allowedSubagents[0]").value("reviewer"))
                .andExpect(jsonPath("$.data.config.executionPolicy.maxTurns").value(8))
                .andReturn()
                .getResponse()
                .getContentAsString());
    AgentDefinitionUpdateDTO agentUpdate = new AgentDefinitionUpdateDTO();
    agentUpdate.setDescription("updated agent");
    mockMvc
        .perform(
            put("/api/agents/{id}", agentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(agentUpdate)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.description").value("updated agent"));

    AgentProviderCreateDTO disposableProvider = new AgentProviderCreateDTO();
    disposableProvider.setName("disposable-provider-" + suffix);
    disposableProvider.setProviderType("openai");
    String disposableProviderId =
        id(
            mockMvc
                .perform(
                    post("/api/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disposableProvider)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    AgentModelCreateDTO disposableModel = new AgentModelCreateDTO();
    disposableModel.setName("disposable-model-" + suffix);
    disposableModel.setProviderId(disposableProviderId);
    configureExecutableModel(disposableModel);
    String disposableModelId =
        id(
            mockMvc
                .perform(
                    post("/api/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disposableModel)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    mockMvc
        .perform(delete("/api/models/{id}", disposableModelId))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(delete("/api/providers/{id}", disposableProviderId))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/models"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results[0].providerId").isString());
    mockMvc
        .perform(get("/api/providers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results[0].credential").doesNotExist());
    mockMvc
        .perform(get("/api/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results[0].modelId").isString());
    mockMvc.perform(delete("/api/agents/{id}", agentId)).andExpect(status().isNoContent());
    mockMvc.perform(get("/api/workspaces")).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/workspaces/1")).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/workspaces/1/agents")).andExpect(status().isNotFound());
  }

  private String id(String response) throws Exception {
    JsonNode node = objectMapper.readTree(response);
    return node.get("data").get("id").asText();
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
