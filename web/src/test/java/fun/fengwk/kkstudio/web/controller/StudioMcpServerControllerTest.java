package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.IOException;

/**
 * Platform MCP Server Web REST API HTTP 契约测试。
 *
 * <p>验证 {@code /api/ai/mcp-servers} 下的 create/update/refresh/delete/list/get 契约、 状态码与响应体中 Bearer
 * Token 的严格脱敏。
 */
@AutoConfigureMockMvc
public class StudioMcpServerControllerTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

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

  @Test
  public void fullCrudLifecycleAndTokenRedaction() throws Exception {
    fakeServer.addTool("echo", "Echo text", "{}");

    // 1. 创建 Server
    String name = "web_mcp_" + System.nanoTime();
    McpServerCreateDTO create = new McpServerCreateDTO();
    create.setName(name);
    create.setUrl(fakeServer.endpointUrl());
    create.setBearerToken("secret-token-12345");
    create.setTimeoutMillis(15000L);

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/ai/mcp-servers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(create)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.name").value(name))
            .andExpect(jsonPath("$.data.bearerTokenConfigured").value(true))
            .andExpect(jsonPath("$.data.version").value("0"))
            .andReturn();

    JsonNode createData = data(createResult);
    String id = createData.get("id").asText();
    assertNotNull(id);
    // 确认 JSON 响应完全不含 bearerToken 属性
    assertFalse(createData.has("bearerToken"));

    // 2. GET /api/ai/mcp-servers/{id}
    MvcResult getResult =
        mockMvc
            .perform(get("/api/ai/mcp-servers/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.name").value(name))
            .andReturn();
    JsonNode getData = data(getResult);
    assertFalse(getData.has("bearerToken"));

    // 3. GET /api/ai/mcp-servers (分页)
    mockMvc
        .perform(get("/api/ai/mcp-servers").param("pageNumber", "1").param("pageSize", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results").isArray());

    // 4. PUT /api/ai/mcp-servers/{id} (更新)
    McpServerUpdateDTO update = new McpServerUpdateDTO();
    update.setTimeoutMillis(20000L);
    update.setExpectedVersion("0");

    mockMvc
        .perform(
            put("/api/ai/mcp-servers/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.timeoutMillis").value(20000))
        .andExpect(jsonPath("$.data.version").value("1"));

    // 5. POST /api/ai/mcp-servers/{id}/refresh (刷新)
    mockMvc
        .perform(post("/api/ai/mcp-servers/{id}/refresh", id).param("expectedVersion", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("2"));

    // 6. DELETE /api/ai/mcp-servers/{id} (删除)
    mockMvc
        .perform(delete("/api/ai/mcp-servers/{id}", id).param("expectedVersion", "2"))
        .andExpect(status().isNoContent());

    // 7. 删除后查询 404
    mockMvc.perform(get("/api/ai/mcp-servers/{id}", id)).andExpect(status().isNotFound());
  }

  @Test
  public void handlesVersionConflictWithConflictStatus() throws Exception {
    fakeServer.addTool("ping", "Ping", "{}");

    String name = "conflict_mcp_" + System.nanoTime();
    McpServerCreateDTO create = new McpServerCreateDTO();
    create.setName(name);
    create.setUrl(fakeServer.endpointUrl());
    create.setTimeoutMillis(5000L);

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/ai/mcp-servers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(create)))
            .andExpect(status().isCreated())
            .andReturn();

    String id = data(createResult).get("id").asText();

    McpServerUpdateDTO badVersionUpdate = new McpServerUpdateDTO();
    badVersionUpdate.setTimeoutMillis(10000L);
    badVersionUpdate.setExpectedVersion("999");

    mockMvc
        .perform(
            put("/api/ai/mcp-servers/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(badVersionUpdate)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"));
  }

  @Test
  public void rejectsInvalidUrlWithBadRequestAndRedactsUrl() throws Exception {
    String name = "bad_url_mcp_" + System.nanoTime();
    McpServerCreateDTO create = new McpServerCreateDTO();
    create.setName(name);
    create.setUrl("http://user:secret@example.com/mcp");
    create.setTimeoutMillis(5000L);

    MvcResult result =
        mockMvc
            .perform(
                post("/api/ai/mcp-servers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(create)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation"))
            .andReturn();

    String responseBody = result.getResponse().getContentAsString();
    assertFalse(responseBody.contains("secret"));
    assertFalse(responseBody.contains("user:secret@example.com"));
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
  }
}
