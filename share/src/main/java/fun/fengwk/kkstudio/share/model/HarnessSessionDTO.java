package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Session Entry Tree 容器查询。 */
@Data
public class HarnessSessionDTO {
  private String sessionId;
  private String agentDefinitionId;
  private String title;
  private String rootSessionId;
  private String parentSessionId;
  private Integer depth;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
