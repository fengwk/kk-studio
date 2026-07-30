package fun.fengwk.kkstudio.share.ai.chat;

import lombok.Data;

import java.time.Instant;

/**
 * Public read representation of a Chat collection.
 *
 * <p>主键与 {@code defaultAgentId} 在 HTTP / DTO 边界以十进制字符串暴露；持久层内部仍为 {@code Long}。 Agent 删除后 Chat
 * 可保留过期的 defaultAgentId，由前端提示用户重新选择。
 */
@Data
public class ChatDTO {

  private String id;
  private String title;
  private String defaultAgentId;

  /** Non-negative decimal string version; clients must echo on every update. */
  private String version;

  private Instant createTime;
  private Instant updateTime;
}
