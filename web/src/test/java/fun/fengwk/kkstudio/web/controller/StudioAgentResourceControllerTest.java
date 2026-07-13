package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.hasItem;
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

import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;
import fun.fengwk.kkstudio.web.WebTestApplication;

/**
 * @author fengwk
 */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
public class StudioAgentResourceControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Test
  public void shouldCreateUpdateListAndDeleteProviderModelAndAgent() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    String provider = "provider-" + suffix;
    String model = "model-" + suffix;
    String agentName = "agent-" + suffix;

    AgentProviderCreateDTO providerCreate = new AgentProviderCreateDTO();
    providerCreate.setName(provider); // provider == name
    providerCreate.setDescription("Provider description");
    providerCreate.setProviderType("openai");
    providerCreate.setBaseUrl("http://localhost/" + suffix);
    providerCreate.setApiKey("test-key");
    providerCreate.setTimeoutMillis(60_000L);

    mockMvc
        .perform(
            post("/api/agent/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(providerCreate)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.name").value(provider))
        .andExpect(jsonPath("$.data.providerType").value("openai"));

    AgentProviderUpdateDTO providerUpdate = new AgentProviderUpdateDTO();
    providerUpdate.setDescription("Updated provider description");
    providerUpdate.setProviderType("openai");
    providerUpdate.setBaseUrl("http://localhost/updated-" + suffix);
    providerUpdate.setApiKey("updated-key");
    providerUpdate.setTimeoutMillis(120_000L);

    // PUT path uses {id} now
    String providersJson =
        mockMvc
            .perform(get("/api/agent/providers").param("pageNumber", "1").param("pageSize", "1000"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long providerId = -1L;
    for (JsonNode node : objectMapper.readTree(providersJson).get("data").get("results")) {
      if (node.get("name").asText().equals(provider)) {
        providerId = node.get("id").asLong();
        break;
      }
    }
    mockMvc
        .perform(
            put("/api/agent/providers/{id}", providerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(providerUpdate)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value(provider))
        .andExpect(jsonPath("$.data.description").value("Updated provider description"));

    AgentModelCreateDTO modelCreate = new AgentModelCreateDTO();
    modelCreate.setProvider(provider);
    modelCreate.setName(model);
    modelCreate.setDescription("Model description");
    modelCreate.setDefaultVariant("fast");
    modelCreate.setVariantsJson("[{\"name\":\"fast\",\"temperature\":0.1}]");

    mockMvc
        .perform(
            post("/api/agent/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(modelCreate)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.providerName").value(provider))
        .andExpect(jsonPath("$.data.name").value(model));

    AgentModelUpdateDTO modelUpdate = new AgentModelUpdateDTO();
    modelUpdate.setDescription("Updated model description");
    modelUpdate.setDefaultVariant("fast");
    modelUpdate.setVariantsJson("[{\"name\":\"fast\",\"temperature\":0.2}]");

    String modelsJson =
        mockMvc
            .perform(get("/api/agent/models").param("pageNumber", "1").param("pageSize", "1000"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long modelId = -1L;
    for (JsonNode node : objectMapper.readTree(modelsJson).get("data").get("results")) {
      if (node.get("name").asText().equals(model)) {
        modelId = node.get("id").asLong();
        break;
      }
    }
    mockMvc
        .perform(
            put("/api/agent/models/{id}", modelId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(modelUpdate)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value(model))
        .andExpect(jsonPath("$.data.description").value("Updated model description"));

    AgentDefinitionCreateDTO agentCreate = new AgentDefinitionCreateDTO();
    agentCreate.setName(agentName);
    agentCreate.setDescription("Agent description");
    agentCreate.setSystemPrompt("You are a test agent.");
    agentCreate.setDefaultProvider(provider);
    agentCreate.setDefaultModel(model);
    agentCreate.setDefaultVariant("fast");
    agentCreate.setToolsJson("[]");

    mockMvc
        .perform(
            post("/api/agent/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(agentCreate)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.name").value(agentName))
        .andExpect(jsonPath("$.data.defaultProviderName").value(provider))
        .andExpect(jsonPath("$.data.defaultModelName").value(model));

    AgentDefinitionUpdateDTO agentUpdate = new AgentDefinitionUpdateDTO();
    agentUpdate.setDescription("Updated agent description");
    agentUpdate.setSystemPrompt("You are an updated test agent.");
    agentUpdate.setDefaultProvider(provider);
    agentUpdate.setDefaultModel(model);
    agentUpdate.setDefaultVariant("fast");
    agentUpdate.setToolsJson("[]");

    String agentsJson =
        mockMvc
            .perform(get("/api/agent/agents").param("pageNumber", "1").param("pageSize", "1000"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long agentId = -1L;
    for (JsonNode node : objectMapper.readTree(agentsJson).get("data").get("results")) {
      if (node.get("name").asText().equals(agentName)) {
        agentId = node.get("id").asLong();
        break;
      }
    }
    mockMvc
        .perform(
            put("/api/agent/agents/{id}", agentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(agentUpdate)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value(agentName))
        .andExpect(jsonPath("$.data.description").value("Updated agent description"));

    mockMvc
        .perform(get("/api/agent/providers").param("pageNumber", "1").param("pageSize", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results[*].name", hasItem(provider)));
    mockMvc
        .perform(get("/api/agent/models").param("pageNumber", "1").param("pageSize", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results[*].name", hasItem(model)));
    mockMvc
        .perform(get("/api/agent/agents").param("pageNumber", "1").param("pageSize", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results[*].name", hasItem(agentName)));

    mockMvc.perform(delete("/api/agent/agents/{id}", agentId)).andExpect(status().isNoContent());
    mockMvc.perform(delete("/api/agent/models/{id}", modelId)).andExpect(status().isNoContent());
    mockMvc
        .perform(delete("/api/agent/providers/{id}", providerId))
        .andExpect(status().isNoContent());
  }
}
