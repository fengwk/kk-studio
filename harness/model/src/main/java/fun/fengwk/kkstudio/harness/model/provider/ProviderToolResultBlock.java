package fun.fengwk.kkstudio.harness.model.provider;

import java.util.List;
import java.util.Objects;

/** Tool 消息中关联单次调用的结构化结果。 */
public record ProviderToolResultBlock(
    String toolCallId, List<ProviderContentBlock> contents, boolean error, String detailsJson)
    implements ProviderContentBlock {

  public ProviderToolResultBlock {
    if (toolCallId == null || toolCallId.isBlank()) {
      throw new IllegalArgumentException("toolCallId must not be blank");
    }
    contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
    detailsJson = detailsJson == null || detailsJson.isBlank() ? "{}" : detailsJson;
  }
}
