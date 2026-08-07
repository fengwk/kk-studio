package fun.fengwk.kkstudio.share.comfyui;

import lombok.Data;

/**
 * 可编辑字段：用于创建与更新体。
 *
 * @author fengwk
 */
@Data
public class ComfyuiWorkflowApiEditablePropertiesDTO {

  /** 必填且全局唯一的 API 名（slug）：匹配 {@code ^[a-z][a-z0-9-]{0,63}$}。 */
  private String apiName;

  /** 必填展示名：trim 后非空白且 ≤128 字符。 */
  private String name;

  /** 可空描述：≤512 字符。 */
  private String description;

  /** 必填 ComfyUI API 格式 workflow JSON（持久化为 jsonb 列）。 */
  private String workflowJson;

  /** 必填输入绑定声明 JSON 数组（持久化为 jsonb 列）；元素含 name/kind/nodeId/inputName 等，kind 仅 parameter/file。 */
  private String inputBindingsJson;

  /** 可空默认结果 selector：JSONPath（≤1024 字符，禁止 {@code ..} 与 {@code =~}，必须可被 JsonPath 编译）。 */
  private String defaultSelector;

  /** 是否启用：null 视作 false。 */
  private Boolean enabled;
}
