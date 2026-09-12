package fun.fengwk.kkstudio.share.ai.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 验证 EnvironmentOperationCreateDTO 与 EnvironmentOperationDTO 的序列化、严格未知字段拒绝与安全契约。 */
class EnvironmentOperationDtoContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 意图：创建请求只接受 timeoutMillis，未知字段立即被严格拒绝。 */
  @Test
  void createDtoAcceptsTimeoutAndRejectsUnknownFields() throws Exception {
    EnvironmentOperationCreateDTO dto =
        MAPPER.readValue("{\"timeoutMillis\":60000}", EnvironmentOperationCreateDTO.class);
    assertEquals(60000L, dto.getTimeoutMillis());

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"timeoutMillis\":60000,\"extra\":\"unknown\"}",
                EnvironmentOperationCreateDTO.class),
        "未知字段必须被拒绝");
  }

  /** 意图：操作响应 DTO 序列化与反序列化字段完整且拒绝未知字段，toString() 不泄露任何私有 token 或 arguments。 */
  @Test
  void operationDtoSerializationAndSafety() throws Exception {
    String json =
        """
        {
          "id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          "environmentId": "11111111-1111-1111-1111-111111111111",
          "sourceId": "22222222-2222-2222-2222-222222222222",
          "operationType": "SKILL_REFRESH",
          "status": "PENDING",
          "sourceVersion": "1",
          "sourceSetVersion": "2",
          "parameterSummary": {"sourceType": "path"},
          "resultSummary": {"revision": "abc", "skillCount": 1}
        }
        """;

    EnvironmentOperationDTO dto = MAPPER.readValue(json, EnvironmentOperationDTO.class);
    assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", dto.getId());
    assertEquals("path", dto.getParameterSummary().get("sourceType"));
    assertEquals(1, dto.getResultSummary().get("skillCount"));

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
}
