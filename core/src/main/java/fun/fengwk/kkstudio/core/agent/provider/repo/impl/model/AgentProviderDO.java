package fun.fengwk.kkstudio.core.agent.provider.repo.impl.model;

import lombok.Data;

import fun.fengwk.kkstudio.agent.provider.ProviderType;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentProviderDO {

  private Long id;
  private Long workspaceId;
  private String name;
  private String description;
  private ProviderType providerType;
  private String baseUrl;
  private String credential;
  private String configJson;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
