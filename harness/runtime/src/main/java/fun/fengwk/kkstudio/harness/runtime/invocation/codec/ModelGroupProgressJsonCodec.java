package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelGroupProgress;

/** {@code harness_model_invocation.group_execution} 的严格 JSON codec。 */
public final class ModelGroupProgressJsonCodec {

  private static final String CONTEXT = "groupExecution";

  public String encode(ModelGroupProgress progress) {
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("groupName", progress.groupName());
    node.put("groupVersion", progress.groupVersion());
    node.put("memberIndex", progress.memberIndex());
    node.put("memberAttempts", progress.memberAttempts());
    return InvocationJsonSupport.write(node, CONTEXT);
  }

  public ModelGroupProgress decode(String json) {
    ObjectNode node =
        InvocationJsonSupport.object(InvocationJsonSupport.parse(json, CONTEXT), CONTEXT);
    InvocationJsonSupport.requireFields(
        node, CONTEXT, "groupName", "groupVersion", "memberIndex", "memberAttempts");
    return new ModelGroupProgress(
        InvocationJsonSupport.text(node, "groupName", CONTEXT),
        InvocationJsonSupport.nonNegativeLong(node, "groupVersion", CONTEXT),
        InvocationJsonSupport.nonNegativeInt(node, "memberIndex", CONTEXT),
        InvocationJsonSupport.nonNegativeInt(node, "memberAttempts", CONTEXT));
  }
}
