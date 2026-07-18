package fun.fengwk.kkstudio.core.comfyui.workflow_api.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code comfyui_workflow_api} 行映射：ComfyUI 工作流卡片。 */
@Data
public class ComfyuiWorkflowApiDO {

  /** 业务主键。 */
  private Long id;

  /** 对外 api 名（小写字母开头，仅含 [a-z0-9-]）。 */
  private String apiName;

  /** 展示名。 */
  private String name;

  /** 描述。 */
  private String description;

  /** ComfyUI API 格式 workflow JSON。 */
  private String workflowJson;

  /** 输入绑定 JSON 数组。 */
  private String inputBindingsJson;

  /** 默认结果 JSONPath selector。 */
  private String defaultSelector;

  /** 是否启用。 */
  private Boolean enabled;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;

  /** 更新时间（映射 {@code gmt_modified}）。 */
  private LocalDateTime updateTime;
}
