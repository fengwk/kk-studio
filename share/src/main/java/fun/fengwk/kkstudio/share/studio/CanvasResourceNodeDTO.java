package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CanvasResourceNodeDTO {
  private String id;
  private String canvasId;
  private String name;
  private CanvasTransformDTO transform;
  private String groupId;
  private List<CanvasResourceDTO> resources = new ArrayList<>();
  private CanvasFunctionDTO function;
  private CanvasFunctionRunDTO run;
}
