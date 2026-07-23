package fun.fengwk.kkstudio.core.harness.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;

/** ExecutionTarget 严格确定性 codec 测试。 */
class ExecutionTargetJsonCodecTest {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final ExecutionTargetJsonCodec codec = new ExecutionTargetJsonCodec();

  @Test
  void roundTripsEveryKind() {
    for (ExecutionTargetKind kind : ExecutionTargetKind.values()) {
      ExecutionTarget target = new ExecutionTarget(kind, 42L);
      String expected = "{\"targetKind\":\"" + kind.name() + "\",\"targetId\":\"42\"}";
      assertEquals(expected, codec.encode(target), "encode " + kind);
      assertEquals(target, codec.decode(expected), "decode " + kind);
    }
  }

  @Test
  void rejectsUnknownTopLevelField() {
    ObjectNode node = NODES.objectNode();
    node.put("targetKind", "MODEL_INVOCATION");
    node.put("targetId", "1");
    node.put("extra", "x");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsMissingField() {
    ObjectNode node = NODES.objectNode();
    node.put("targetKind", "MODEL_INVOCATION");
    node.put("targetId", "1");
    node.remove("targetKind");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsUnknownKind() {
    ObjectNode node = NODES.objectNode();
    node.put("targetKind", "FOO");
    node.put("targetId", "1");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsCaseSensitiveKind() {
    ObjectNode node = NODES.objectNode();
    node.put("targetKind", "model_invocation");
    node.put("targetId", "1");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsNumericTargetId() {
    ObjectNode node = NODES.objectNode();
    node.put("targetKind", "MODEL_INVOCATION");
    node.put("targetId", 1);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsNonDecimalTargetId() {
    ObjectNode node = NODES.objectNode();
    node.put("targetKind", "MODEL_INVOCATION");
    node.put("targetId", "abc");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsNonPositiveTargetId() {
    ObjectNode node = NODES.objectNode();
    node.put("targetKind", "MODEL_INVOCATION");
    node.put("targetId", "0");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsTargetIdWithSignPrefix() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"targetKind\":\"THREAD\",\"targetId\":\"+1\"}"));
  }

  @Test
  void rejectsTrailingAndDuplicate() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"targetKind\":\"THREAD\",\"targetId\":\"1\"} trailing"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"targetKind\":\"THREAD\",\"targetKind\":\"MODEL_INVOCATION\",\"targetId\":\"1\"}"));
  }

  @Test
  void rejectsMalformedRootJson() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(NODES.arrayNode()));
  }

  @Test
  void rejectsExplicitNullForTextField() {
    ObjectNode node = NODES.objectNode();
    node.putNull("targetKind");
    node.put("targetId", "1");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void guardsNullArguments() {
    ExecutionTarget target = new ExecutionTarget(ExecutionTargetKind.THREAD, 1L);
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.encodeNode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertEquals("{\"targetKind\":\"THREAD\",\"targetId\":\"1\"}", codec.encode(target));
  }

  @Test
  void encodingIsDeterministic() {
    ExecutionTarget target = new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 42L);
    assertEquals(codec.encode(target), codec.encode(target));
  }
}
