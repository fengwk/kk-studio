package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /api/canvases/{canvasId}/changes?afterVersion=N} 的恢复载荷： 要么是从 afterVersion 起连续的
 * patches（客户端逐个应用），要么是必须整体替换当前状态的 snapshot（gap / 压缩 / 服务端无法增量）。
 */
@Data
public class CanvasChangesDTO {

  private List<CanvasPatchDTO> patches = new ArrayList<>();

  private CanvasSnapshotDTO snapshot;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
