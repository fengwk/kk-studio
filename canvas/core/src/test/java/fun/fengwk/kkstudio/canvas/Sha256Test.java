package fun.fengwk.kkstudio.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

class Sha256Test {

  private static final String ABC =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  // 已知向量：UTF-8 文本的摘要是 64 位小写十六进制。
  @Test
  void hexMatchesKnownVector() {
    assertEquals(ABC, Sha256.hex("abc"));
  }

  // 分段更新与一次性摘要等价。
  @Test
  void newDigestSupportsIncrementalUpdates() {
    MessageDigest digest = Sha256.newDigest();
    digest.update((byte) 'a');
    digest.update("bc".getBytes(StandardCharsets.UTF_8));
    assertEquals(ABC, HexFormat.of().formatHex(digest.digest()));
  }

  @Test
  void nullTextIsRejected() {
    assertThrows(NullPointerException.class, () -> Sha256.hex(null));
  }
}
