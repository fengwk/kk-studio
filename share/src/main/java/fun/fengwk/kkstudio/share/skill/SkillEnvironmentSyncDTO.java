package fun.fengwk.kkstudio.share.skill;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 某个环境对该技能包的安装状态投影。 */
@Data
public class SkillEnvironmentSyncDTO {

  /** 环境 id。 */
  private String environmentId;

  /** Daemon 回报的已安装归档摘要；未安装为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String installedSha256;

  /** Daemon 回报的本地安装根目录；未安装为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String localPath;

  /** 安装失败的去敏摘要；成功为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String error;

  /** 已安装摘要是否等于当前版本归档摘要。 */
  private boolean upToDate;
}
