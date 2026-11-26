package fun.fengwk.kkstudio.harness.codec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.json.JsonCodecSupport;
import fun.fengwk.kkstudio.harness.api.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;

import java.util.Objects;
import java.util.Set;

/** Daemon 预览与持久审批共用同一严格、无截断的编辑审查 JSON 形状。 */
public final class ToolEditReviewJsonCodec {

  private static final String CONTEXT = "editReview";
  private static final Set<String> REQUIRED_FIELDS = Set.of("path", "diff", "sha256");
  private static final Set<String> OPTIONAL_FIELDS = Set.of("lossyDecode");
  private static final Set<String> LOSSY_DECODE_FIELDS = Set.of("encoding", "invalidSequences");

  public ObjectNode encodeNode(ToolEditReview review) {
    Objects.requireNonNull(review, "review");
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("path", review.expectedFile().path());
    node.put("diff", review.diff());
    if (review.expectedFile().sha256() == null) {
      node.putNull("sha256");
    } else {
      node.put("sha256", review.expectedFile().sha256());
    }
    if (review.lossyDecode() != null) {
      ObjectNode lossyDecode = node.putObject("lossyDecode");
      lossyDecode.put("encoding", review.lossyDecode().encoding());
      lossyDecode.put("invalidSequences", review.lossyDecode().invalidSequences());
    }
    return node;
  }

  public ToolEditReview decode(String json) {
    return decodeNode(InvocationJsonSupport.parse(json, CONTEXT));
  }

  public ToolEditReview decodeNode(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, CONTEXT);
    JsonCodecSupport.requireFields(node, REQUIRED_FIELDS, OPTIONAL_FIELDS, CONTEXT);
    return new ToolEditReview(
        new ExpectedFile(
            InvocationJsonSupport.text(node, "path", CONTEXT),
            InvocationJsonSupport.nullableText(node, "sha256", CONTEXT)),
        InvocationJsonSupport.text(node, "diff", CONTEXT),
        node.has("lossyDecode") ? decodeLossyDecode(node.get("lossyDecode")) : null);
  }

  private static ToolEditReview.LossyDecode decodeLossyDecode(JsonNode value) {
    String context = CONTEXT + ".lossyDecode";
    ObjectNode node = InvocationJsonSupport.object(value, context);
    JsonCodecSupport.requireFields(node, LOSSY_DECODE_FIELDS, context);
    return new ToolEditReview.LossyDecode(
        InvocationJsonSupport.text(node, "encoding", context),
        InvocationJsonSupport.positiveInt(node, "invalidSequences", context));
  }
}
