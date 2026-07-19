package fun.fengwk.kkstudio.core.chat.service.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Chat collection domain row. */
@Data
public class Chat {

  private Long id;
  private String title;
  private Long defaultAgentId;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
