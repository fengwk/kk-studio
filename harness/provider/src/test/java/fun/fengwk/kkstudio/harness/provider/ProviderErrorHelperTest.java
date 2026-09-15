package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;

import java.nio.charset.StandardCharsets;

/** 测试意图：验证 ProviderErrorHelper 的 HTTP 错误消息格式化、截断标记与静默错误判断逻辑。 */
class ProviderErrorHelperTest {

  @Test
  @DisplayName("isSilentTransportKind 正确识别静默传输类型")
  void testIsSilentTransportKind() {
    assertTrue(ProviderErrorHelper.isSilentTransportKind(TransportErrorKind.CANCELLED));
    assertTrue(ProviderErrorHelper.isSilentTransportKind(TransportErrorKind.EXECUTOR_REJECTED));
    assertTrue(ProviderErrorHelper.isSilentTransportKind(TransportErrorKind.CALLBACK_FAILED));

    assertFalse(ProviderErrorHelper.isSilentTransportKind(TransportErrorKind.TIMEOUT));
    assertFalse(ProviderErrorHelper.isSilentTransportKind(TransportErrorKind.IO));
    assertFalse(ProviderErrorHelper.isSilentTransportKind(TransportErrorKind.HTTP_STATUS));
    assertFalse(ProviderErrorHelper.isSilentTransportKind(TransportErrorKind.INVALID_RESPONSE));
  }

  @Test
  @DisplayName("formatHttpErrorMessage: 正文缺失时回退为 HTTP 状态码与 fallback")
  void testFormatHttpErrorMessageWithoutBody() {
    String fallback = "Default fallback error";

    // null body with positive status
    assertEquals(
        "HTTP 401: " + fallback,
        ProviderErrorHelper.formatHttpErrorMessage(401, null, false, fallback));

    // empty body with positive status
    assertEquals(
        "HTTP 500: " + fallback,
        ProviderErrorHelper.formatHttpErrorMessage(500, "", false, fallback));

    // null body with zero or negative status
    assertEquals(fallback, ProviderErrorHelper.formatHttpErrorMessage(0, null, false, fallback));
    assertEquals(fallback, ProviderErrorHelper.formatHttpErrorMessage(-1, "", false, fallback));
  }

  @Test
  @DisplayName("formatHttpErrorMessage: 完整保留原始正文及空白、非 JSON 与截断标记")
  void testFormatHttpErrorMessageWithBody() {
    String fallback = "Fallback";

    // normal body without truncation
    String body = "{\"error\":\"test\"}";
    assertEquals(
        "HTTP 400\n" + body,
        ProviderErrorHelper.formatHttpErrorMessage(400, body, false, fallback));

    // normal body with truncation
    assertEquals(
        "HTTP 400\n" + body + ProviderErrorHelper.TRUNCATION_MARKER,
        ProviderErrorHelper.formatHttpErrorMessage(400, body, true, fallback));

    // whitespace body must be preserved verbatim
    String whitespaceBody = "   \n\t  ";
    assertEquals(
        "HTTP 400\n" + whitespaceBody,
        ProviderErrorHelper.formatHttpErrorMessage(400, whitespaceBody, false, fallback));

    // non-JSON body
    String htmlBody = "<html><body>502 Bad Gateway</body></html>";
    assertEquals(
        "HTTP 502\n" + htmlBody,
        ProviderErrorHelper.formatHttpErrorMessage(502, htmlBody, false, fallback));
  }

  @Test
  @DisplayName("formatHttpErrorMessage(TransportException): 委托与边界检查")
  void testFormatHttpErrorMessageFromException() {
    String fallback = "Fallback";

    // null exception returns fallback
    assertEquals(
        fallback, ProviderErrorHelper.formatHttpErrorMessage((TransportException) null, fallback));

    // exception without body
    TransportException exNoBody =
        new TransportException(TransportErrorKind.HTTP_STATUS, "msg", 401, null, null, null);
    assertEquals(
        "HTTP 401: " + fallback, ProviderErrorHelper.formatHttpErrorMessage(exNoBody, fallback));

    // exception with body and truncation
    byte[] bodyBytes = "truncated error response".getBytes(StandardCharsets.UTF_8);
    TransportException exTruncated =
        new TransportException(
            TransportErrorKind.HTTP_STATUS, "msg", 500, bodyBytes, true, null, null);
    assertEquals(
        "HTTP 500\ntruncated error response" + ProviderErrorHelper.TRUNCATION_MARKER,
        ProviderErrorHelper.formatHttpErrorMessage(exTruncated, fallback));
  }
}
