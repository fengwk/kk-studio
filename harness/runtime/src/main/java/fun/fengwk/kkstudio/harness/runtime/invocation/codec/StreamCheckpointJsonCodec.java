package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;

import java.util.Objects;

/** Strict deterministic JSON codec for a durable Model stream checkpoint. */
public final class StreamCheckpointJsonCodec {

  private static final String CONTEXT = "streamCheckpoint";

  public String encode(StreamCheckpoint checkpoint) {
    return InvocationJsonSupport.write(encodeNode(checkpoint), CONTEXT);
  }

  public ObjectNode encodeNode(StreamCheckpoint checkpoint) {
    Objects.requireNonNull(checkpoint, "checkpoint");
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("attempt", checkpoint.attempt());
    node.put("sequence", checkpoint.sequence());
    InvocationJsonSupport.putNullable(node, "text", checkpoint.text());
    InvocationJsonSupport.putNullable(node, "thinking", checkpoint.thinking());
    return node;
  }

  public StreamCheckpoint decode(String json) {
    return decodeNode(InvocationJsonSupport.parse(json, CONTEXT));
  }

  public StreamCheckpoint decodeNode(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, CONTEXT);
    InvocationJsonSupport.requireFields(node, CONTEXT, "attempt", "sequence", "text", "thinking");
    return new StreamCheckpoint(
        InvocationJsonSupport.positiveInt(node, "attempt", CONTEXT),
        InvocationJsonSupport.nonNegativeLong(node, "sequence", CONTEXT),
        InvocationJsonSupport.nullableText(node, "text", CONTEXT),
        InvocationJsonSupport.nullableText(node, "thinking", CONTEXT));
  }
}
