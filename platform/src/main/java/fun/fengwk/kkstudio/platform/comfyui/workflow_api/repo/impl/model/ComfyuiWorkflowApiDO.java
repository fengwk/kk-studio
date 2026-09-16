package fun.fengwk.kkstudio.platform.comfyui.workflow_api.repo.impl.model;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code comfyui_workflow_api} 行映射：ComfyUI 工作流卡片。 */
@Data
public class ComfyuiWorkflowApiDO {

  /** 业务主键（PostgreSQL uuid，由应用生成）。 */
  private UUID id;

  /** 对外 api 名：正则 {@code ^[a-z][a-z0-9-]{0,63}$}（小写字母开头，仅含 [a-z0-9-]，最长 64），唯一索引约束。 */
  private String apiName;

  /** 展示名，必填：非空白且不超过 128 字符。 */
  private String name;

  /** 描述，可选：映射 text 列，null 表示未填写。 */
  private String description;

  /** ComfyUI API 格式 workflow JSON（jsonb，必填），运行时可被 {@code Workflow.fromApiJson} 解析。 */
  private String workflowJson;

  /**
   * 输入绑定 JSON 数组（jsonb，必填）：数组项含 name / kind / nodeId / inputName 等字段，kind ∈ parameter / file，name
   * 唯一。
   */
  private String inputBindingsJson;

  /** 默认结果 JSONPath selector，可选：运行时校验并预编译，空时返回规范化完整结果。 */
  private String defaultSelector;

  /** 是否启用（必填）：运行时查找只匹配 enabled = true 的卡片。 */
  private Boolean enabled;

  /** 创建时间（映射 {@code created_at} timestamptz，毫秒精度）。 */
  private OffsetDateTime createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz，毫秒精度）。 */
  private OffsetDateTime updateTime;
}
