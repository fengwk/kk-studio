package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.util.List;

/**
 * Agent definition execution configuration persisted in the {@code agent_definition.config} JSONB
 * column.
 *
 * <p>{@code environmentName} is optional. {@code tools} / {@code skills} / {@code allowedSubagents}
 * are short names only (no namespace or path strings).
 *
 * @author fengwk
 */
@Data
public class AgentDefinitionConfigDTO {

  /** Optional live Environment name selected by this Agent; blank means none. */
  private String environmentName;

  private List<String> tools;
  private List<String> skills;
  private List<String> allowedSubagents;
  private AgentExecutionPolicyDTO executionPolicy;
}
