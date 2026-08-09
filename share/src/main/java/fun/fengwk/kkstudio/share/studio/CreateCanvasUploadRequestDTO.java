package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** {@code POST /api/canvases/{canvasId}/uploads} 请求。 */
@Data
public class CreateCanvasUploadRequestDTO {

  private String kind;
  private String filename;
  private String mediaType;
  private String size;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
