package fun.fengwk.kkstudio.harness.model.provider;

import java.util.List;
import java.util.Objects;

/** Tool 消息中关联单次调用的结构化结果。 */
public record ProviderToolResultBlock(
    String toolCallId,
    String toolName,
    List<ProviderContentBlock> contents,
    boolean error,
    String detailsJson)
    implements ProviderContentBlock {

  public ProviderToolResultBlock {
    if (toolCallId == null || toolCallId.isBlank()) {
      throw new IllegalArgumentException("toolCallId must not be blank");
    }
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("toolName must not be blank");
    }
    contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
    detailsJson = detailsJson == null || detailsJson.isBlank() ? "{}" : detailsJson;
  }

  /** 兼容仅携带 call id 的早期模型；新消息必须显式携带工具名。 */
  public ProviderToolResultBlock(
      String toolCallId, List<ProviderContentBlock> contents, boolean error, String detailsJson) {
    this(toolCallId, "tool", contents, error, detailsJson);
  }
}
