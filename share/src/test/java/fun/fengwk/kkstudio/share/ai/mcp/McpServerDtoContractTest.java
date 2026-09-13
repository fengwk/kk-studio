package fun.fengwk.kkstudio.share.ai.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationDTO;

import java.time.Instant;

/** MCP Server 相关 Share DTO 的序列化、反序列化、严格未知字段拒绝与公开安全边界契约测试。 */
class McpServerDtoContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void createDtoSerializationAndUnknownFieldRejection() throws Exception {
    // 意图：验证 McpServerCreateDTO 仅接收 name 与 configJson，未知字段严格 fail closed
    String json = "{\"name\":\"my_server\",\"configJson\":\"{\\\"type\\\":\\\"remote\\\"}\"}";
    McpServerCreateDTO dto = MAPPER.readValue(json, McpServerCreateDTO.class);
    assertEquals("my_server", dto.getName());
    assertEquals("{\"type\":\"remote\"}", dto.getConfigJson());

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"name\":\"s\",\"configJson\":\"{}\",\"url\":\"http://leak.com\"}",
                McpServerCreateDTO.class));
  }

  @Test
  void updateDtoSerializationAndUnknownFieldRejection() throws Exception {
    // 意图：验证 McpServerUpdateDTO 仅接收 expectedVersion 与 configJson，未知字段严格 fail closed
    String json = "{\"expectedVersion\":\"3\",\"configJson\":\"{\\\"type\\\":\\\"local\\\"}\"}";
    McpServerUpdateDTO dto = MAPPER.readValue(json, McpServerUpdateDTO.class);
    assertEquals("3", dto.getExpectedVersion());
    assertEquals("{\"type\":\"local\"}", dto.getConfigJson());

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"expectedVersion\":\"0\",\"configJson\":\"{}\",\"timeoutMillis\":1000}",
                McpServerUpdateDTO.class));
  }

  @Test
  void discoverDtoSerializationAndUnknownFieldRejection() throws Exception {
    // 意图：验证 McpServerDiscoverDTO 仅接收 expectedVersion，未知字段严格 fail closed
    String json = "{\"expectedVersion\":\"5\"}";
    McpServerDiscoverDTO dto = MAPPER.readValue(json, McpServerDiscoverDTO.class);
    assertEquals("5", dto.getExpectedVersion());

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"expectedVersion\":\"5\",\"extra\":\"val\"}", McpServerDiscoverDTO.class));
  }

  @Test
  void serverDtoExposesOnlySafeMetadataAndNoSensitiveFields() {
    // 意图：验证 McpServerDTO 物理上不含 URL、headers、env、command、cwd、config 或 bearer token 字段
    assertThrows(NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("url"));
    assertThrows(NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("headers"));
    assertThrows(
        NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("bearerToken"));
    assertThrows(
        NoSuchFieldException.class,
        () -> McpServerDTO.class.getDeclaredField("bearerTokenConfigured"));
    assertThrows(NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("env"));
    assertThrows(NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("command"));
    assertThrows(NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("cwd"));
    assertThrows(
        NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("configJson"));
    assertThrows(
        NoSuchFieldException.class, () -> McpServerDTO.class.getDeclaredField("connectionConfig"));

    McpServerDTO dto = new McpServerDTO();
    dto.setId("00000000-0000-4000-8000-000000000001");
    dto.setName("safe_server");
    dto.setType("remote");
    dto.setEnvironmentId(null);
    dto.setEnabled(true);
    dto.setTimeoutMillis(30000L);
    dto.setDiscoveryStatus("AVAILABLE");
    dto.setDiscoveredVersion("2");
    dto.setToolCount(5);
    dto.setVersion("2");
    dto.setCreateTime(Instant.now());
    dto.setUpdateTime(Instant.now());

    String str = dto.toString();
    assertTrue(str.contains("safe_server"));
    assertFalse(str.contains("bearerToken"));
    assertFalse(str.contains("configJson"));
  }

  @Test
  void configDtoSerialization() throws Exception {
    // 意图：验证 McpServerConfigDTO 承载身份、版本与完整配置 JSON
    McpServerConfigDTO dto = new McpServerConfigDTO();
    dto.setId("00000000-0000-4000-8000-000000000001");
    dto.setName("full_server");
    dto.setVersion("1");
    dto.setConfigJson("{\"type\":\"remote\",\"url\":\"https://example.com/mcp\"}");

    String serialized = MAPPER.writeValueAsString(dto);
    McpServerConfigDTO read = MAPPER.readValue(serialized, McpServerConfigDTO.class);
    assertEquals("full_server", read.getName());
    assertEquals("1", read.getVersion());
    assertEquals("{\"type\":\"remote\",\"url\":\"https://example.com/mcp\"}", read.getConfigJson());
  }

  @Test
  void configurationDtosRedactConfigFromToString() {
    // 意图：请求/显式配置响应被日志在日志或断言失败中格式化时，不回显连接秘密
    String secret = "sensitive-config-marker";
    McpServerCreateDTO create = new McpServerCreateDTO();
    create.setName("safe");
    create.setConfigJson(secret);
    McpServerUpdateDTO update = new McpServerUpdateDTO();
    update.setExpectedVersion("1");
    update.setConfigJson(secret);
    McpServerConfigDTO config = new McpServerConfigDTO();
    config.setId("00000000-0000-4000-8000-000000000001");
    config.setConfigJson(secret);

    assertFalse(create.toString().contains(secret));
    assertFalse(update.toString().contains(secret));
    assertFalse(config.toString().contains(secret));
  }

  @Test
  void discoveryResponseDtoSerialization() throws Exception {
    // 意图：验证 McpServerDiscoveryResponseDTO 支持安全 server 与可选 operation 组合
    McpServerDTO serverDto = new McpServerDTO();
    serverDto.setId("00000000-0000-4000-8000-000000000001");
    serverDto.setName("srv");
    serverDto.setType("local");

    EnvironmentOperationDTO opDto = new EnvironmentOperationDTO();
    opDto.setId("00000000-0000-4000-8000-000000000002");
    opDto.setStatus("PENDING");

    McpServerDiscoveryResponseDTO response = new McpServerDiscoveryResponseDTO(serverDto, opDto);
    String serialized = MAPPER.writeValueAsString(response);
    McpServerDiscoveryResponseDTO read =
        MAPPER.readValue(serialized, McpServerDiscoveryResponseDTO.class);
    assertNotNull(read.getServer());
    assertNotNull(read.getOperation());
    assertEquals("srv", read.getServer().getName());
    assertEquals("PENDING", read.getOperation().getStatus());
  }
}
