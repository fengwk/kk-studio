package fun.fengwk.kkstudio.share.ai.skill;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/** 技能包列表项：只包含列表页需要的当前版本摘要。 */
@Data
public class SkillPackageSummaryDTO {

  /** 包名（1–64 个小写字母或数字，以单个连字符分隔）。 */
  private String packageName;

  /** 可空包描述。 */
  private @Nullable String description;

  /** 当前版本号：Git 渠道为 commit SHA，上传渠道为用户填写的文本。 */
  private String packageVersion;

  /** 当前版本发布时间。 */
  private Instant publishedAt;

  /** 当前版本的 Skill 清单（按 name 升序）；Agent 配置的技能候选直接来自这里。 */
  private List<SkillManifestEntryDTO> skills;

  /** 渠道：{@code GIT} 或 {@code UPLOAD}。 */
  private String source;

  /** Git 渠道是否存在比当前版本更新的已检查 HEAD。 */
  private boolean updateAvailable;

  /** CAS 乐观锁版本号（十进制字符串）。 */
  private String version;

  /** 创建时间。 */
  private Instant createTime;

  /** 最后更新时间。 */
  private Instant updateTime;
}
