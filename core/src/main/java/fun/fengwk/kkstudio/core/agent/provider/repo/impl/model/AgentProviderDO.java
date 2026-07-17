package fun.fengwk.kkstudio.core.agent.provider.repo.impl.model;

import fun.fengwk.kkstudio.share.model.AgentProviderType;
import java.time.LocalDateTime;
import lombok.Data;

/** Provider persistence row. */
@Data
public class AgentProviderDO {

  private Long id;
  private String name;
  private String description;
  private AgentProviderType providerType;
  private String baseUrl;
  private String credential;
  private String configJson;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
