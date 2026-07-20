package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.util.List;

/**
 * A single Agent model variant. Every optional field may be {@code null} (meaning "do not send,
 * fall back to provider default"); {@code stopSequences} is a possibly-empty list of plain strings.
 *
 * <p>Numeric fields use {@code Integer} to keep the wire format a plain JSON number regardless of
 * convention4j's {@code Long}-as-string autoconfiguration.
 */
@Data
public class AgentModelVariantDTO {

  private String id;
  private String reasoningEffort;
  private Integer maxOutputTokens;
  private Double temperature;
  private Double topP;
  private Integer topK;
  private Double frequencyPenalty;
  private Double presencePenalty;
  private List<String> stopSequences;
}
