package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CanvasFunctionModelDTO {
  private String key;
  private String label;
  private String outputKind;
  private CanvasFunctionReferencePolicyDTO referencePolicy;
  private List<CanvasFunctionParameterDefinitionDTO> parameters = new ArrayList<>();
  private Boolean available;
  private String unavailableReason;
}
