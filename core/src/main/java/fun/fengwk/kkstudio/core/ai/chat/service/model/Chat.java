package fun.fengwk.kkstudio.core.ai.chat.service.model;

import lombok.Data;

import java.time.Instant;

/** Chat collection domain row. */
@Data
public class Chat {

  private Long id;
  private String title;
  private String agentName;
  private boolean yoloEnabled;
  private Long version;
  private Instant createTime;
  private Instant updateTime;
}
