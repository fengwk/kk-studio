package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;

import java.time.Instant;
import java.util.Objects;

/** durable Tool approval 状态的严格、确定性 JSON codec。 */
public final class ToolApprovalJsonCodec {

  private static final String CONTEXT = "toolApproval";

  public String encode(ToolApproval approval) {
    return InvocationJsonSupport.write(encodeNode(approval), CONTEXT);
  }

  public ObjectNode encodeNode(ToolApproval approval) {
    Objects.requireNonNull(approval, "approval");
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("required", approval.required());
    InvocationJsonSupport.putNullable(node, "decision", approval.decision());
    InvocationJsonSupport.putNullable(node, "decisionId", approval.decisionId());
    InvocationJsonSupport.putNullable(node, "actor", approval.actor());
    InvocationJsonSupport.putNullable(node, "reason", approval.reason());
    InvocationJsonSupport.putNullable(node, "requestedAt", approval.requestedAt());
    InvocationJsonSupport.putNullable(node, "decidedAt", approval.decidedAt());
    return node;
  }

  public ToolApproval decode(String json) {
    return decodeNode(InvocationJsonSupport.parse(json, CONTEXT));
  }

  public ToolApproval decodeNode(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, CONTEXT);
    InvocationJsonSupport.requireFields(
        node,
        CONTEXT,
        "required",
        "decision",
        "decisionId",
        "actor",
        "reason",
        "requestedAt",
        "decidedAt");
    boolean required = InvocationJsonSupport.bool(node, "required", CONTEXT);
    ToolApprovalDecision decision =
        InvocationJsonSupport.nullableEnum(node, "decision", ToolApprovalDecision.class, CONTEXT);
    String decisionId = InvocationJsonSupport.nullableText(node, "decisionId", CONTEXT);
    String actor = InvocationJsonSupport.nullableText(node, "actor", CONTEXT);
    String reason = InvocationJsonSupport.nullableText(node, "reason", CONTEXT);
    Instant requestedAt = InvocationJsonSupport.nullableInstant(node, "requestedAt", CONTEXT);
    Instant decidedAt = InvocationJsonSupport.nullableInstant(node, "decidedAt", CONTEXT);
    return new ToolApproval(required, decision, decisionId, actor, reason, requestedAt, decidedAt);
  }
}
