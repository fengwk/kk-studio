package fun.fengwk.kkstudio.canvas.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.List;

/** Function 目录项：函数名、说明、严格 JSON args schema 与输入/输出限制。 */
@Data
public class CanvasFunctionDefinitionDTO {
  private String name;
  private @Nullable String description;

  /** 严格 JSON Schema object，前端据此渲染参数编辑器；未知关键字在服务端注册期即被拒绝。 */
  private JsonNode argsSchema = JsonNodeFactory.instance.objectNode();

  /** 有界输出计划：启动时按槽位预分配 Resource ID，成功发布时按该顺序原子挂接。 */
  private List<CanvasFunctionOutputDTO> outputs = new ArrayList<>();

  private @Nullable CanvasFunctionReferencePolicyDTO referencePolicy;
  private @Nullable Boolean available;

  /** 目录暴露的版本身份：该函数完整定义的 fingerprint。 */
  private CanvasFunctionVersionDTO version;

  /** required-nullable：available 时必须显式输出 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String unavailableReason;
}
