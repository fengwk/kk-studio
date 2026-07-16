package fun.fengwk.kkstudio.core.comfyui.workflow_api.repo.impl.model;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class ComfyuiWorkflowApiDO {

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
