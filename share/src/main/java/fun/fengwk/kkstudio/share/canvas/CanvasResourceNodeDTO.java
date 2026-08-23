package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CanvasResourceNodeDTO {
  private String id;
  private String canvasId;
  private String name;
  private CanvasTransformDTO transform;

  /** required-nullable：未分组节点必须显式输出 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String groupId;

  private List<CanvasResourceDTO> resources = new ArrayList<>();

  /** required-nullable：非函数节点必须显式输出 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private CanvasFunctionDTO function;

  /** required-nullable：无运行中/最近运行记录时必须显式输出 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private CanvasFunctionRunDTO run;
}
