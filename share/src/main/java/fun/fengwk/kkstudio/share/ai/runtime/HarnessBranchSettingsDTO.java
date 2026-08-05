package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.util.List;

/**
 * Complete branch settings snapshot of one Entry branch.
 *
 * <p>{@code environmentId} is a nullable canonical lowercase UUID route identity; display names
 * never enter this durable snapshot. {@code activeTools} is immutable at the conversion boundary
 * (the web mapper always copies it).
 */
@Data
public class HarnessBranchSettingsDTO {
  private String environmentId;
  private String agentName;
  private HarnessModelSelectionDTO model;
  private String thinkingLevel;
  private List<String> activeTools = List.of();
}
