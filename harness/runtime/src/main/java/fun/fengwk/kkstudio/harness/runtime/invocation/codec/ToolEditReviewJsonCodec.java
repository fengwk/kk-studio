package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEditReview;

import java.util.Objects;

/** Daemon 预览与持久审批共用同一严格、无截断的编辑审查 JSON 形状。 */
public final class ToolEditReviewJsonCodec {

  private static final String CONTEXT = "editReview";

  public ObjectNode encodeNode(ToolEditReview review) {
    Objects.requireNonNull(review, "review");
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("path", review.expectedFile().path());
    node.put("diff", review.diff());
    node.put("sha256", review.expectedFile().sha256());
    return node;
  }

  public ToolEditReview decode(String json) {
    return decodeNode(InvocationJsonSupport.parse(json, CONTEXT));
  }

  public ToolEditReview decodeNode(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, CONTEXT);
    InvocationJsonSupport.requireFields(node, CONTEXT, "path", "diff", "sha256");
    return new ToolEditReview(
        new ExpectedFile(
            InvocationJsonSupport.text(node, "path", CONTEXT),
            InvocationJsonSupport.text(node, "sha256", CONTEXT)),
        InvocationJsonSupport.text(node, "diff", CONTEXT));
  }
}
