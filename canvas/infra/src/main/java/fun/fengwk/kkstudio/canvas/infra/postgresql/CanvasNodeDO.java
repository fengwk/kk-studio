package fun.fengwk.kkstudio.canvas.infra.postgresql;

import lombok.Data;

import java.util.UUID;

/** {@code canvas_node} 行映射；{@code functionJson} 是 base shape {@code {name,args}} 的 JSONB 文本。 */
@Data
public class CanvasNodeDO {
  private UUID id;
  private UUID canvasId;
  private String name;
  private Double x;
  private Double y;
  private Double width;
  private Double height;
  private UUID groupId;
  private String functionJson;
}
