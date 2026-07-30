package fun.fengwk.kkstudio.harness.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ToolInvocationErrorJsonCodecTest {

  private final ToolInvocationErrorJsonCodec codec = new ToolInvocationErrorJsonCodec();

  @Test
  void encodesAndDecodesCanonicalError() {
    ToolInvocationError error = new ToolInvocationError("PERMISSION_DENIED", "Rejected.");

    assertEquals("{\"kind\":\"PERMISSION_DENIED\",\"message\":\"Rejected.\"}", codec.encode(error));
    assertEquals(error, codec.decode(codec.encode(error)));
  }

  @Test
  void rejectsUnknownOrMalformedShape() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"kind\":\"A\",\"message\":\"B\",\"extra\":true}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"kind\":\"A\",\"kind\":\"B\",\"message\":\"B\"}"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode("{\"kind\":1,\"message\":\"B\"}"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode("{\"kind\":\"A\",\"message\":1}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"kind\":\"A\"}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"kind\":\"A\",\"message\":\"B\"} {}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("not-json"));
  }

  @Test
  void rejectsNullsAndBlankSemanticFields() {
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(IllegalArgumentException.class, () -> new ToolInvocationError("", "message"));
    assertThrows(IllegalArgumentException.class, () -> new ToolInvocationError("kind", " "));
  }
}
