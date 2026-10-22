package fun.fengwk.kkstudio.share.canvas;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class CanvasFunctionReferencePolicyDTO {
  private List<String> allowedKinds = new ArrayList<>();
  private @Nullable Integer maxReferences;
  private @Nullable Map<String, Integer> maxByKind = new LinkedHashMap<>();
}
