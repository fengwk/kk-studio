package fun.fengwk.kkstudio.platform.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * 预签名 PUT 的头部上限按 UTF-8 字节计。
 *
 * <p>Daemon(Go) 用 {@code len(name)/len(value)} 比较 1024，按字节限制；Java 必须同口径，否则含非 ASCII 的签名头会被单方面拒绝。
 */
class DaemonPresignedPutTest {

  private static final String URL = "https://bucket.example.com/key?signature=abc";

  @Test
  void boundsHeaderNameAndValueByUtf8Bytes() {
    String ascii = "a".repeat(1024);
    assertEquals(ascii, presigned(Map.of(ascii, ascii)).headers().get(ascii));

    // 341 个汉字是 1023 UTF-8 字节：合法；1024 个汉字是 3072 字节：超限。
    String longest = "签".repeat(341);
    assertEquals(longest, presigned(Map.of("x-amz-meta", longest)).headers().get("x-amz-meta"));
    assertThrows(
        IllegalArgumentException.class, () -> presigned(Map.of("x-amz-meta", "签".repeat(342))));
    assertThrows(IllegalArgumentException.class, () -> presigned(Map.of("签".repeat(342), "v")));
  }

  private static DaemonPresignedPut presigned(Map<String, String> headers) {
    return new DaemonPresignedPut("PUT", URL, headers, 1L);
  }
}
