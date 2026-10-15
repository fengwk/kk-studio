package fun.fengwk.kkstudio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 客户端版本值对象只接受非负目录 epoch 与 64 位小写 SHA-256 fingerprint。 */
class CanvasFunctionVersionTest {

  @Test
  void acceptsCanonicalEpochAndLowercaseFingerprint() {
    CanvasFunctionVersion version = new CanvasFunctionVersion(0L, "a".repeat(64));
    assertEquals(0L, version.catalogEpoch());
    assertEquals("a".repeat(64), version.definitionFingerprint());
  }

  @Test
  void rejectsNegativeEpochAndMalformedFingerprint() {
    assertThrows(
        IllegalArgumentException.class, () -> new CanvasFunctionVersion(-1L, "a".repeat(64)));
    for (String fingerprint :
        new String[] {null, "", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64)}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new CanvasFunctionVersion(1L, fingerprint),
          "fingerprint must be rejected: " + fingerprint);
    }
  }
}
