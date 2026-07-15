package fun.fengwk.kkstudio.core.harness.tool.worker.store;

import java.time.LocalDateTime;
import lombok.Data;

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
