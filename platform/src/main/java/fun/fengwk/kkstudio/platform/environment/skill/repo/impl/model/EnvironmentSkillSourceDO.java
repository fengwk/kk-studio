package fun.fengwk.kkstudio.platform.environment.skill.repo.impl.model;

import lombok.Data;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/** {@code environment_skill_source} 行映射：Platform 唯一的 Skill 来源配置。 */
@Data
public class EnvironmentSkillSourceDO {

  /** 来源的全局唯一 UUID（应用生成，永不变更）。 */
  private UUID sourceId;

  /** 所属 Environment 的全局唯一 UUID。 */
  private UUID environmentId;

  /** 来源类型 wire 值：path / git。 */
  private String sourceType;

  /** PATH 来源的宿主目录；GIT 来源为 null。 */
  @ToString.Exclude private String path;

  /** 是否该 Environment 的缺省来源。 */
  private Boolean defaultSource;

  /** GIT 来源的仓库 URL（绝不进入错误消息或摘要）。 */
  @ToString.Exclude private String gitUrl;

  /** GIT 来源可选 ref。 */
  @ToString.Exclude private String gitRef;

  /** GIT 来源可选的仓库内相对扫描目录。 */
  @ToString.Exclude private String scanPath;

  /** 行版本与 Daemon sourceVersion。 */
  private Long version;

  /** 应用状态：UNAPPLIED / READY / FAILED。 */
  private String status;

  /** 最近一次成功应用的配置版本。 */
  private Long appliedVersion;

  /** 最近一次成功应用的内容 revision。 */
  private String appliedRevision;

  /** 最近一次扫描的有界诊断（{@code diagnostics} jsonb 列，读为字符串、写回时 cast jsonb）。 */
  private String diagnosticsJson;

  /** FAILED 状态下的失败分类码。 */
  private String lastErrorCode;

  /** FAILED 状态下的失败描述。 */
  private String lastErrorMessage;

  /** 最近一次成功应用的时间。 */
  private Instant lastAppliedAt;

  private Instant createTime;
  private Instant updateTime;
}
