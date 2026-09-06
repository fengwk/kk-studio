package fun.fengwk.kkstudio.share.ai.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** {@link McpServerRefreshDTO} 反序列化契约测试：保持 expectedVersion 解析正常，拒绝未知字段。 */
class McpServerRefreshDTOTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  /** 测试意图：验证携带合法 expectedVersion 的最小 JSON 能正确反序列化。 */
  @Test
  void deserializesValidExpectedVersion() throws Exception {
    String json = "{\"expectedVersion\":\"2\"}";
    McpServerRefreshDTO dto = objectMapper.readValue(json, McpServerRefreshDTO.class);
    assertEquals("2", dto.getExpectedVersion());
  }

  /** 测试意图：验证当请求体携带未知额外字段时，fail-closed 拒绝反序列化并抛出异常。 */
  @Test
  void rejectsUnknownFieldsFailClosed() {
    String jsonWithExtra = "{\"expectedVersion\":\"1\",\"extraField\":\"value\"}";
    JsonMappingException exception =
        assertThrows(
            JsonMappingException.class,
            () -> objectMapper.readValue(jsonWithExtra, McpServerRefreshDTO.class));
    assertTrue(
        exception.getMessage().contains("unknown mcp server refresh field: extraField"),
        () -> "Unexpected message: " + exception.getMessage());
  }
}
