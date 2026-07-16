package fun.fengwk.kkstudio.core.comfyui.workflow_api.service.model;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * ComfyUI workflow API card 领域实体。
 *
 * @author fengwk
 */
@Data
public class ComfyuiWorkflowApi {

  private Long id;
  private String apiName;
  private String name;
  private String description;
  private String workflowJson;
  private String inputBindingsJson;
  private String defaultSelector;
  private Boolean enabled;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
