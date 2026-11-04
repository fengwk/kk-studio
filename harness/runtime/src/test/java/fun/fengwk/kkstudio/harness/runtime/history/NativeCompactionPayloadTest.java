package fun.fengwk.kkstudio.harness.runtime.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.history.EntryType;
import fun.fengwk.kkstudio.harness.api.history.NativeCompactionPayload;
import fun.fengwk.kkstudio.harness.codec.HistoryEntryPayloadJsonCodec;

class NativeCompactionPayloadTest {
  private final HistoryEntryPayloadJsonCodec codec = new HistoryEntryPayloadJsonCodec();

  // Wire omissions are not equivalent to explicit unknown measurements.
  @Test
  void strictNullableCanonicalWire() {
    var payload = new NativeCompactionPayload("auto", null, 0L, 12L, null, null, "anchor", null);
    String json =
        "{\"trigger\":\"auto\",\"preTokens\":null,\"postTokens\":0,"
            + "\"cumulativeDroppedTokens\":12,\"durationMs\":null,\"nativeHeadId\":null,"
            + "\"nativeAnchorId\":\"anchor\",\"nativeTailId\":null}";
    assertEquals(json, codec.encode(payload));
    assertEquals(payload, codec.decode(EntryType.NATIVE_COMPACTION, json));
    for (String invalid :
        new String[] {
          json.replace("\"preTokens\":null,", ""),
          json.replace("\"preTokens\":null", "\"preTokens\":-1"),
          json.replace("\"preTokens\":null", "\"preTokens\":1.5"),
          json.replace("\"trigger\":\"auto\"", "\"trigger\":\" auto\""),
          json.replace("\"trigger\":\"auto\"", "\"trigger\":null"),
          json.replace("\"trigger\":\"auto\"", "\"trigger\":\"" + "a".repeat(65) + "\""),
          json.replace("\"nativeHeadId\":null", "\"nativeHeadId\":\"" + "a".repeat(257) + "\""),
          json.replace("\"preTokens\":null", "\"preTokens\":9223372036854775808"),
          json.replace("\"nativeHeadId\":null", "\"nativeHeadId\":\"\""),
          json.replace("\"nativeHeadId\":null", "\"nativeHeadId\":42"),
          json.replace("\"trigger\":\"auto\"", "\"unknown\":0,\"trigger\":\"auto\""),
          json.replace("\"trigger\":\"auto\"", "\"trigger\":\"auto\",\"trigger\":\"auto\"")
        }) {
      assertThrows(
          IllegalArgumentException.class, () -> codec.decode(EntryType.NATIVE_COMPACTION, invalid));
    }
  }

  // Every nullable measurement and opaque ID must retain null, including the all-unknown case.
  @Test
  void preservesAllUnknownAndMaximumValues() {
    var unknown = new NativeCompactionPayload("auto", null, null, null, null, null, null, null);
    var maximum =
        new NativeCompactionPayload(
            "a".repeat(64),
            Long.MAX_VALUE,
            0L,
            Long.MAX_VALUE,
            0L,
            "a".repeat(256),
            "anchor",
            "tail");
    for (var payload : new NativeCompactionPayload[] {unknown, maximum}) {
      assertEquals(payload, codec.decode(EntryType.NATIVE_COMPACTION, codec.encode(payload)));
    }
  }
}
