package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.platform.catalog.dto.McpServerDiscoverDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.IOException;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * Platform MCP Server HTTP 契约测试。
 *
 * <p>验证 {@code /api/platform/mcp-servers} 下的 create/list/get/discover/update/delete
 * 契约：列表只暴露安全元数据，详情返回 URL 而 headers 只以 configured 事实出现，秘密更新三态生效，并发冲突映射到稳定状态码。
 */
@AutoConfigureMockMvc
public class McpServerControllerTest extends WebPostgresTestSupport {

  private static final String BASE = "/api/platform/mcp-servers";

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
  public void fullCrudLifecycleKeepsHeadersSecret() throws Exception {
    fakeServer.addTool("echo", "Echo text", "{}");
    String name = "web_mcp_" + System.nanoTime();

    ObjectNode create = createBody(name, fakeServer.endpointUrl(), 15000L);
    setHeaders(create, Map.of("Authorization", "Bearer secret-token-12345"));

    MvcResult createResult =
        mockMvc
            .perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(create.toString()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value(name))
            .andExpect(jsonPath("$.url").value(fakeServer.endpointUrl()))
            .andExpect(jsonPath("$.version").value("0"))
            .andExpect(jsonPath("$.discoveryStatus").value("UNVERIFIED"))
            .andExpect(jsonPath("$.headers.configured").value(true))
            .andReturn();

    JsonNode created = data(createResult);
    // 响应只暴露 URL 与 headers 已配置事实，绝不回显 header 值
    assertFalse(created.toString().contains("secret-token-12345"));
    assertFalse(created.has("headersValue"));

    // 详情同样是唯一携带 URL 的投影
    mockMvc
        .perform(get(BASE + "/{name}", name))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value(name))
        .andExpect(jsonPath("$.url").value(fakeServer.endpointUrl()))
        .andExpect(jsonPath("$.headers.configured").value(true));

    // 列表是摘要数组：不含 URL 与 headers
    MvcResult listResult =
        mockMvc
            .perform(get(BASE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andReturn();
    JsonNode listed =
        StreamSupport.stream(data(listResult).spliterator(), false)
            .filter(node -> name.equals(node.path("name").asText()))
            .findFirst()
            .orElseThrow();
    assertFalse(listed.has("url"));
    assertFalse(listed.has("headers"));
    assertEquals("0", listed.path("version").asText());

    // 同步发现：CAS 坐标在请求体
    McpServerDiscoverDTO discover = new McpServerDiscoverDTO();
    discover.setExpectedVersion("0");
    mockMvc
        .perform(
            post(BASE + "/{name}/discover", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(discover)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.discoveryStatus").value("AVAILABLE"))
        .andExpect(jsonPath("$.toolCount").value(1))
        .andExpect(jsonPath("$.headers.configured").value(true));

    // 更新：KEEP 保留 headers，URL、enabled 与超时整体替换，状态重置为 UNVERIFIED
    ObjectNode update = objectMapper.createObjectNode();
    update.put("expectedVersion", "0");
    update.put("url", fakeServer.endpointUrl());
    update.putObject("headers").put("operation", "KEEP");
    update.put("enabled", true);
    update.put("timeoutMillis", 20000L);

    mockMvc
        .perform(
            put(BASE + "/{name}", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(update.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timeoutMillis").value(20000))
        .andExpect(jsonPath("$.version").value("1"))
        .andExpect(jsonPath("$.discoveryStatus").value("UNVERIFIED"))
        .andExpect(jsonPath("$.headers.configured").value(true));

    // 删除：expectedVersion 在 query
    mockMvc
        .perform(delete(BASE + "/{name}", name).param("expectedVersion", "1"))
        .andExpect(status().isNoContent());

    mockMvc.perform(get(BASE + "/{name}", name)).andExpect(status().isNotFound());
  }

  @Test
  public void clearsHeadersWithClearOperation() throws Exception {
    String name = "clear_mcp_" + System.nanoTime();
    fakeServer.addTool("ping", "Ping", "{}");
    ObjectNode create = createBody(name, fakeServer.endpointUrl(), 5000L);
    setHeaders(create, Map.of("X-Api-Key", "secret"));
    mockMvc
        .perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(create.toString()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.headers.configured").value(true));

    ObjectNode clear = objectMapper.createObjectNode();
    clear.put("expectedVersion", "0");
    clear.put("url", fakeServer.endpointUrl());
    clear.putObject("headers").put("operation", "CLEAR");
    clear.put("enabled", true);
    clear.put("timeoutMillis", 5000L);
    mockMvc
        .perform(
            put(BASE + "/{name}", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(clear.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headers.configured").value(false));
  }

  @Test
  public void handlesVersionConflictWithConflictStatus() throws Exception {
    fakeServer.addTool("ping", "Ping", "{}");
    String name = "conflict_mcp_" + System.nanoTime();

    ObjectNode create = createBody(name, fakeServer.endpointUrl(), 5000L);

    mockMvc
        .perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(create.toString()))
        .andExpect(status().isCreated());

    ObjectNode badVersionUpdate = objectMapper.createObjectNode();
    badVersionUpdate.put("expectedVersion", "999");
    badVersionUpdate.put("url", fakeServer.endpointUrl());
    badVersionUpdate.putObject("headers").put("operation", "KEEP");
    badVersionUpdate.put("enabled", true);
    badVersionUpdate.put("timeoutMillis", 5000L);

    mockMvc
        .perform(
            put(BASE + "/{name}", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(badVersionUpdate.toString()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"));

    McpServerDiscoverDTO staleDiscover = new McpServerDiscoverDTO();
    staleDiscover.setExpectedVersion("999");
    mockMvc
        .perform(
            post(BASE + "/{name}/discover", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(staleDiscover)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"));
  }

  @Test
  public void rejectsInvalidUrlWithBadRequestAndRedactsSecrets() throws Exception {
    String name = "bad_url_mcp_" + System.nanoTime();
    ObjectNode create = createBody(name, "http://user:secret@example.com/mcp", 5000L);

    MvcResult result =
        mockMvc
            .perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(create.toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation"))
            .andReturn();

    String responseBody = result.getResponse().getContentAsString();
    assertFalse(responseBody.contains("secret"));
    assertFalse(responseBody.contains("user:secret@example.com"));
  }

  @Test
  public void requiresCompleteUpdateBodyAndSetOnlyCreateSecrets() throws Exception {
    String name = "strict_mcp_" + System.nanoTime();
    mockMvc
        .perform(
            post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(name, fakeServer.endpointUrl(), 5000L).toString()))
        .andExpect(status().isCreated());

    // 创建时 headers 缺省即未配置：KEEP 没有可保留的旧值，只接受 SET
    String keepOnCreate =
        """
        {"name":"%s","url":"%s","headers":{"operation":"KEEP"}}
        """
            .formatted(name + "_keep", fakeServer.endpointUrl());
    mockMvc
        .perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(keepOnCreate))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));

    // 更新是整体替换：headers、enabled 与 timeoutMillis 缺一即 400
    ObjectNode missingEnabled = objectMapper.createObjectNode();
    missingEnabled.put("expectedVersion", "0");
    missingEnabled.put("url", fakeServer.endpointUrl());
    missingEnabled.putObject("headers").put("operation", "KEEP");
    missingEnabled.put("timeoutMillis", 5000L);
    mockMvc
        .perform(
            put(BASE + "/{name}", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(missingEnabled.toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));

    ObjectNode missingHeaders = objectMapper.createObjectNode();
    missingHeaders.put("expectedVersion", "0");
    missingHeaders.put("url", fakeServer.endpointUrl());
    missingHeaders.put("enabled", true);
    missingHeaders.put("timeoutMillis", 5000L);
    mockMvc
        .perform(
            put(BASE + "/{name}", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(missingHeaders.toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
  }

  @Test
  public void rejectsUnknownFieldsAndPlainHeaderMapsWithBadRequest() throws Exception {
    String name = "rej_mcp_" + System.currentTimeMillis();
    String unknownField =
        """
        {"name":"%s","url":"%s","configJson":"{}"}
        """
            .formatted(name, fakeServer.endpointUrl());
    mockMvc
        .perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(unknownField))
        .andExpect(status().isBadRequest());

    // headers 只能用 SecretUpdate 表达，不能再用可回读的明文映射
    String plainHeaders =
        """
        {"name":"%s","url":"%s","headers":{"Authorization":"Bearer x"}}
        """
            .formatted(name, fakeServer.endpointUrl());
    mockMvc
        .perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(plainHeaders))
        .andExpect(status().isBadRequest());

    // SET 值必须是 header 映射的 JSON 对象文本
    String invalidSecretValue =
        """
        {"name":"%s","url":"%s","headers":{"operation":"SET","value":"not-json"}}
        """
            .formatted(name, fakeServer.endpointUrl());
    mockMvc
        .perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(invalidSecretValue))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
  }

  /** 请求体用 JSON 数字表达 Long：生成契约的请求侧 Long 是数值，服务端拒绝标量强转。 */
  /** 远端工具名无法规范化时 discovery 以 validation 拒绝，原因来自规范化器的产生处校验。 */
  @Test
  public void discoverRejectsUnnormalizableRemoteToolName() throws Exception {
    fakeServer.addTool("!!!", "bad name", "{}");
    String name = "web_mcp_bad_" + System.nanoTime();
    mockMvc
        .perform(
            post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(name, fakeServer.endpointUrl(), 15000L).toString()))
        .andExpect(status().isCreated());

    McpServerDiscoverDTO discover = new McpServerDiscoverDTO();
    discover.setExpectedVersion("0");
    mockMvc
        .perform(
            post(BASE + "/{name}/discover", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(discover)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(
            jsonPath("$.detail").value("source tool name normalizes to an empty identifier: !!!"));
  }

  private ObjectNode createBody(String name, String url, long timeoutMillis) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("name", name);
    body.put("url", url);
    body.put("timeoutMillis", timeoutMillis);
    return body;
  }

  /** headers 以一个 JSON 对象文本作为 SET 的秘密值提交。 */
  private void setHeaders(ObjectNode body, Map<String, String> headers) throws Exception {
    ObjectNode secret = body.putObject("headers");
    secret.put("operation", "SET");
    secret.put("value", objectMapper.writeValueAsString(headers));
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }
}
