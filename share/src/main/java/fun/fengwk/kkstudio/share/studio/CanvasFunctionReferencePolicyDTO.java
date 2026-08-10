package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class CanvasFunctionReferencePolicyDTO {
  private List<String> allowedKinds = new ArrayList<>();
  private Integer maxReferences;
  private Map<String, Integer> maxByKind = new LinkedHashMap<>();
}
