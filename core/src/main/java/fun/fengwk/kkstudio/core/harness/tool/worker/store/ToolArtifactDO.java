package fun.fengwk.kkstudio.core.harness.tool.worker.store;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ToolArtifactDO {
  private Long id;
  private String mediaType;
  private String encoding;
  private byte[] content;
  private Long sizeBytes;
  private String sha256;
  private LocalDateTime createTime;
}
