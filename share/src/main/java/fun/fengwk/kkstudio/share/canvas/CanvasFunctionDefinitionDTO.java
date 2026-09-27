package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Function 目录项：函数名、说明、严格 JSON args schema 与输入/输出限制。 */
@Data
public class CanvasFunctionDefinitionDTO {
  private String name;
  private String description;

  /** 严格 JSON Schema object，前端据此渲染参数编辑器；未知关键字在服务端注册期即被拒绝。 */
  private Map<String, Object> argsSchema = new LinkedHashMap<>();

  /** 有界输出计划：启动时按槽位预分配 Resource ID，成功发布时按该顺序原子挂接。 */
  private List<CanvasFunctionOutputDTO> outputs = new ArrayList<>();

  private CanvasFunctionReferencePolicyDTO referencePolicy;
  private Boolean available;

  /** required-nullable：available 时必须显式输出 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String unavailableReason;
}
