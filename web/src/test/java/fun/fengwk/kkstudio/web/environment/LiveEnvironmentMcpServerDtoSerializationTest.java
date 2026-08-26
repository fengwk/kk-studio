package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentDTO;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentMcpServerDTO;

import java.time.Instant;
import java.util.List;

/**
 * MCP server 摘要 DTO 的序列化契约：{@code error} 是 required-nullable 字段——即使全局配置 NON_NULL，READY server 的
 * {@code error} 也必须显式序列化为 {@code null}，前端契约才不会出现字段缺失。
 */
class LiveEnvironmentMcpServerDtoSerializationTest {

  private static final ObjectMapper STRICT_NON_NULL_MAPPER =
      new ObjectMapper()
          .findAndRegisterModules()
          .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  @Test
  void readyServerSerializesExplicitNullErrorEvenUnderGlobalNonNull() throws Exception {
    LiveEnvironmentMcpServerDTO dto = new LiveEnvironmentMcpServerDTO();
    dto.setName("fs");
    dto.setStatus("READY");
    dto.setError(null);
    dto.setTools(List.of());

    JsonNode json = STRICT_NON_NULL_MAPPER.readTree(STRICT_NON_NULL_MAPPER.writeValueAsString(dto));
    assertEquals("fs", json.path("name").asText());
    assertEquals("READY", json.path("status").asText());
    assertTrue(json.has("error"), "error must be present even when null");
    assertTrue(json.path("error").isNull(), "error must be explicit null for READY servers");
    assertTrue(json.path("tools").isArray());
  }

  @Test
  void failedServerSerializesBoundedErrorText() throws Exception {
    LiveEnvironmentMcpServerDTO dto = new LiveEnvironmentMcpServerDTO();
    dto.setName("broken");
    dto.setStatus("FAILED");
    dto.setError("cannot connect");
    dto.setTools(List.of());

    JsonNode json = STRICT_NON_NULL_MAPPER.readTree(STRICT_NON_NULL_MAPPER.writeValueAsString(dto));
    assertEquals("cannot connect", json.path("error").asText());
    assertTrue(json.path("error").isTextual());
  }

  /**
   * 公共 Environment 查询 DTO 只投影
   * name/status/ready/lastSeen/capabilities/skills/mcpServers/rootPath，不泄漏其它 READY metadata。
   */
  @Test
  void publicEnvironmentDtoDoesNotExposeInternalMetadata() throws Exception {
    LiveEnvironmentDTO dto = new LiveEnvironmentDTO();
    dto.setName("local");
    dto.setStatus("READY");
    dto.setReady(true);
    dto.setLastSeen(Instant.parse("2026-08-10T00:00:00Z"));
    dto.setCapabilities(List.of());
    dto.setSkills(List.of());
    dto.setMcpServers(List.of());
    dto.setRootPath("/home/dev");

    JsonNode json = STRICT_NON_NULL_MAPPER.readTree(STRICT_NON_NULL_MAPPER.writeValueAsString(dto));

    assertTrue(json.path("name").isTextual());
    assertTrue(json.path("capabilities").isArray());
    assertTrue(json.path("skills").isArray());
    assertTrue(json.path("mcpServers").isArray());
    assertEquals("/home/dev", json.path("rootPath").asText());
    assertTrue(json.path("operatingSystem").isMissingNode());
    assertTrue(json.path("workingDirectory").isMissingNode());
    assertTrue(json.path("timeZone").isMissingNode());
    assertTrue(json.path("note").isMissingNode());
  }
}
