package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.IOException;
import java.util.Map;

/**
 * Platform MCP Server Web REST API HTTP 契约测试。
 *
 * <p>验证 name-keyed {@code /api/ai/mcp-servers} 下的 create/list/get/config/discover/update/delete 契约、
 * 安全元数据投影（不含 URL 与 headers）、显式 config 读取（强制 no-store）与并发冲突状态码。
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
  public void fullCrudLifecycleAndConfigPrivacy() throws Exception {
    fakeServer.addTool("echo", "Echo text", "{}");
    String name = "web_mcp_" + System.nanoTime();

    McpServerCreateDTO create = new McpServerCreateDTO();
    create.setName(name);
    create.setUrl(fakeServer.endpointUrl());
    create.setHeaders(Map.of("Authorization", "Bearer secret-token-12345"));
    create.setTimeoutMillis(15000L);

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/ai/mcp-servers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(create)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.name").value(name))
            .andExpect(jsonPath("$.data.version").value("0"))
            .andExpect(jsonPath("$.data.discoveryStatus").value("UNVERIFIED"))
            .andReturn();

    JsonNode createData = data(createResult);
    // 确认 JSON 响应完全不含敏感信息
    assertFalse(createData.has("url"));
    assertFalse(createData.has("headers"));
    assertFalse(createData.has("id"));

    // 2. GET /api/ai/mcp-servers/{name} (安全公开视图)
    MvcResult getResult =
        mockMvc
            .perform(get("/api/ai/mcp-servers/{name}", name))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value(name))
            .andReturn();
    assertFalse(data(getResult).has("url"));
    assertFalse(data(getResult).has("headers"));

    // 3. GET /api/ai/mcp-servers/{name}/config (显式配置读取，必须带有 Cache-Control: no-store)
    MvcResult getConfigResult =
        mockMvc
            .perform(get("/api/ai/mcp-servers/{name}/config", name))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.data.name").value(name))
            .andExpect(jsonPath("$.data.url").value(fakeServer.endpointUrl()))
            .andReturn();
    JsonNode configData = data(getConfigResult);
    assertTrue(
        configData.get("headers").get("Authorization").asText().contains("secret-token-12345"));

    // 4. GET /api/ai/mcp-servers (分页)
    mockMvc
        .perform(get("/api/ai/mcp-servers").param("pageNumber", "1").param("pageSize", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results").isArray());

    // 5. POST /api/ai/mcp-servers/{name}/discover (同步发现)
    mockMvc
        .perform(post("/api/ai/mcp-servers/{name}/discover", name).param("expectedVersion", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.discoveryStatus").value("AVAILABLE"))
        .andExpect(jsonPath("$.data.toolCount").value(1));

    // 6. PUT /api/ai/mcp-servers/{name} (更新)
    McpServerUpdateDTO update = new McpServerUpdateDTO();
    update.setExpectedVersion("0");
    update.setUrl(fakeServer.endpointUrl());
    update.setTimeoutMillis(20000L);

    mockMvc
        .perform(
            put("/api/ai/mcp-servers/{name}", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.timeoutMillis").value(20000))
        .andExpect(jsonPath("$.data.version").value("1"))
        .andExpect(jsonPath("$.data.discoveryStatus").value("UNVERIFIED"));

    // 7. DELETE /api/ai/mcp-servers/{name} (删除)
    mockMvc
        .perform(delete("/api/ai/mcp-servers/{name}", name).param("expectedVersion", "1"))
        .andExpect(status().isNoContent());

    // 8. 删除后查询 404
    mockMvc.perform(get("/api/ai/mcp-servers/{name}", name)).andExpect(status().isNotFound());
  }

  @Test
  public void handlesVersionConflictWithConflictStatus() throws Exception {
    fakeServer.addTool("ping", "Ping", "{}");
    String name = "conflict_mcp_" + System.nanoTime();

    McpServerCreateDTO create = new McpServerCreateDTO();
    create.setName(name);
    create.setUrl(fakeServer.endpointUrl());
    create.setTimeoutMillis(5000L);

    mockMvc
        .perform(
            post("/api/ai/mcp-servers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
        .andExpect(status().isCreated());

    McpServerUpdateDTO badVersionUpdate = new McpServerUpdateDTO();
    badVersionUpdate.setExpectedVersion("999");
    badVersionUpdate.setUrl(fakeServer.endpointUrl());

    mockMvc
        .perform(
            put("/api/ai/mcp-servers/{name}", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(badVersionUpdate)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"));

    mockMvc
        .perform(post("/api/ai/mcp-servers/{name}/discover", name).param("expectedVersion", "999"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"));
  }

  @Test
  public void rejectsInvalidUrlWithBadRequestAndRedactsSecrets() throws Exception {
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

  @Test
  public void rejectsUnknownFieldsOnCreateWithBadRequest() throws Exception {
    String name = "rej_mcp_" + System.currentTimeMillis();
    String body =
        """
        {"name":"%s","url":"%s","configJson":"{}"}
        """
            .formatted(name, fakeServer.endpointUrl());

    mockMvc
        .perform(post("/api/ai/mcp-servers").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
  }
}
