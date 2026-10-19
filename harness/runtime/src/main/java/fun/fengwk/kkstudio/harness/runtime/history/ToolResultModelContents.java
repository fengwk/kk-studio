package fun.fengwk.kkstudio.harness.runtime.history;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolEditReviewJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 纯运行时模型可见 ToolResult 内容派生工具：从成功且携带 editReview 的 ToolResult 中派生带有权威 diff 的内容供模型投影与预算使用。
 *
 * <p>非成功结果或 rendererKey != "edit" 保持原 contents；缺失 editReview 保持原 contents； 存在但非法的 editReview 抛出明确的
 * {@link IllegalArgumentException}。
 */
public final class ToolResultModelContents {

  private static final String EDIT_RENDERER_KEY = "edit";
  private static final String EDIT_REVIEW_FIELD = "editReview";
  private static final ToolEditReviewJsonCodec REVIEW_CODEC = new ToolEditReviewJsonCodec();

  private ToolResultModelContents() {}

  /**
   * 计算指定 ToolResultMessageContent 的模型可见 AgentMessageContent 列表。
   *
   * @param result durable 工具结果消息内容
   * @return 模型可见的不可变 AgentMessageContent 列表
   */
  public static List<AgentMessageContent> contentsOf(ToolResultMessageContent result) {
    Objects.requireNonNull(result, "result");
    if (result.error() || !EDIT_RENDERER_KEY.equals(result.rendererKey())) {
      return result.contents();
    }
    JsonNode detailsNode = JsonObjects.parseObject(result.detailsJson(), "detailsJson");
    if (!detailsNode.has(EDIT_REVIEW_FIELD)) {
      return result.contents();
    }
    ToolEditReview review = REVIEW_CODEC.decodeNode(detailsNode.get(EDIT_REVIEW_FIELD));
    List<AgentMessageContent> derived = new ArrayList<>(result.contents().size() + 1);
    derived.addAll(result.contents());
    derived.add(
        new TextMessageContent(
            "\n\nFile: " + review.expectedFile().path() + "\nDiff:\n" + review.diff()));
    return List.copyOf(derived);
  }
}
