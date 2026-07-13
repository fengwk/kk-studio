package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Agent definition execution configuration persisted as {@code config_json}.
 *
 * @author fengwk
 */
@Data
public class AgentDefinitionConfigDTO {

  private List<String> tools;
  private List<String> skills;
  private List<String> allowedSubagents;
  private Map<String, Object> executionPolicy;
}
