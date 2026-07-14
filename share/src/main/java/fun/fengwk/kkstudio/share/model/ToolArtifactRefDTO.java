package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** Immutable artifact reference included in a task report. */
@Data
public class ToolArtifactRefDTO {
  private String artifactId;
  private String mediaType;
  private Long sizeBytes;
}
