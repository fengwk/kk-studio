package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * Package 编辑请求体中的单个 Skill 定义。
 *
 * <p>这是客户端提交的原始内容：{@code contentRevision} 由服务端按 {@code content} 的精确 UTF-8 字节计算，绝不接受客户端提供的 revision。
 */
@Data
public class SkillDefinitionDTO {

  /** Skill canonical 名（同一 package 内唯一，全局跨 package 唯一）：非空白、无环绕空白、单行、≤128。 */
  private String name;

  /** Skill 描述（必填、非空、≤1024，允许 LF 换行）。 */
  private String description;

  /** Skill 正文（必填、非空，长度不设上限）；contentRevision 由服务端据此计算。 */
  private String content;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill definition field: " + fieldName);
  }
}
