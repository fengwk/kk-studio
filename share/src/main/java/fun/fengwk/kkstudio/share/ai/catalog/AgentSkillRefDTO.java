package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 技能引用：精确标识所属来源及其短名。
 *
 * <p>身份是 {@code (sourceId, name)}，运行时由对应 Environment 的持久 inventory 事实提供。
 *
 * @author fengwk
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentSkillRefDTO {

  /** 所属来源的全局唯一 UUID（小写 canonical UUID 字符串）。 */
  private String sourceId;

  /** Skill canonical 短名（同一 Environment 内全局唯一）。 */
  private String name;

  /** 拒绝未预期的多余字段。 */
  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown agent skill reference field: " + fieldName);
  }
}
