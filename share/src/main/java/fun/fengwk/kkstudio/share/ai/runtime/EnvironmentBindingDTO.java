package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * 完整 Environment binding 的公开表示：canonical 路由名称 + canonical workspace path。
 *
 * <p>DTO 对象本身可空（null 表示未选择 Environment）；非 null 时两字段都必须提供且经 domain {@code EnvironmentBinding}
 * 严格校验（{@code name} 为 canonical bounded 小写路由名称，{@code workspacePath} 为 Environment Root 下 canonical
 * 相对 wire 路径，{@code '.'} 表示 root）。
 */
@Data
public class EnvironmentBindingDTO {

  /** 必填 canonical bounded 小写 Environment 路由名称（无空白/无 {@code '/'}，≤64 字符）。 */
  private String name;

  /**
   * 必填 Environment Root 下 canonical 相对 wire workspace 路径（{@code '.'} 表示 root，仅用 {@code '/'} 分隔）。
   */
  private String workspacePath;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown environment binding field: " + name);
  }
}
