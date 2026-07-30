package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

/** Token limits carried by an Agent model config. {@code output} must be {@code <= context}. */
@Data
public class AgentModelLimitDTO {

  /**
   * Model context window in tokens. Uses {@code Integer} so the wire format is a plain JSON number
   * regardless of convention4j's {@code Long}-as-string autoconfiguration.
   */
  private Integer context;

  /** Maximum output tokens per turn; must be {@code > 0} and {@code <= context}. */
  private Integer output;
}
