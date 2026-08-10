package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CanvasFunctionParameterDefinitionDTO {
  private String key;
  private String label;
  private String type;
  private Boolean required;
  private Object defaultValue;
  private List<String> options = new ArrayList<>();
  private Integer min;
  private Integer max;
}
