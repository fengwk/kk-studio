package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class AgentProviderEditablePropertiesDTO {

  private String name;
  private String description;
  private String providerType;
  private String baseUrl;
  private String credential;
  private Long modelCallTimeoutMillis;
  private Long modelCallIdleTimeoutMillis;
}
