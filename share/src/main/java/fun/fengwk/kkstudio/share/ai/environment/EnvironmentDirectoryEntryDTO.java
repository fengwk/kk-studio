package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

/** 单层目录列表中的单个子目录条目。 */
@Data
public class EnvironmentDirectoryEntryDTO {
  /** 子目录的 canonical 相对路径（可继续作为 {@code path} 参数浏览）。 */
  private String path;

  /** 子目录的展示路径（目录名）。 */
  private String displayPath;
}
