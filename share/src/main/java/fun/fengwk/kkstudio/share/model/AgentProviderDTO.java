package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentProviderDTO {

  private String id;
  private String workspaceId;
  private String name;
  private String description;
  private String providerType;
  private String baseUrl;
  private boolean configured;
  private String configJson;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
