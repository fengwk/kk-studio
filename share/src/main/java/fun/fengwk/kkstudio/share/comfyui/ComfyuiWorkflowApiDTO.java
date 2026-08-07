package fun.fengwk.kkstudio.share.comfyui;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * ComfyUI workflow API card 响应 DTO。
 *
 * <p>主键在 HTTP / DTO 边界以正的十进制字符串暴露，与项目内其它 durable 资源（如 AgentDefinition / HarnessSession）的边界约定保持一致；
 * 持久层在 {@link fun.fengwk.kkstudio.core.comfyui.workflow_api.service.model.ComfyuiWorkflowApi} 仍为
 * {@code Long}。
 *
 * @author fengwk
 */
@Data
public class ComfyuiWorkflowApiDTO {

  /** workflow API card 主键：正十进制字符串（底层 bigint，由数据库序列分配）。 */
  private String id;

  /** 必填且全局唯一的 API 名（slug）：匹配 {@code ^[a-z][a-z0-9-]{0,63}$}。 */
  private String apiName;

  /** 必填展示名：trim 后非空白且 ≤128 字符。 */
  private String name;

  /** 可空描述：≤512 字符。 */
  private String description;

  /** 必填 ComfyUI API 格式 workflow JSON（持久化为 jsonb 列）。 */
  private String workflowJson;

  /** 必填输入绑定声明 JSON 数组（持久化为 jsonb 列）；元素含 name/kind(nodeId/inputName 等，kind 仅 parameter/file）。 */
  private String inputBindingsJson;

  /** 可空默认结果 selector：JSONPath（≤1024 字符，禁止 {@code ..} 与 {@code =~}，必须可被 JsonPath 编译）。 */
  private String defaultSelector;

  /** 是否启用：null 视作 false；运行期只允许引用已启用卡片。 */
  private Boolean enabled;

  /** 创建时间（UTC LocalDateTime，映射 timestamptz）。 */
  private LocalDateTime createTime;

  /** 更新时间（UTC LocalDateTime，映射 timestamptz）。 */
  private LocalDateTime updateTime;
}
