package fun.fengwk.kkstudio.share.ai.chat;

import lombok.Data;

import java.time.Instant;

/** Public read representation of a Chat collection. */
@Data
public class ChatDTO {

  private String id;
  private String title;

  /** Required visible Agent identity; it may later be stale after Agent deletion. */
  private String agentName;

  /** Visible sending permission mode. */
  private boolean yoloEnabled;

  /** Non-negative decimal string version; clients must echo on every update. */
  private String version;

  private Instant createTime;
  private Instant updateTime;
}
