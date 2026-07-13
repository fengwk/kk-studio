package fun.fengwk.kkstudio.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import fun.fengwk.kkstudio.share.model.AgentExecutionPolicyDTO;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceCreateDTO;
import fun.fengwk.kkstudio.web.WebTestApplication;

import java.util.List;

/** HTTP contract test for workspace routing, string IDs, and credential redaction. */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
public class StudioAgentResourceControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  public void shouldUseWorkspaceRoutesAndNeverReturnCredential() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    String workspaceId = createWorkspace("web-workspace-" + suffix);
    mockMvc
        .perform(get("/api/workspaces/{workspaceId}", workspaceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(workspaceId));

    AgentProviderCreateDTO provider = new AgentProviderCreateDTO();
    provider.setName("provider-" + suffix);
    provider.setProviderType("openai");
    provider.setCredential("secret-value");
    String providerId =
        id(
            mockMvc
                .perform(
                    post("/api/workspaces/{workspaceId}/providers", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(provider)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isString())
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
                    post("/api/workspaces/{workspaceId}/models", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(model)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.providerId").value(providerId))
                .andReturn()
                .getResponse()
                .getContentAsString());

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

    mockMvc
        .perform(get("/api/agent/providers"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/workspaces/{workspaceId}/providers", workspaceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results[0].credential").doesNotExist());
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
