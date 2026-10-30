package fun.fengwk.kkstudio.common.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class UuidsTest {

  private static final String CANONICAL = "12345678-1234-1234-1234-123456789abc";

  // canonical 文本必须与 UUID.toString() 往返一致，不做大小写归一。
  @Test
  void parseCanonicalAcceptsExactRoundTrip() {
    UUID id = UUID.fromString(CANONICAL);
    assertEquals(id, Uuids.parseCanonical(id.toString(), "canvasId"));
  }

  // 大写与过短文本都不是 canonical，且非法输入保留原解析异常。
  @Test
  void parseCanonicalRejectsUppercaseAndShortValues() {
    IllegalArgumentException uppercase =
        assertThrows(
            IllegalArgumentException.class,
            () -> Uuids.parseCanonical(CANONICAL.toUpperCase(), "canvasId"));
    assertEquals(
        "canvasId must be a canonical UUID: " + CANONICAL.toUpperCase(), uppercase.getMessage());

    IllegalArgumentException shortValue =
        assertThrows(
            IllegalArgumentException.class, () -> Uuids.parseCanonical("1234", "canvasId"));
    assertEquals("canvasId must be a canonical UUID: 1234", shortValue.getMessage());
    assertInstanceOf(IllegalArgumentException.class, shortValue.getCause());
  }

  // null 在解析前拒绝，不包装成 UUID 格式错误。
  @Test
  void parseCanonicalRejectsNull() {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> Uuids.parseCanonical(null, "canvasId"));
    assertEquals("canvasId must not be null", error.getMessage());
  }
}
