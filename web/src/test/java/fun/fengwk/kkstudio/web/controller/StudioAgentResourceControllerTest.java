package fun.fengwk.kkstudio.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentExecutionPolicyDTO;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceCreateDTO;
import fun.fengwk.kkstudio.web.WebTestApplication;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract for global Provider/Model APIs, string IDs and credential redaction. */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
public class StudioAgentResourceControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  public void shouldUseGlobalProviderAndModelRoutes() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    String workspaceId = createWorkspace("web-workspace-" + suffix);

    AgentProviderCreateDTO provider = new AgentProviderCreateDTO();
    provider.setName("provider-" + suffix);
    provider.setProviderType("openai");
    provider.setCredential("secret-value");
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
                .andReturn()
                .getResponse()
                .getContentAsString());

    AgentModelCreateDTO model = new AgentModelCreateDTO();
    model.setName("model-" + suffix);
    model.setProviderId(providerId);
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
    config.setTools(List.of("browser"));
    config.setSkills(List.of("java"));
    config.setAllowedSubagents(List.of("reviewer"));
    AgentExecutionPolicyDTO policy = new AgentExecutionPolicyDTO();
    policy.setMaxTurns(8);
    config.setExecutionPolicy(policy);
    AgentDefinitionCreateDTO agent = new AgentDefinitionCreateDTO();
    agent.setName("agent-" + suffix);
    agent.setModelId(modelId);
    agent.setConfig(config);
    mockMvc
        .perform(
            post("/api/workspaces/{workspaceId}/agents", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(agent)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").isString())
        .andExpect(jsonPath("$.data.modelId").value(modelId))
        .andExpect(jsonPath("$.data.config.allowedSubagents[0]").value("reviewer"))
        .andExpect(jsonPath("$.data.config.executionPolicy.maxTurns").value(8));

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
        .perform(get("/api/workspaces/{workspaceId}/providers", workspaceId))
        .andExpect(status().isNotFound());
  }

  private String createWorkspace(String name) throws Exception {
    WorkspaceCreateDTO workspace = new WorkspaceCreateDTO();
    workspace.setName(name);
    String response =
        mockMvc
            .perform(
                post("/api/workspaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(workspace)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.id").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return id(response);
  }

  private String id(String response) throws Exception {
    JsonNode node = objectMapper.readTree(response);
    return node.get("data").get("id").asText();
  }
}
