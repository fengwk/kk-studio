package fun.fengwk.kkstudio.share.ai.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 验证 EnvironmentOperationDTO 的序列化、严格未知字段拒绝与安全契约。 */
class EnvironmentOperationDtoContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 意图：操作响应 DTO 序列化与反序列化字段完整且拒绝未知字段，toString() 不泄露任何私有 token 或 arguments。 */
  @Test
  void operationDtoSerializationAndSafety() throws Exception {
    String json =
        """
        {
          "id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          "environmentId": "11111111-1111-1111-1111-111111111111",
          "resourceType": "MCP_SERVER",
          "resourceId": "22222222-2222-2222-2222-222222222222",
          "operationType": "MCP_SERVER_DISCOVER",
          "status": "PENDING",
          "resourceVersion": "1",
          "parameterSummary": {"transport": "stdio"},
          "resultSummary": {"toolCount": 1}
        }
        """;

    EnvironmentOperationDTO dto = MAPPER.readValue(json, EnvironmentOperationDTO.class);
    assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", dto.getId());
    assertEquals("MCP_SERVER", dto.getResourceType());
    assertEquals("22222222-2222-2222-2222-222222222222", dto.getResourceId());
    assertEquals("1", dto.getResourceVersion());
    assertEquals("stdio", dto.getParameterSummary().get("transport"));
    assertEquals(1, dto.getResultSummary().get("toolCount"));

    String str = dto.toString();
    assertFalse(str.contains("arguments"));
    assertFalse(str.contains("leaseToken"));
    assertFalse(str.contains("ownerNodeId"));

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"id\":\"" + dto.getId() + "\",\"leaseToken\":\"secret\"}",
                EnvironmentOperationDTO.class),
        "包含未声明的内部字段必须被拒绝");
  }

  /** 意图：操作响应 DTO 严格拒绝旧的 sourceId、sourceVersion、sourceSetVersion 字段，不保留别名。 */
  @Test
  void operationDtoRejectsLegacySourceFields() {
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"sourceId\":\"22222222-2222-2222-2222-222222222222\"}",
                EnvironmentOperationDTO.class),
        "旧字段 sourceId 必须被拒绝");

    assertThrows(
        Exception.class,
        () -> MAPPER.readValue("{\"sourceVersion\":\"1\"}", EnvironmentOperationDTO.class),
        "旧字段 sourceVersion 必须被拒绝");

    assertThrows(
        Exception.class,
        () -> MAPPER.readValue("{\"sourceSetVersion\":\"2\"}", EnvironmentOperationDTO.class),
        "旧字段 sourceSetVersion 必须被拒绝");
  }
}
