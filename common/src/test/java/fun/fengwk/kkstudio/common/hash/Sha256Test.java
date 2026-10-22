package fun.fengwk.kkstudio.common.hash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

class Sha256Test {

  private static final String EMPTY =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private static final String ABC =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  // 已知向量：输出必须是 64 位小写十六进制。
  @Test
  void hexOfBytesMatchesKnownVectors() {
    assertEquals(EMPTY, Sha256.hex(new byte[0]));
    assertEquals(ABC, Sha256.hex("abc".getBytes(StandardCharsets.UTF_8)));
  }

  // 文本形式固定按 UTF-8 编码。
  @Test
  void hexOfTextUsesUtf8() {
    assertEquals(ABC, Sha256.hex("abc"));
    assertEquals(Sha256.hex("é".getBytes(StandardCharsets.UTF_8)), Sha256.hex("é"));
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
  void nullInputIsRejected() {
    assertThrows(NullPointerException.class, () -> Sha256.hex((byte[]) null));
    assertThrows(NullPointerException.class, () -> Sha256.hex((String) null));
  }
}
