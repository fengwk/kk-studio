package fun.fengwk.kkstudio.core.agent.model.service.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentModel {

  private Long id;
  private Long providerId;
  private String name;
  private String description;
  private String defaultVariant;
  private String variantsJson;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
