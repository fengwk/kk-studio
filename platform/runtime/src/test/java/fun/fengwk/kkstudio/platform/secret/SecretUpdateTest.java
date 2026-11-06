package fun.fengwk.kkstudio.platform.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.secret.SecretUpdate.Operation;

import java.nio.charset.StandardCharsets;

/** 秘密更新操作的三态契约与安全校验测试。 */
class SecretUpdateTest {

  private static final String SECRET = "super-secret-value-98765";

  /** 验证 KEEP、CLEAR、SET 三种合法操作的静态工厂与字段取值。 */
  @Test
  void acceptsTheThreeRealOperations() {
    SecretUpdate keep = SecretUpdate.keep();
    assertEquals(Operation.KEEP, keep.operation());
    assertNull(keep.value());

    SecretUpdate clear = SecretUpdate.clear();
    assertEquals(Operation.CLEAR, clear.operation());
    assertNull(clear.value());

    SecretUpdate set = SecretUpdate.set(SECRET);
    assertEquals(Operation.SET, set.operation());
    assertEquals(SECRET, set.value());
  }

  /** 验证 KEEP 与 CLEAR 操作不能携带秘密值，否则抛出非法参数异常。 */
  @Test
  void keepAndClearMustNotCarryAValue() {
    assertThrows(IllegalArgumentException.class, () -> new SecretUpdate(Operation.KEEP, SECRET));
    assertThrows(IllegalArgumentException.class, () -> new SecretUpdate(Operation.CLEAR, SECRET));
  }

  /** 验证 operation 必须非空。 */
  @Test
  void operationMustNotBeNull() {
    assertThrows(NullPointerException.class, () -> new SecretUpdate(null, null));
    assertThrows(NullPointerException.class, () -> new SecretUpdate(null, SECRET));
  }

  /** 验证 SET 操作拒绝 null、空白、首尾带空白、超长以及纯遮罩占位符值。 */
  @Test
  void setRejectsBlankPaddedOversizedAndMaskValues() {
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set(null));
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set(""));
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set("   "));
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set(" padded"));
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set("padded "));
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set("\npadded"));
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set("padded\t"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SecretUpdate.set("x".repeat(SecretUpdate.MAX_SECRET_CHARS + 1)));
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set("***"));
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set("•••"));
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set("···"));
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set("●●●"));
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set("◦◦◦"));
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set("⁃⁃⁃"));
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set("- - -"));

    // 多字节字符同样按 UTF-8 字节预算拒绝。
    String multiByte = "汉".repeat(SecretUpdate.MAX_SECRET_CHARS / 3 + 1);
    assertTrue(multiByte.getBytes(StandardCharsets.UTF_8).length > SecretUpdate.MAX_SECRET_CHARS);
    assertThrows(IllegalArgumentException.class, () -> SecretUpdate.set(multiByte));
  }

  /** 验证 toString 绝不暴露秘密明文，且 equals 与 hashCode 正确运作。 */
  @Test
  void toStringAndAccessorsNeverExposeTheValue() {
    SecretUpdate update = SecretUpdate.set(SECRET);

    assertFalse(update.toString().contains(SECRET));
    assertTrue(update.toString().contains("<redacted>"));
    assertTrue(update.toString().contains(Operation.SET.name()));
    assertFalse(update.equals(null));
    assertFalse(update.equals("not-a-secret-update"));
    assertEquals(update, SecretUpdate.set(SECRET));
    assertEquals(update.hashCode(), SecretUpdate.set(SECRET).hashCode());
  }
}
