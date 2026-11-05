package fun.fengwk.kkstudio.share.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * 编辑技能包的描述与 Git 渠道设置；上传渠道只能改描述。
 *
 * <p>{@code branch} 省略表示保持当前检查分支；{@code token} 为三态：省略保持、显式 null 清除、非空替换。上传渠道传入任一 Git 字段都会被拒绝。
 */
@Data
public class SkillPackageEditDTO {

  /** 客户端已展示的 CAS 版本号（十进制字符串）。 */
  private String expectedVersion;

  /** 可空包描述；null 表示清空。 */
  private @Nullable String description;

  /** Git 渠道的检查分支；省略表示保持当前值，上传渠道禁止提供。 */
  private @Nullable String branch;

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
