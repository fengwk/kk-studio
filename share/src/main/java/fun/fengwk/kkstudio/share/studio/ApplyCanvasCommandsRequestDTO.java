package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** {@code POST /api/canvases/{canvasId}/commands} 的 typed command batch。 */
@Data
public class ApplyCanvasCommandsRequestDTO {
  private String expectedRevision;
  private String commandId;
  private List<CanvasCommandDTO> commands = new ArrayList<>();

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
