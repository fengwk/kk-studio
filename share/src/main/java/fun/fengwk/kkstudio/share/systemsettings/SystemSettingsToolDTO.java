package fun.fengwk.kkstudio.share.systemsettings;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * tool section：权限规则（保序数组）+ 默认 YOLO + 模型/工具 Gateway 与 skill 加载预算。
 *
 * <p>{@code permission} 是 AgentToolId 到有序规则数组的映射；规则数组顺序即求值顺序，必须保持。key 也可使用精确 {@code *} 表示全局规则。
 * action 取值仅为 {@code allow}/{@code ask}/{@code deny}。
 *
 * <p>pattern 语义：带 {@code path} 的目标走 gitignore 语义，bash / 普通 command 候选仍使用简单 wildcard；持久化只接受安全校验子集 （非
 * negation / 非空白 / 非 comment-only 的有效形态）。
 */
@Data
public class SystemSettingsToolDTO {

  private Map<String, List<PermissionRuleDTO>> permission;

  private Boolean defaultYolo;

  private Long modelGatewayBusyRetryMillis;

  private Long toolGatewayBusyRetryMillis;

  private Long toolGatewayOverloadRetryMillis;

  private Long skillLoadTimeoutMillis;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown system settings tool field: " + name);
  }

  /** 单条 permission 规则：pattern + allow/ask/deny action（pattern 语义见外层类注释）。 */
  @Data
  public static class PermissionRuleDTO {

    private String pattern;

    private String action;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException("unknown system settings permission rule field: " + name);
    }
  }
}
