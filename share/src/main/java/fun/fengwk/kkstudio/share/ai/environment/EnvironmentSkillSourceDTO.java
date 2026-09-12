package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import lombok.ToString;

import java.time.Instant;
import java.util.List;

/**
 * Environment Skill 来源配置响应 DTO。
 *
 * <p>{@code version} 同时是行级 CAS 令牌与下发给 Daemon 的 {@code sourceVersion}；{@code defaultSource}
 * 由服务端拥有（创建 Environment 时生成的缺省来源），客户端不可配置。{@code gitUrl} 可能携带用户凭据，因此不进入 {@code
 * toString()}，但仍在响应中返回，供编辑表单回显当前配置。
 */
@Data
public class EnvironmentSkillSourceDTO {

  /** 来源全局唯一 UUID（创建后不可变）。 */
  private String sourceId;

  /** 所属 Environment UUID。 */
  private String environmentId;

  /** 来源类型 wire 值：{@code path} / {@code git}。 */
  private String type;

  /** PATH 来源目录；GIT 来源为 null。 */
  @ToString.Exclude private String path;

  /** GIT 来源仓库 URL；PATH 来源为 null。 */
  @ToString.Exclude private String gitUrl;

  /** GIT 来源 ref；缺省（null）表示跟踪远端默认 HEAD。 */
  @ToString.Exclude private String gitRef;

  /** GIT 来源仓库内相对扫描目录；null 表示仓库根。 */
  @ToString.Exclude private String scanPath;

  /** 是否该 Environment 的缺省来源（服务端拥有：每个 Environment 至多一个，且仅 PATH 可为 true）。 */
  private boolean defaultSource;

  /** 行版本（canonical 非负十进制字符串）。 */
  private String version;

  /** 应用状态：UNAPPLIED / READY / FAILED。 */
  private String status;

  /** 最近一次成功应用的配置版本；从未应用时为 null。 */
  private String appliedVersion;

  /** 最近一次成功应用的内容 revision（40 位 SHA-1 commit 或 64 位 SHA-256 聚合）。 */
  private String appliedRevision;

  /** 最近一次扫描的有界诊断。 */
  private List<EnvironmentSkillDiagnosticDTO> diagnostics;

  /** FAILED 状态下的失败分类码；其余状态为 null。 */
  private String lastErrorCode;

  /** FAILED 状态下的失败描述；其余状态为 null。 */
  private String lastErrorMessage;

  /** 最近一次成功应用时间；从未应用时为 null。 */
  private Instant lastAppliedAt;

  private Instant createTime;
  private Instant updateTime;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment skill source field: " + fieldName);
  }
}
