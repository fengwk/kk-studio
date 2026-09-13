package fun.fengwk.kkstudio.platform.environment.skill.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code environment_skill} 行领域模型：某个来源最新一次成功扫描发现的单个 Skill。
 *
 * <p>身份是 {@code (sourceId, name)}，{@code sourceVersion} 对齐发现它时的来源行版本，因此旧配置下发现的行仍是可展示的陈旧事实，
 * 但不构成新规划的候选（正文由 Daemon 按 revision 持有，永不入库）。
 */
@Data
public class SkillInventoryEntry {

  /** 所属 Environment 的全局唯一 UUID（与 sourceId 一起构成指向来源配置的复合 FK）。 */
  private UUID environmentId;

  /** 所属来源的全局唯一 UUID。 */
  private UUID sourceId;

  /** Skill canonical 名（同一 Environment 内全局唯一）。 */
  private String name;

  /** 发现该 Skill 时的来源行版本。 */
  private long sourceVersion;

  /** Skill 描述（非空、无环绕空白）。 */
  private String description;

  /** Daemon 宿主上的 Skill 目录（仅事实记录，不在 SQL 解析路径）。 */
  private String baseDirectory;

  /** 内容 revision（小写 64 位 SHA-256）。 */
  private String contentRevision;

  /** 最近一次发现该 Skill 的时间。 */
  private Instant discoveredAt;
}
