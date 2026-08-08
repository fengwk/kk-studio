package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** {@link ToolEffectBatch} 的严格、确定性 JSON codec。 */
public final class ToolEffectBatchJsonCodec {

  private static final int VERSION = 1;
  private static final String CONTEXT = "toolEffectBatch";
  private static final HistoryEntryPayloadJsonCodec ENTRY_CODEC =
      new HistoryEntryPayloadJsonCodec();

  public String encode(ToolEffectBatch batch) {
    return InvocationJsonSupport.write(encodeNode(batch), CONTEXT);
  }

  public ObjectNode encodeNode(ToolEffectBatch batch) {
    Objects.requireNonNull(batch, "batch");
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("version", VERSION);
    ArrayNode entries = node.putArray("customEntries");
    for (CustomEntryPayload payload : batch.customEntries()) {
      entries.add(ENTRY_CODEC.encodeNode(payload));
    }
    return node;
  }

  public ToolEffectBatch decode(String json) {
    return decodeNode(InvocationJsonSupport.parse(json, CONTEXT));
  }

  public ToolEffectBatch decodeNode(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, CONTEXT);
    InvocationJsonSupport.requireFields(node, CONTEXT, "version", "customEntries");
    int version = InvocationJsonSupport.positiveInt(node, "version", CONTEXT);
    if (version != VERSION) {
      throw new IllegalArgumentException(CONTEXT + ".version must be " + VERSION);
    }
    ArrayNode entries =
        InvocationJsonSupport.array(
            InvocationJsonSupport.required(node, "customEntries", CONTEXT),
            CONTEXT + ".customEntries");
    List<CustomEntryPayload> payloads = new ArrayList<>(entries.size());
    for (JsonNode entry : entries) {
      payloads.add((CustomEntryPayload) ENTRY_CODEC.decodeNode(EntryType.CUSTOM, entry));
    }
    return new ToolEffectBatch(payloads);
  }
}
