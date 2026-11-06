package fun.fengwk.kkstudio.platform.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageSource;

import java.time.Instant;
import java.util.List;

/** 技能包详情：当前版本、Skill 清单与 Git 渠道的检查事实。 */
@Data
public class SkillPackageDetailDTO {

  /** 包名。 */
  private String packageName;

  /** 可空包描述。 */
  private @Nullable String description;

  /** 当前版本号。 */
  private String packageVersion;

  /** 当前版本发布时间。 */
  private Instant publishedAt;

  /** 从当前版本归档读出的 Skill 清单（按 name 升序）。 */
  private List<SkillManifestEntryDTO> skills;

  /** 发布渠道。 */
  private SkillPackageSource source;

  /** Git 渠道的仓库事实；上传渠道为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable SkillGitSourceDTO git;

  /** Git 渠道最近一次检查到的 branch HEAD；上传渠道为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String headCommit;

  /** Git 渠道最近一次检查时间；上传渠道为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable Instant headCheckedAt;

  /** Git 渠道最近一次检查失败的摘要；成功或上传渠道为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String headCheckError;

  /** Git 渠道是否存在比当前版本更新的已检查 HEAD。 */
  private boolean updateAvailable;

  /** 当前版本归档的小写十六进制 SHA-256。 */
  private String artifactSha256;

  /** 当前版本归档的字节大小。 */
  private long artifactSizeBytes;

  /** CAS 乐观锁版本号。 */
  private Long version;

  /** 创建时间。 */
  private Instant createdAt;

  /** 最后更新时间。 */
  private Instant updatedAt;
}
