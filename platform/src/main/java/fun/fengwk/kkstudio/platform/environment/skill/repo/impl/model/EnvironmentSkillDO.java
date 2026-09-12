package fun.fengwk.kkstudio.platform.environment.skill.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code environment_skill} 行映射：某个来源最新一次成功扫描发现的单个 Skill（正文永不入库）。 */
@Data
public class EnvironmentSkillDO {

  /** 所属 Environment 的全局唯一 UUID。 */
  private UUID environmentId;

  /** 所属来源的全局唯一 UUID。 */
  private UUID sourceId;

  /** Skill canonical 名（同一 Environment 内全局唯一）。 */
  private String name;

  /** 发现该 Skill 时的来源行版本。 */
  private Long sourceVersion;

  /** Skill 描述。 */
  private String description;

  /** Daemon 宿主上的 Skill 目录。 */
  private String baseDirectory;

  /** 内容 revision（小写 64 位 SHA-256）。 */
  private String contentRevision;

  /** 最近一次发现该 Skill 的时间。 */
  private Instant discoveredAt;
}
