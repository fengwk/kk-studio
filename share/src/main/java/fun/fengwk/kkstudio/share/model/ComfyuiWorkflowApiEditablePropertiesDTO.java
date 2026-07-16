package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/**
 * 可编辑字段：用于创建与更新体。
 *
 * @author fengwk
 */
@Data
public class ComfyuiWorkflowApiEditablePropertiesDTO {

  private String apiName;
  private String name;
  private String description;
  private String workflowJson;
  private String inputBindingsJson;
  private String defaultSelector;
  private Boolean enabled;
}
