package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** ResourceNode 上可选的资源生产配置。 */
public record CanvasFunctionDTO(String modelKey, String configJson) {

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
