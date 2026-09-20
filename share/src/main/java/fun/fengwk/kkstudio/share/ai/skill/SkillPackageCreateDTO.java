package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * {@code POST /api/ai/catalog/skill-packages} 请求体：创建 Package 并发布当时的 branch HEAD。
 *
 * <p>服务端读取 {@code branch} 的当前 HEAD，把解析出的 exact commit 作为首个 current commit，并从该 commit 派生
 * manifest；创建请求本身不接收 commit。
 */
@Data
public class SkillPackageCreateDTO {

  /** package 名：非空白、无环绕空白、不含 {@code : / @ \}、≤128，且全局未占用。 */
  private String packageName;

  /** 可空 package 描述；null/空白视为未填写。 */
  private String description;

  /** Git repository URL：必须带 scheme 且不得内嵌 userinfo。 */
  private String repositoryUrl;

  /** 只用于检查候选更新的 branch，也是创建时解析首个 commit 的来源。 */
  private String branch;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill package create field: " + fieldName);
  }
}
