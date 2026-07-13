package fun.fengwk.kkstudio.core.agent.model.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentModelDO {

  private Long id;
  private Long providerId;
  private String name;
  private String description;
  private String defaultVariant;
  private String variantsJson;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
