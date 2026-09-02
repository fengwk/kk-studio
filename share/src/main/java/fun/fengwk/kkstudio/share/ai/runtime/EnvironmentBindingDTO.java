package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 完整 Environment binding 的公开表示：canonical EnvironmentId UUID + canonical workspace path。 */
@Data
public class EnvironmentBindingDTO {

  /** 必填 canonical UUID 文本形式的环境 ID。 */
  private String environmentId;

  /**
   * 必填 Environment Root 下 canonical 相对 wire workspace 路径（{@code '.'} 表示 root，仅用 {@code '/'} 分隔）。
   */
  private String workspacePath;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown environment binding field: " + name);
  }
}
