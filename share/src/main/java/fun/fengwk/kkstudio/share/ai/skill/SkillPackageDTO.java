package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Skill Package 权威投影：不可变仓库身份、人工确认的 exact commit、branch 检查观察值、派生 manifest 与 CAS {@code version}。
 *
 * <p>{@code checkStatus} 是 Card 状态，只由这组事实派生：从未检查为 {@code UNCHECKED}，观察值等于 current 为 {@code
 * UP_TO_DATE}，两者不同为 {@code UPDATE_AVAILABLE}，最近检查错误非空为 {@code CHECK_FAILED}。检查失败始终保留 current commit
 * 与 manifest，Agent 使用不受影响。
 */
@Data
public class SkillPackageDTO {

  /** Package 名（不可变路由身份）：非空白、无环绕空白、不含 {@code : / @ \}、≤128。 */
  private String packageName;

  /** 可空 package 描述；null 表示未填写。 */
  private String description;

  /** 不可变 Git repository URL（带 scheme、无内嵌 userinfo）；更换仓库必须新建 Package。 */
  private String repositoryUrl;

  /** 只用于检查候选更新的 branch；不决定已发布内容。 */
  private String branch;

  /** 人工确认并已发布的 exact Git object id（40 或 64 位小写 hex）。 */
  private String currentCommit;

  /** 最近一次成功检查到的 branch HEAD；从未成功检查时为 null（required-nullable）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String observedHeadCommit;

  /** 最近一次检查时间；从未检查时为 null（required-nullable）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private Instant headCheckedAt;

  /** 最近一次检查的有界错误摘要；检查成功时为 null（required-nullable）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String headCheckError;

  /** Card 状态，取 {@code UNCHECKED / UP_TO_DATE / UPDATE_AVAILABLE / CHECK_FAILED}。 */
  private String checkStatus;

  /** 从 {@code currentCommit} 派生的 Skill manifest，按 name 排序。 */
  private List<SkillManifestEntryDTO> skills;

  /** CAS 乐观锁版本：十进制字符串。 */
  private String version;

  private Instant createTime;

  private Instant updateTime;
}
