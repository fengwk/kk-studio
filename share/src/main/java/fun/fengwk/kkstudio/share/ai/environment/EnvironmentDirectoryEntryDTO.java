package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

/** 单层目录列表中的单个子目录条目。 */
@Data
public class EnvironmentDirectoryEntryDTO {
  /** 子目录名（{@code path} 的最后一段）。 */
  private String name;

  /** 子目录的 canonical 相对路径，是当前列表目录的直接子路径（可继续作为 {@code path} 参数浏览）。 */
  private String path;
}
