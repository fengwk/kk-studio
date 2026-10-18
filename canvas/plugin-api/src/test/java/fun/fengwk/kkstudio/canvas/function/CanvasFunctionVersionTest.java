package fun.fengwk.kkstudio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 客户端版本值对象只接受 64 位小写 SHA-256 fingerprint。 */
class CanvasFunctionVersionTest {

  @Test
  void acceptsLowercaseFingerprint() {
    CanvasFunctionVersion version = new CanvasFunctionVersion("a".repeat(64));
    assertEquals("a".repeat(64), version.definitionFingerprint());
  }

  @Test
  void rejectsMalformedFingerprint() {
    for (String fingerprint :
        new String[] {null, "", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64)}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new CanvasFunctionVersion(fingerprint),
          "fingerprint must be rejected: " + fingerprint);
    }
  }
}
