package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;

/** attempt/sequence 与非 null content 的严格 stream-checkpoint 持久化边界。 */
class StreamCheckpointJsonCodecTest {

  private final StreamCheckpointJsonCodec codec = new StreamCheckpointJsonCodec();

  /** 所有合法的 text/thinking 形态都使用同一个 deterministic 四字段对象。 */
  @Test
  void roundTripsEveryLegalContentShapeWithExactJson() {
    StreamCheckpoint both = new StreamCheckpoint(2, 7, "text", "thinking");
    assertEquals(
        "{\"attempt\":2,\"sequence\":7,\"text\":\"text\",\"thinking\":\"thinking\"}",
        codec.encode(both));
    assertEquals(both, codec.decode(codec.encode(both)));
    assertEquals(both, codec.decodeNode(codec.encodeNode(both)));

    StreamCheckpoint textOnly = new StreamCheckpoint(1, 0, "text", "");
    assertEquals(
        "{\"attempt\":1,\"sequence\":0,\"text\":\"text\",\"thinking\":\"\"}",
        codec.encode(textOnly));
    assertEquals("", codec.decode(codec.encode(textOnly)).thinking());

    StreamCheckpoint thinkingOnly = new StreamCheckpoint(1, 1, "", "thinking");
    assertEquals(
        "{\"attempt\":1,\"sequence\":1,\"text\":\"\",\"thinking\":\"thinking\"}",
        codec.encode(thinkingOnly));
    assertEquals("", codec.decode(codec.encode(thinkingOnly)).text());
  }

  /** 严格 parser 设置拒绝 duplicate、trailing、null 以及非 object 的输入。 */
  @Test
  void rejectsMalformedDocumentBoundaries() {
    String json = codec.encode(new StreamCheckpoint(1, 0, "text", ""));
    String duplicate = json.replace("\"attempt\":1", "\"attempt\":1,\"attempt\":2");

    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json + " {}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate));
  }

  /** 数值边界、精确字段以及领域内容要求在每次 decode 时都会被校验。 */
  @Test
  void rejectsUnknownMissingWrongTypeAndInvalidCheckpointFacts() {
    assertInvalid("{\"attempt\":1,\"sequence\":0,\"text\":\"x\",\"thinking\":\"\",\"extra\":1}");
    assertInvalid("{\"attempt\":1,\"sequence\":0,\"text\":\"x\"}");
    assertInvalid("{\"attempt\":null,\"sequence\":0,\"text\":\"x\",\"thinking\":\"\"}");
    assertInvalid("{\"attempt\":0,\"sequence\":0,\"text\":\"x\",\"thinking\":\"\"}");
    assertInvalid("{\"attempt\":1.5,\"sequence\":0,\"text\":\"x\",\"thinking\":\"\"}");
    assertInvalid("{\"attempt\":2147483648,\"sequence\":0,\"text\":\"x\",\"thinking\":\"\"}");
    assertInvalid("{\"attempt\":1,\"sequence\":-1,\"text\":\"x\",\"thinking\":\"\"}");
    assertInvalid(
        "{\"attempt\":1,\"sequence\":9223372036854775808,\"text\":\"x\",\"thinking\":\"\"}");
    assertInvalid("{\"attempt\":1,\"sequence\":0,\"text\":1,\"thinking\":\"\"}");
    assertInvalid("{\"attempt\":1,\"sequence\":0,\"text\":null,\"thinking\":\"\"}");
    assertInvalid("{\"attempt\":1,\"sequence\":0,\"text\":\"\",\"thinking\":\"\"}");
  }

  private void assertInvalid(String json) {
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
  }
}
