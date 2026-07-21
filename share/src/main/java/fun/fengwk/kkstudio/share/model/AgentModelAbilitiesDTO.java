package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.util.List;

/**
 * Functional abilities of an Agent model.
 *
 * <p>{@code inputModalities} is a non-empty subset of {@link AgentModelInputModality}; {@code
 * tools} and {@code reasoning} are first-class booleans driving runtime behaviour.
 */
@Data
public class AgentModelAbilitiesDTO {

  private Boolean tools;
  private Boolean reasoning;
  private List<AgentModelInputModality> inputModalities;
}
