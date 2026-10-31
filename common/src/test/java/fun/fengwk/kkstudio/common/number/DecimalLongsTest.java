package fun.fengwk.kkstudio.common.number;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Canonical 十进制 long 的边界：零、上限、空白、符号、前导零与越界都必须确定。 */
class DecimalLongsTest {

  // 合法形态只含 canonical 数字本身，0 与 Long.MAX_VALUE 都可精确往返。
  @Test
  void acceptsCanonicalBounds() {
    assertEquals(0L, DecimalLongs.parseNonNegative("0", "version"));
    assertEquals(42L, DecimalLongs.parseNonNegative("42", "version"));
    assertEquals(
        Long.MAX_VALUE, DecimalLongs.parseNonNegative(Long.toString(Long.MAX_VALUE), "version"));
    assertEquals(1L, DecimalLongs.parsePositive("1", "sequence"));
    assertEquals(
        Long.MAX_VALUE, DecimalLongs.parsePositive(Long.toString(Long.MAX_VALUE), "sequence"));
  }

  // 非负解析拒绝 null、空白、符号、前导零和小数，且不把空白归一成合法输入。
  @Test
  void rejectsNonCanonicalNonNegativeValues() {
    assertRejected(
        () -> DecimalLongs.parseNonNegative(null, "version"), "version must not be null");
    assertRejected(
        () -> DecimalLongs.parseNonNegative("", "version"),
        "version must be a canonical non-negative decimal: ");
    assertRejected(
        () -> DecimalLongs.parseNonNegative(" ", "version"),
        "version must be a canonical non-negative decimal:  ");
    assertRejected(
        () -> DecimalLongs.parseNonNegative(" 1", "version"),
        "version must be a canonical non-negative decimal:  1");
    assertRejected(
        () -> DecimalLongs.parseNonNegative("1 ", "version"),
        "version must be a canonical non-negative decimal: 1 ");
    assertRejected(
        () -> DecimalLongs.parseNonNegative("+1", "version"),
        "version must be a canonical non-negative decimal: +1");
    assertRejected(
        () -> DecimalLongs.parseNonNegative("-1", "version"),
        "version must be a canonical non-negative decimal: -1");
    assertRejected(
        () -> DecimalLongs.parseNonNegative("01", "version"),
        "version must be a canonical non-negative decimal: 01");
    assertRejected(
        () -> DecimalLongs.parseNonNegative("1.5", "version"),
        "version must be a canonical non-negative decimal: 1.5");
    assertRejected(
        () -> DecimalLongs.parseNonNegative("9223372036854775808", "version"),
        "version exceeds long range: 9223372036854775808");
  }

  // 正数解析额外拒绝 0，同时保留越界与非数字拒绝。
  @Test
  void rejectsNonCanonicalPositiveValues() {
    assertRejected(
        () -> DecimalLongs.parsePositive("0", "sequence"),
        "sequence must be a canonical positive decimal: 0");
    assertRejected(
        () -> DecimalLongs.parsePositive("01", "sequence"),
        "sequence must be a canonical positive decimal: 01");
    assertRejected(
        () -> DecimalLongs.parsePositive("-1", "sequence"),
        "sequence must be a canonical positive decimal: -1");
    assertRejected(() -> DecimalLongs.parsePositive(null, "sequence"), "sequence must not be null");
    assertRejected(
        () -> DecimalLongs.parsePositive("9223372036854775808", "sequence"),
        "sequence exceeds long range: 9223372036854775808");
  }

  private static void assertRejected(Executable executable, String message) {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, executable);
    assertEquals(message, error.getMessage());
  }
}
