package fun.fengwk.kkstudio.platform.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

class ClusterInternalErrorsTest {
  private final ObjectMapper mapper = JsonMapper.builder().build();

  /** 只接受 problem 顶层的机器字段和 detail，限制跨节点详情长度。 */
  @Test
  void readsBoundedProblemFields() {
    String payload =
        "{\"code\":\"validation\",\"reason\":\"IDE_CLOSE_CANCELLED\",\"detail\":\""
            + "x".repeat(501)
            + "\",\"message\":\"legacy\"}";
    var error = ClusterInternalErrors.parse(mapper, payload.getBytes(StandardCharsets.UTF_8));
    assertEquals("validation", error.code());
    assertEquals("IDE_CLOSE_CANCELLED", error.reason());
    assertEquals("x".repeat(500), error.detail());
  }

  /** 畸形或非字符串字段不能被误认为可信问题详情。 */
  @Test
  void malformedProblemsProduceNoFields() {
    var invalid = ClusterInternalErrors.parse(mapper, "secret".getBytes(StandardCharsets.UTF_8));
    assertNull(invalid.code());
    assertNull(invalid.reason());
    assertNull(invalid.detail());
    var wrongTypes =
        ClusterInternalErrors.parse(
            mapper,
            "{\"code\":123,\"reason\":{},\"detail\":\"\"}".getBytes(StandardCharsets.UTF_8));
    assertNull(wrongTypes.code());
    assertNull(wrongTypes.reason());
    assertNull(wrongTypes.detail());
  }
}
