package fun.fengwk.kkstudio.canvas.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class CanvasFunctionReferencePolicyDTO {
  private List<CanvasResourceKind> allowedKinds = new ArrayList<>();
  private @Nullable Integer maxReferences;
  private @Nullable Map<String, Integer> maxByKind = new LinkedHashMap<>();
}
