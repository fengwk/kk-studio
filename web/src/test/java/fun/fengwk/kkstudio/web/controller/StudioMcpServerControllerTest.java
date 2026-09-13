package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDiscoverDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.IOException;

/**
 * Platform MCP Server Web REST API HTTP 契约测试。
 *
 * <p>验证 {@code /api/ai/mcp-servers} 下的 create/update/discover/delete/list/get 契约、 安全元数据投影、显式 config
 * 读取（强制 no-store）与发现状态码。
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

    String remoteConfig =
        """
        {
          "type": "remote",
          "url": "%s",
          "headers": {
            "Authorization": "Bearer secret-token-12345"
          },
          "timeoutMillis": 15000
        }
        """
            .formatted(fakeServer.endpointUrl());

    // 1. 创建 Server
    String name = "web_mcp_" + System.nanoTime();
    McpServerCreateDTO create = new McpServerCreateDTO();
    create.setName(name);
    create.setConfigJson(remoteConfig);

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/ai/mcp-servers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(create)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.name").value(name))
            .andExpect(jsonPath("$.data.type").value("remote"))
            .andExpect(jsonPath("$.data.version").value("0"))
            .andExpect(jsonPath("$.data.discoveryStatus").value("UNVERIFIED"))
            .andReturn();

    JsonNode createData = data(createResult);
    String id = createData.get("id").asText();
    assertNotNull(id);
    // 确认 JSON 响应完全不含敏感信息
    assertFalse(createData.has("configJson"));
    assertFalse(createData.has("url"));
    assertFalse(createData.has("headers"));

    // 2. GET /api/ai/mcp-servers/{id} (安全公开视图)
    MvcResult getResult =
        mockMvc
            .perform(get("/api/ai/mcp-servers/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.name").value(name))
            .andReturn();
    JsonNode getData = data(getResult);
    assertFalse(getData.has("configJson"));
    assertFalse(getData.has("url"));

    // 3. GET /api/ai/mcp-servers/{id}/config (显式配置读取，必须带有 Cache-Control: no-store)
    MvcResult getConfigResult =
        mockMvc
            .perform(get("/api/ai/mcp-servers/{id}/config", id))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.name").value(name))
            .andReturn();
    JsonNode getConfigData = data(getConfigResult);
    assertTrue(getConfigData.has("configJson"));
    assertTrue(getConfigData.get("configJson").asText().contains("secret-token-12345"));

    // 4. GET /api/ai/mcp-servers (分页)
    mockMvc
        .perform(get("/api/ai/mcp-servers").param("pageNumber", "1").param("pageSize", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results").isArray());

    // 5. POST /api/ai/mcp-servers/{id}/discover (发现，返回 202 Accepted)
    McpServerDiscoverDTO discover = new McpServerDiscoverDTO();
    discover.setExpectedVersion("0");
    mockMvc
        .perform(
            post("/api/ai/mcp-servers/{id}/discover", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(discover)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.server.discoveryStatus").value("AVAILABLE"))
        .andExpect(jsonPath("$.data.server.toolCount").value(1));

    // 6. PUT /api/ai/mcp-servers/{id} (更新)
    String newConfig =
        """
        {
          "type": "remote",
          "url": "%s",
          "timeoutMillis": 20000
        }
        """
            .formatted(fakeServer.endpointUrl());
    McpServerUpdateDTO update = new McpServerUpdateDTO();
    update.setExpectedVersion("0");
    update.setConfigJson(newConfig);

    mockMvc
        .perform(
            put("/api/ai/mcp-servers/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.timeoutMillis").value(20000))
        .andExpect(jsonPath("$.data.version").value("1"))
        .andExpect(jsonPath("$.data.discoveryStatus").value("UNVERIFIED"));

    // 7. DELETE /api/ai/mcp-servers/{id} (删除)
    mockMvc
        .perform(delete("/api/ai/mcp-servers/{id}", id).param("expectedVersion", "1"))
        .andExpect(status().isNoContent());

    // 8. 删除后查询 404
    mockMvc.perform(get("/api/ai/mcp-servers/{id}", id)).andExpect(status().isNotFound());
  }

  @Test
  public void handlesVersionConflictWithConflictStatus() throws Exception {
    fakeServer.addTool("ping", "Ping", "{}");

    String name = "conflict_mcp_" + System.nanoTime();
    String config =
        """
        {
          "type": "remote",
          "url": "%s",
          "timeoutMillis": 5000
        }
        """
            .formatted(fakeServer.endpointUrl());

    McpServerCreateDTO create = new McpServerCreateDTO();
    create.setName(name);
    create.setConfigJson(config);

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/ai/mcp-servers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(create)))
            .andExpect(status().isCreated())
            .andReturn();

    String id = data(createResult).get("id").asText();

    // 验证 PUT 在 expectedVersion 版本过期时返回 409 Conflict
    McpServerUpdateDTO badVersionUpdate = new McpServerUpdateDTO();
    badVersionUpdate.setExpectedVersion("999");
    badVersionUpdate.setConfigJson(config);

    mockMvc
        .perform(
            put("/api/ai/mcp-servers/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(badVersionUpdate)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"));

    // 验证 POST discover 在 expectedVersion 版本过期时返回 409 Conflict
    McpServerDiscoverDTO badVersionDiscover = new McpServerDiscoverDTO();
    badVersionDiscover.setExpectedVersion("999");

    mockMvc
        .perform(
            post("/api/ai/mcp-servers/{id}/discover", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(badVersionDiscover)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"));
  }

  @Test
  public void rejectsInvalidConfigWithBadRequestAndRedactsSecrets() throws Exception {
    String name = "bad_url_mcp_" + System.nanoTime();
    String config =
        """
        {
          "type": "remote",
          "url": "http://user:secret@example.com/mcp",
          "timeoutMillis": 5000
        }
        """;

    McpServerCreateDTO create = new McpServerCreateDTO();
    create.setName(name);
    create.setConfigJson(config);

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
  public void rejectsUnknownFieldsOnDiscoverWithBadRequest() throws Exception {
    fakeServer.addTool("echo", "Echo text", "{}");

    String name = "rej_mcp_" + System.currentTimeMillis();
    String config =
        """
        {
          "type": "remote",
          "url": "%s",
          "timeoutMillis": 5000
        }
        """
            .formatted(fakeServer.endpointUrl());

    McpServerCreateDTO create = new McpServerCreateDTO();
    create.setName(name);
    create.setConfigJson(config);

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/ai/mcp-servers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(create)))
            .andExpect(status().isCreated())
            .andReturn();

    String id = data(createResult).get("id").asText();

    mockMvc
        .perform(
            post("/api/ai/mcp-servers/{id}/discover", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"0\",\"extraField\":\"forbidden\"}"))
        .andExpect(status().isBadRequest());
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
  }
}
