package fun.fengwk.kkstudio.share.ai.interaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 待处理 Interaction 的产品来源：Chat 或 Issue+Agent。
 *
 * <p>{@code type} 为 {@code CHAT} 时 {@code chatId} 非空；为 {@code ISSUE_AGENT} 时 {@code issueId} 与
 * {@code agentName} 非空。空字段显式序列化为 null，避免客户端猜字段存在性。
 */
@Data
public class InteractionOwnerDTO {

  private String type;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String chatId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String issueId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String agentName;
}
