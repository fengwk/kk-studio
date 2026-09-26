package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInputReceipt;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** durable 人工输入回执的严格、确定性 JSON codec。 */
public final class ToolInputReceiptJsonCodec {

  private static final String CONTEXT = "toolInputReceipt";

  public String encode(ToolInputReceipt receipt) {
    return InvocationJsonSupport.write(encodeNode(receipt), CONTEXT);
  }

  public ObjectNode encodeNode(ToolInputReceipt receipt) {
    Objects.requireNonNull(receipt, "receipt");
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("submissionId", receipt.submissionId().toString());
    node.put("actor", receipt.actor());
    node.put("acceptedAt", receipt.acceptedAt().toString());
    return node;
  }

  public ToolInputReceipt decode(String json) {
    return decodeNode(InvocationJsonSupport.parse(json, CONTEXT));
  }

  public ToolInputReceipt decodeNode(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, CONTEXT);
    InvocationJsonSupport.requireFields(node, CONTEXT, "submissionId", "actor", "acceptedAt");
    UUID submissionId = InvocationJsonSupport.requiredUuid(node, "submissionId", CONTEXT);
    String actor = InvocationJsonSupport.text(node, "actor", CONTEXT);
    Instant acceptedAt = InvocationJsonSupport.nullableInstant(node, "acceptedAt", CONTEXT);
    if (acceptedAt == null) {
      throw new IllegalArgumentException(CONTEXT + " must declare non-null acceptedAt");
    }
    return new ToolInputReceipt(submissionId, actor, acceptedAt);
  }
}
