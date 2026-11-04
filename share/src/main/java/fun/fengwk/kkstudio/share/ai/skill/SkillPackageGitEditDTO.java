package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/** 编辑 Git 渠道技能包的描述、检查 branch 与访问令牌；repository URL 不可修改。 */
@Data
public class SkillPackageGitEditDTO {

  /** 客户端已展示的 CAS 版本号（十进制字符串）。 */
  private String expectedVersion;

  /** 可空包描述；null 表示清空。 */
  private @Nullable String description;

  /** 只用于检查候选更新的 branch。 */
  private String branch;

  /** 三态令牌：省略保持、显式 null 清除、非空替换；绝不回显。 */
  @Setter(AccessLevel.NONE)
  @ToString.Exclude
  private @Nullable String token;

  @JsonIgnore
  @Setter(AccessLevel.NONE)
  private boolean tokenProvided;

  /** 区分「未传 token」与「显式清除」：只有 JSON 中出现该字段时才设置 {@code tokenProvided}。 */
  @JsonSetter("token")
  public void applyToken(String token) {
    this.token = token;
    this.tokenProvided = true;
  }

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill package field: " + fieldName);
  }
}
