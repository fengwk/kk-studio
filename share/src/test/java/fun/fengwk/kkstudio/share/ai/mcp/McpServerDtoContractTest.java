package fun.fengwk.kkstudio.share.ai.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

/** MCP Server 相关 Share DTO 的序列化、严格未知字段拒绝与公开安全边界契约测试。 */
class McpServerDtoContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void createDtoCarriesExplicitHttpFields() throws Exception {
    // 意图：验证 McpServerCreateDTO 直接承载 name/url/headers/enabled/timeoutMillis，未知字段严格 fail closed
    String json =
        "{\"name\":\"my_server\",\"url\":\"https://example.com/mcp\","
            + "\"headers\":{\"Authorization\":\"Bearer x\"},\"enabled\":true,\"timeoutMillis\":5000}";
    McpServerCreateDTO dto = MAPPER.readValue(json, McpServerCreateDTO.class);
    assertEquals("my_server", dto.getName());
    assertEquals("https://example.com/mcp", dto.getUrl());
    assertEquals(Map.of("Authorization", "Bearer x"), dto.getHeaders());
    assertEquals(Boolean.TRUE, dto.getEnabled());
    assertEquals(5000L, dto.getTimeoutMillis());

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"name\":\"s\",\"url\":\"https://example.com\",\"configJson\":\"{}\"}",
                McpServerCreateDTO.class));
  }

  @Test
  void updateDtoCarriesExpectedVersionAndExplicitHttpFields() throws Exception {
    // 意图：验证 McpServerUpdateDTO 承载 expectedVersion 与显式 HTTP 字段，名称不可编辑
    String json =
        "{\"expectedVersion\":\"3\",\"url\":\"https://example.com/mcp\",\"enabled\":false}";
    McpServerUpdateDTO dto = MAPPER.readValue(json, McpServerUpdateDTO.class);
    assertEquals("3", dto.getExpectedVersion());
    assertEquals("https://example.com/mcp", dto.getUrl());
    assertEquals(Boolean.FALSE, dto.getEnabled());

    assertThrows(
        NoSuchFieldException.class, () -> McpServerUpdateDTO.class.getDeclaredField("name"));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"expectedVersion\":\"0\",\"url\":\"https://example.com\",\"type\":\"local\"}",
                McpServerUpdateDTO.class));
  }

  @Test
  void serverDtoExposesOnlySafeMetadataAndNoSensitiveFields() {
    // 意图：验证 McpServerDTO 物理上不含 URL、headers、类型、环境绑定或配置字段，名字即身份
    assertThrows(NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("id"));
    assertThrows(NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("url"));
    assertThrows(NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("headers"));
    assertThrows(NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("type"));
    assertThrows(
        NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("environmentId"));
    assertThrows(
        NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("discoveredVersion"));
    assertThrows(
        NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("configJson"));

    McpServerDTO dto = new McpServerDTO();
    dto.setName("safe_server");
    dto.setEnabled(true);
    dto.setTimeoutMillis(30000L);
    dto.setDiscoveryStatus("AVAILABLE");
    dto.setToolCount(5);
    dto.setVersion("2");
    dto.setCreateTime(Instant.now());
    dto.setUpdateTime(Instant.now());

    String str = dto.toString();
    assertTrue(str.contains("safe_server"));
    assertFalse(str.contains("configJson"));
  }

  @Test
  void configDtoCarriesExplicitHttpFieldsAndRedactsHeadersFromToString() throws Exception {
    // 意图：验证显式配置读取返回 name/version/url/headers，且 headers 不进入 toString
    String secret = "Bearer sensitive-config-marker";
    McpServerConfigDTO dto = new McpServerConfigDTO();
    dto.setName("full_server");
    dto.setVersion("1");
    dto.setUrl("https://example.com/mcp");
    dto.setHeaders(Map.of("Authorization", secret));

    String serialized = MAPPER.writeValueAsString(dto);
    McpServerConfigDTO read = MAPPER.readValue(serialized, McpServerConfigDTO.class);
    assertEquals("full_server", read.getName());
    assertEquals("1", read.getVersion());
    assertEquals("https://example.com/mcp", read.getUrl());
    assertEquals(secret, read.getHeaders().get("Authorization"));

    assertFalse(dto.toString().contains("sensitive-config-marker"));
  }
}
