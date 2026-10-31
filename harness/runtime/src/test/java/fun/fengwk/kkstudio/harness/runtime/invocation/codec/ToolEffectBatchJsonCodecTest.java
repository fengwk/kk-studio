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
                new CustomEntryPayload("goal", "state", "{\"n\":1}"),
                new CustomEntryPayload("memory", "state", "{\"n\":2}")));

    String json =
        "{\"customEntries\":["
            + "{\"contributorId\":\"goal\",\"customType\":\"state\",\"data\":{\"n\":1}},"
            + "{\"contributorId\":\"memory\",\"customType\":\"state\",\"data\":{\"n\":2}}]}";
    assertEquals(json, codec.encode(batch));
    assertEquals(batch, codec.decode(json));
    assertEquals(batch, codec.decodeNode(codec.encodeNode(batch)));
    assertEquals("{\"customEntries\":[]}", codec.encode(ToolEffectBatch.EMPTY));
  }

  @Test
  void rejectsMalformedDuplicateTrailingUnknownAndMissingFields() {
    String valid = "{\"customEntries\":[]}";
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(valid + " {}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"customEntries\":[],\"customEntries\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"customEntries\":[],\"extra\":true}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"customEntries\":{}}"));
  }

  @Test
  void rejectsInvalidCustomPayloadAndBatchOverflow() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"customEntries\":["
                    + "{\"contributorId\":\"goal\",\"customType\":\"state\",\"data\":null}]}"));

    List<CustomEntryPayload> entries = new ArrayList<>();
    for (int i = 0; i <= ToolEffectBatch.MAX_CUSTOM_ENTRIES; i++) {
      entries.add(new CustomEntryPayload("goal", "state-" + i, "{}"));
    }
    assertThrows(IllegalArgumentException.class, () -> new ToolEffectBatch(entries));
  }
}
