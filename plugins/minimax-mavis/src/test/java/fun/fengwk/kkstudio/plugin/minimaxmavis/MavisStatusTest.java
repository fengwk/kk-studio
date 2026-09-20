package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/** 响应状态分层校验：私有端点三层、MCP 端点两层，401/1004 是认证拒绝。 */
class MavisStatusTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode json(String payload) {
    try {
      return MAPPER.readTree(payload);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  /** 缺失、null、数值 0 与字符串 "0" 在所有层都表示成功。 */
  @Test
  void treatsAbsentAndZeroCodesAsSuccess() {
    assertTrue(MavisStatus.privateStatus(json("{}")).isEmpty());
    assertTrue(MavisStatus.privateStatus(json("{\"code\":0}")).isEmpty());
    assertTrue(
        MavisStatus.privateStatus(json("{\"code\":\"0\",\"base_resp\":{\"status_code\":0}}"))
            .isEmpty());
    assertTrue(
        MavisStatus.privateStatus(json("{\"statusInfo\":{\"code\":null},\"code\":0}")).isEmpty());
    assertTrue(
        MavisStatus.businessStatus(json("{\"base_resp\":{\"status_code\":\"0\"}}")).isEmpty());
  }

  /** 私有端点按 statusInfo → base_resp → code 的观测顺序取第一个失败状态。 */
  @Test
  void privateStatusFollowsObservedLayerOrder() {
    assertEquals(
        "401",
        MavisStatus.privateStatus(
                json("{\"statusInfo\":{\"code\":401},\"base_resp\":{\"status_code\":1004}}"))
            .orElseThrow()
            .asText());
    assertEquals(
        "1004",
        MavisStatus.privateStatus(json("{\"base_resp\":{\"status_code\":1004},\"code\":500}"))
            .orElseThrow()
            .asText());
    assertEquals("500", MavisStatus.privateStatus(json("{\"code\":500}")).orElseThrow().asText());
  }

  /** MCP 端点只看 base_resp 与顶层 code，不解释桌面 statusInfo 层。 */
  @Test
  void businessStatusIgnoresPrivateStatusInfo() {
    assertTrue(MavisStatus.businessStatus(json("{\"statusInfo\":{\"code\":500}}")).isEmpty());
    assertEquals(
        "1004",
        MavisStatus.businessStatus(json("{\"base_resp\":{\"status_code\":\"1004\"}}"))
            .orElseThrow()
            .asText());
  }

  /** 401/1004 映射为认证错误，其他状态映射为携带状态码与去敏消息的业务错误。 */
  @Test
  void mapsFailedCodesToTypedErrors() {
    assertInstanceOf(MavisAuthException.class, MavisStatus.raise(json("401"), "ctx", "detail"));
    assertInstanceOf(
        MavisAuthException.class, MavisStatus.raise(json("\"1004\""), "ctx", "detail"));

    MavisException insufficientCredits =
        MavisStatus.raise(json("402"), "web_search", "quota exhausted");
    MavisBusinessException business =
        assertInstanceOf(MavisBusinessException.class, insufficientCredits);
    assertEquals("402", business.code());
    assertEquals("web_search", business.context());
    assertEquals("quota exhausted", business.serverMessage());
    assertTrue(business.getMessage().contains("Do not retry until credits are available."));
  }

  /** 非标量状态码退化为固定文本，避免把未知服务端结构带进错误消息。 */
  @Test
  void rendersNonScalarCodesSafely() {
    JsonNode container = json("{\"nested\":true}");
    assertFalse(MavisStatus.isAuthCode(container));
    assertEquals("unexpected-status", MavisStatus.render(container));
    assertEquals("", MavisStatus.render(json("null")));
  }
}
