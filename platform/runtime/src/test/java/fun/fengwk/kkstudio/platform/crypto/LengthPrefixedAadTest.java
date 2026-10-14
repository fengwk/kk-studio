package fun.fengwk.kkstudio.platform.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 长度前缀 AAD 原语的编码契约：字段边界无歧义且可追加整型。 */
class LengthPrefixedAadTest {

  @Test
  void encodesUtf8FieldsWithBigEndianLengthPrefixes() {
    byte[] encoded = LengthPrefixedAad.create().field("ab").field("c").build();

    assertArrayEquals(new byte[] {0, 0, 0, 2, 'a', 'b', 0, 0, 0, 1, 'c'}, encoded);
  }

  @Test
  void appendsIntegerFields() {
    byte[] encoded = LengthPrefixedAad.create().field("x").intValue(2).build();

    assertArrayEquals(new byte[] {0, 0, 0, 1, 'x', 0, 0, 0, 2}, encoded);
  }

  /** 字段边界由长度前缀决定：("ab","c") 与 ("a","bc") 必须产生不同字节，避免拼接歧义。 */
  @Test
  void distinguishesFieldBoundaries() {
    byte[] split = LengthPrefixedAad.create().field("ab").field("c").build();
    byte[] shifted = LengthPrefixedAad.create().field("a").field("bc").build();

    assertFalse(Arrays.equals(split, shifted));
  }

  @Test
  void handlesEmptyFieldsAndNonAscii() {
    byte[] encoded = LengthPrefixedAad.create().field("").field("中").build();

    byte[] expected = new byte[] {0, 0, 0, 0, 0, 0, 0, 3, (byte) 0xE4, (byte) 0xB8, (byte) 0xAD};
    assertArrayEquals(expected, encoded);
    assertTrue(encoded.length == expected.length);
  }

  @Test
  void rejectsNullFields() {
    assertThrows(NullPointerException.class, () -> LengthPrefixedAad.create().field(null));
  }

  @Test
  void buildsFromUtf8BytesConsistently() {
    byte[] encoded = LengthPrefixedAad.create().field("plugin").field("CN").intValue(2).build();

    assertArrayEquals(
        "plugin".getBytes(StandardCharsets.UTF_8), Arrays.copyOfRange(encoded, 4, 10));
  }
}
