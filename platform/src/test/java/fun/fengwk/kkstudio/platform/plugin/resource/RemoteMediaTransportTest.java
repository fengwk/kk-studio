package fun.fengwk.kkstudio.platform.plugin.resource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 远端传输接口基础方法单元测试。 */
class RemoteMediaTransportTest {

  /** 验证受限 HTTP 请求头的判定（大小写不敏感）。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "host",
        "Host",
        "HOST",
        "content-length",
        "Content-Length",
        "connection",
        "Connection",
        "expect",
        "Expect",
        "upgrade",
        "Upgrade",
        "via",
        "Via",
        "transfer-encoding",
        "Transfer-Encoding"
      })
  void identifiesRestrictedHeaders(String header) {
    assertTrue(
        RemoteMediaTransport.isRestrictedHeader(header),
        () -> "Header should be restricted: " + header);
  }

  /** 普通签名头与业务头不得被误判为受限头。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "content-type",
        "Content-Type",
        "x-amz-checksum-sha256",
        "authorization",
        "x-custom-token"
      })
  void identifiesNonRestrictedHeaders(String header) {
    assertFalse(
        RemoteMediaTransport.isRestrictedHeader(header),
        () -> "Header should not be restricted: " + header);
  }

  /** null 请求头不得抛异常且返回 false。 */
  @Test
  void handlesNullHeaderNameGracefully() {
    assertFalse(RemoteMediaTransport.isRestrictedHeader(null));
  }
}
