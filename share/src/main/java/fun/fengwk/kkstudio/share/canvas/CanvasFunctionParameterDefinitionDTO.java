package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CanvasFunctionParameterDefinitionDTO {
  private String key;
  private String label;
  private String type;
  private Boolean required;

  /** required-nullable：无默认值时必须显式输出 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private Object defaultValue;

  private List<String> options = new ArrayList<>();

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private Integer min;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private Integer max;
}
