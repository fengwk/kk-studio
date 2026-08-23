package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonInclude;
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

  /** required-nullable：available 时必须显式输出 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String unavailableReason;
}
