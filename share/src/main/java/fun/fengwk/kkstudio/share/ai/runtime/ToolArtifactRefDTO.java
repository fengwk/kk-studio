package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** Immutable artifact reference included in a task report. */
@Data
public class ToolArtifactRefDTO {
  private String artifactId;
  private String mediaType;
  private Long sizeBytes;
}
