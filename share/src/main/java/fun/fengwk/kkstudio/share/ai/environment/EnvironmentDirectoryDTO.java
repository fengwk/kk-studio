package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

import java.util.List;

/**
 * Environment Root 下单层目录浏览结果（control-plane 只读，不走 Tool Invocation）。
 *
 * <p>{@code path} / {@code parentPath} 是 canonical 相对 wire 路径（{@code '.'} 表示 root）；{@code
 * displayPath} 是请求 {@code path} 的最后一段（root 为 {@code '.'}），只作展示、绝不暴露 daemon 本地绝对路径；{@code truncated}
 * 表示条目数超过单层上限被截断； {@code gitBranch} 可空，是浏览目录所在 git 仓库的当前分支（仅 symbolic HEAD，不是必需字段）。
 */
@Data
public class EnvironmentDirectoryDTO {
  /** 被浏览目录的 canonical 相对路径（{@code '.'} 表示 root）。 */
  private String path;

  /** 被浏览目录的展示名：请求 {@code path} 的最后一段（root 为 {@code '.'}），绝不暴露本地绝对路径。 */
  private String displayPath;

  /** 父目录的 canonical 相对路径（root 为 {@code '.'}）。 */
  private String parentPath;

  /** 条目数超过单层上限（1000）时为 true。 */
  private boolean truncated;

  /** 浏览目录所在 git 仓库的当前分支；未知时为空。 */
  private String gitBranch;

  /** 按名称稳定排序的直属子目录，不含 symlink；至多 1000 条。 */
  private List<EnvironmentDirectoryEntryDTO> entries;
}
