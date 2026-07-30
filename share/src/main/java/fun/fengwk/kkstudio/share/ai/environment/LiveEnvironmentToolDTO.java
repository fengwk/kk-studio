package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

/** Compact tool capability published by a live Environment. */
@Data
public class LiveEnvironmentToolDTO {
  private String name;
  private String version;
  private String description;
}
