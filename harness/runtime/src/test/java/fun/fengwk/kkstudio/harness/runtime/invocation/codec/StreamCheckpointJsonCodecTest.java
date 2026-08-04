package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;

/** Strict stream-checkpoint persistence boundary for attempt/sequence and nullable content. */
class StreamCheckpointJsonCodecTest {

  private final StreamCheckpointJsonCodec codec = new StreamCheckpointJsonCodec();

  /** All legal nullable text/thinking shapes use one deterministic four-field object. */
  @Test
  void roundTripsEveryLegalContentShapeWithExactJson() {
    StreamCheckpoint both = new StreamCheckpoint(2, 7, "text", "thinking");
    assertEquals(
        "{\"attempt\":2,\"sequence\":7,\"text\":\"text\",\"thinking\":\"thinking\"}",
        codec.encode(both));
    assertEquals(both, codec.decode(codec.encode(both)));
    assertEquals(both, codec.decodeNode(codec.encodeNode(both)));

    StreamCheckpoint textOnly = new StreamCheckpoint(1, 0, "text", null);
    assertEquals(
        "{\"attempt\":1,\"sequence\":0,\"text\":\"text\",\"thinking\":null}",
        codec.encode(textOnly));
    assertNull(codec.decode(codec.encode(textOnly)).thinking());

    StreamCheckpoint thinkingOnly = new StreamCheckpoint(1, 1, null, "thinking");
    assertEquals(
        "{\"attempt\":1,\"sequence\":1,\"text\":null,\"thinking\":\"thinking\"}",
        codec.encode(thinkingOnly));
    assertNull(codec.decode(codec.encode(thinkingOnly)).text());
  }

  /** Strict parser settings reject duplicate, trailing, null and non-object input. */
  @Test
  void rejectsMalformedDocumentBoundaries() {
    String json = codec.encode(new StreamCheckpoint(1, 0, "text", null));
    String duplicate = json.replace("\"attempt\":1", "\"attempt\":1,\"attempt\":2");

    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json + " {}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate));
  }

  /** Numeric bounds, exact fields and domain content requirements are checked on every decode. */
  @Test
  void rejectsUnknownMissingWrongTypeAndInvalidCheckpointFacts() {
    assertInvalid("{\"attempt\":1,\"sequence\":0,\"text\":\"x\",\"thinking\":null,\"extra\":1}");
    assertInvalid("{\"attempt\":1,\"sequence\":0,\"text\":\"x\"}");
    assertInvalid("{\"attempt\":null,\"sequence\":0,\"text\":\"x\",\"thinking\":null}");
    assertInvalid("{\"attempt\":0,\"sequence\":0,\"text\":\"x\",\"thinking\":null}");
    assertInvalid("{\"attempt\":1.5,\"sequence\":0,\"text\":\"x\",\"thinking\":null}");
    assertInvalid("{\"attempt\":2147483648,\"sequence\":0,\"text\":\"x\",\"thinking\":null}");
    assertInvalid("{\"attempt\":1,\"sequence\":-1,\"text\":\"x\",\"thinking\":null}");
    assertInvalid(
        "{\"attempt\":1,\"sequence\":9223372036854775808,\"text\":\"x\",\"thinking\":null}");
    assertInvalid("{\"attempt\":1,\"sequence\":0,\"text\":1,\"thinking\":null}");
    assertInvalid("{\"attempt\":1,\"sequence\":0,\"text\":null,\"thinking\":null}");
    assertInvalid("{\"attempt\":1,\"sequence\":0,\"text\":\" \",\"thinking\":\"\"}");
  }

  private void assertInvalid(String json) {
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
  }
}
