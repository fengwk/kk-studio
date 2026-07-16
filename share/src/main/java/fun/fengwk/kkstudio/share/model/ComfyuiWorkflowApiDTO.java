package fun.fengwk.kkstudio.share.model;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * ComfyUI workflow API card 响应 DTO。
 *
 * <p>主键在 HTTP / DTO 边界以十进制字符串暴露， 与项目内其它 snowflake 资源（如 AgentDefinition / HarnessSession）的边界约定保持一致；
 * 持久层在 {@link fun.fengwk.kkstudio.core.comfyui.workflow_api.service.model.ComfyuiWorkflowApi} 仍为
 * {@code Long}。
 *
 * @author fengwk
 */
@Data
public class ComfyuiWorkflowApiDTO {

  private String id;
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
