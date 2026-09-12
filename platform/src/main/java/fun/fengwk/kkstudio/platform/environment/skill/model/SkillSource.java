package fun.fengwk.kkstudio.platform.environment.skill.model;

import lombok.Data;
import lombok.ToString;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDiagnostic;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceType;
import fun.fengwk.kkstudio.platform.environment.skill.SkillSourceStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code environment_skill_source} 行领域模型：Platform 唯一的 Skill 来源配置。
 *
 * <p>{@code version} 同时承担两个职责：行级 CAS 乐观锁，以及下发给 Daemon 的 {@code sourceVersion}；不存在第二个配置代际。 {@code
 * applied*} 三元组只在来源被 Daemon 成功应用后出现，因此 {@code UNAPPLIED} 允许保留旧的已应用事实（配置刚改、Daemon 还没重新应用），但绝不允许残留
 * last error。{@code gitUrl} 可能携带用户凭据，绝不进入错误消息。
 */
@Data
public class SkillSource {

  /** 来源的全局唯一 UUID（应用生成，永不变更）。 */
  private UUID sourceId;

  /** 所属 Environment 的全局唯一 UUID。 */
  private UUID environmentId;

  /** 来源类型。 */
  private DaemonSkillSourceType type;

  /** PATH 来源的宿主目录（仅 path 类型非空；Daemon 以自己的文件系统解析）。 */
  @ToString.Exclude private String path;

  /** 是否该 Environment 的缺省来源；每个 Environment 至多一个（仅 path 类型可为 true）。 */
  private boolean defaultSource;

  /** GIT 来源的仓库 URL（仅 git 类型非空）。 */
  @ToString.Exclude private String gitUrl;

  /** GIT 来源可选 ref：为空表示跟踪远端默认 HEAD。 */
  @ToString.Exclude private String gitRef;

  /** GIT 来源可选的仓库内相对扫描目录（仅 git 类型可非空）。 */
  @ToString.Exclude private String scanPath;

  /** 行版本与 Daemon sourceVersion（非负，从 0 开始，每次配置更新 +1）。 */
  private long version;

  /** 应用状态。 */
  private SkillSourceStatus status = SkillSourceStatus.UNAPPLIED;

  /** 最近一次成功应用的配置版本；必须不超过 {@link #version}。 */
  private Long appliedVersion;

  /** 最近一次成功应用的内容 revision（40 位 SHA-1 或 64 位 SHA-256）。 */
  private String appliedRevision;

  /** 最近一次扫描的有界诊断（可为空列表，绝不为 null）。 */
  private List<DaemonSkillDiagnostic> diagnostics = List.of();

  /** FAILED 状态下的失败分类码。 */
  private String lastErrorCode;

  /** FAILED 状态下的失败描述。 */
  private String lastErrorMessage;

  /** 最近一次成功应用的时间（与 appliedVersion/appliedRevision 成对）。 */
  private Instant lastAppliedAt;

  private Instant createTime;
  private Instant updateTime;
}
