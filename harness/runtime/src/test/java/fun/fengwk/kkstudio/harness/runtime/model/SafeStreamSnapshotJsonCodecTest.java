package fun.fengwk.kkstudio.harness.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class SafeStreamSnapshotJsonCodecTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final SafeStreamSnapshotJsonCodec codec = new SafeStreamSnapshotJsonCodec();

  @Test
  void roundTripsCanonicalTextAndThinkingSnapshot() {
    SafeStreamSnapshot snapshot = new SafeStreamSnapshot("hello", "thought");
    String expected = canonicalJson();

    assertEquals(expected, codec.encode(snapshot));
    assertEquals(snapshot, codec.decode(expected));
    assertEquals(snapshot, codec.decodeNode(codec.encodeNode(snapshot)));
  }

  @Test
  void allowsEmptyTextAndEmptyThinking() {
    // Pure-thought stream: text is the empty string, thinking carries real content.
    SafeStreamSnapshot thoughtOnly = new SafeStreamSnapshot("", "reasoning");
    String thoughtOnlyJson = "{\"text\":\"\",\"thinking\":\"reasoning\"}";
    assertEquals(thoughtOnly, codec.decode(thoughtOnlyJson));
    assertEquals(thoughtOnlyJson, codec.encode(thoughtOnly));

    // Pure-text stream: thinking is the empty string.
    SafeStreamSnapshot textOnly = new SafeStreamSnapshot("answer", "");
    String textOnlyJson = "{\"text\":\"answer\",\"thinking\":\"\"}";
    assertEquals(textOnly, codec.decode(textOnlyJson));
    assertEquals(textOnlyJson, codec.encode(textOnly));

    // EMPTY sentinel must round-trip and the encoded form must remain canonical.
    assertEquals("{\"text\":\"\",\"thinking\":\"\"}", codec.encode(SafeStreamSnapshot.EMPTY));
    assertEquals(SafeStreamSnapshot.EMPTY, codec.decode("{\"text\":\"\",\"thinking\":\"\"}"));
  }

  @Test
  void rejectsExtraAndMissingFields() {
    ObjectNode extra = NODES.objectNode();
    extra.put("text", "x");
    extra.put("thinking", "y");
    extra.put("extra", 1);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(extra));

    ObjectNode missingText = canonicalNode();
    missingText.remove("text");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(missingText));

    ObjectNode missingThinking = canonicalNode();
    missingThinking.remove("thinking");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(missingThinking));
  }

  @Test
  void rejectsNonStringFieldsAndExplicitNulls() {
    ObjectNode textIsNumber = canonicalNode();
    textIsNumber.put("text", 1);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(textIsNumber));

    ObjectNode thinkingIsBoolean = canonicalNode();
    thinkingIsBoolean.put("thinking", true);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(thinkingIsBoolean));

    ObjectNode textIsNull = canonicalNode();
    textIsNull.putNull("text");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(textIsNull));

    ObjectNode thinkingIsNull = canonicalNode();
    thinkingIsNull.putNull("thinking");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(thinkingIsNull));
  }

  @Test
  void rejectsNonObjectRootsAndMalformedJson() {
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(NODES.arrayNode()));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(NODES.nullNode()));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(NODES.missingNode()));
    ObjectNode emptyObject = NODES.objectNode();
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(emptyObject));

    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(" "));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
  }

  @Test
  void rejectsDuplicateFieldsAndTrailingTokens() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"text\":\"x\",\"text\":\"y\",\"thinking\":\"z\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"text\":\"x\",\"thinking\":\"y\"} extra"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"text\":\"x\",\"thinking\":\"y\"} { }"));
  }

  @Test
  void rejectsNullArgumentsAtBoundary() {
    assertThrows(IllegalArgumentException.class, () -> codec.encode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.encodeNode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(null));
  }

  private ObjectNode canonicalNode() {
    ObjectNode node = NODES.objectNode();
    node.put("text", "hello");
    node.put("thinking", "thought");
    return node;
  }

  private String canonicalJson() {
    try {
      // Plain Jackson serialization to compare against the codec's emitted form without
      // duplicating its own field-order assumption in two places.
      return MAPPER.writeValueAsString(canonicalNode());
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot serialize canonical snapshot", error);
    }
  }
}
