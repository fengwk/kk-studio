package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;

import java.util.ArrayList;
import java.util.List;

/** ToolEffectBatch durable wire 的严格性与确定性测试。 */
class ToolEffectBatchJsonCodecTest {

  private final ToolEffectBatchJsonCodec codec = new ToolEffectBatchJsonCodec();

  @Test
  void roundTripsInDeclaredOrderWithCanonicalJson() {
    ToolEffectBatch batch =
        new ToolEffectBatch(
            List.of(
                new CustomEntryPayload("goal", "state", 1, "{\"n\":1}"),
                new CustomEntryPayload("memory", "state", 2, "{\"n\":2}")));

    String json =
        "{\"version\":1,\"customEntries\":["
            + "{\"pluginId\":\"goal\",\"customType\":\"state\",\"schemaVersion\":1,\"data\":{\"n\":1}},"
            + "{\"pluginId\":\"memory\",\"customType\":\"state\",\"schemaVersion\":2,\"data\":{\"n\":2}}]}";
    assertEquals(json, codec.encode(batch));
    assertEquals(batch, codec.decode(json));
    assertEquals(batch, codec.decodeNode(codec.encodeNode(batch)));
    assertEquals("{\"version\":1,\"customEntries\":[]}", codec.encode(ToolEffectBatch.EMPTY));
  }

  @Test
  void rejectsMalformedDuplicateTrailingUnknownMissingAndUnsupportedVersion() {
    String valid = "{\"version\":1,\"customEntries\":[]}";
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(valid + " {}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"version\":1,\"version\":1,\"customEntries\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"version\":1,\"customEntries\":[],\"extra\":true}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"customEntries\":[]}"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode("{\"version\":2,\"customEntries\":[]}"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode("{\"version\":1,\"customEntries\":{}}"));
  }

  @Test
  void rejectsInvalidCustomPayloadAndBatchOverflow() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"customEntries\":["
                    + "{\"pluginId\":\"goal\",\"customType\":\"state\","
                    + "\"schemaVersion\":1,\"data\":null}]}"));

    List<CustomEntryPayload> entries = new ArrayList<>();
    for (int i = 0; i <= ToolEffectBatch.MAX_CUSTOM_ENTRIES; i++) {
      entries.add(new CustomEntryPayload("goal", "state-" + i, 1, "{}"));
    }
    assertThrows(IllegalArgumentException.class, () -> new ToolEffectBatch(entries));
  }
}
