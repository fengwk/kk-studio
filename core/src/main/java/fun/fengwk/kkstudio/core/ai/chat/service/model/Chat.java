package fun.fengwk.kkstudio.core.ai.chat.service.model;

import lombok.Data;

import java.time.Instant;

/** Chat collection domain row. */
@Data
public class Chat {

  private Long id;
  private String title;
  private Long defaultAgentId;
  private String defaultEnvironmentName;
  private Long version;
  private Instant createTime;
  private Instant updateTime;
}
