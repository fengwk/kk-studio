package fun.fengwk.kkstudio.platform.harness.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/** 会话文件面板的目录条目：文件带大小与 revision，子目录以 {@code /} 结尾。 */
@Data
public class HarnessSessionFileEntryDTO {

  /** 条目名；子目录以 {@code /} 结尾。 */
  private String name;

  /** 会话内相对路径；子目录同样以 {@code /} 结尾。 */
  private String path;

  /** 是否为目录。 */
  private boolean directory;

  /** 文件字节数；目录为 null。 */
  private @Nullable Long sizeBytes;

  /** 文件 revision；目录为 null。 */
  private @Nullable Integer revision;

  /** 最后更新时间；目录为 null。 */
  private @Nullable Instant updatedAt;
}
