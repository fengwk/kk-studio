package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;

/**
 * Agent definition execution configuration persisted in the {@code agent_definition.config} JSONB
 * column.
 *
 * <p>{@code tools} and {@code skills} are short names only (no namespace or path strings).
 *
 * @author fengwk
 */
@Data
public class AgentDefinitionConfigDTO {

  private List<String> tools;
  private List<String> skills;

  /** Rejects fields that are outside the compact persisted configuration contract. */
  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown agent definition config field: " + fieldName);
  }
}
