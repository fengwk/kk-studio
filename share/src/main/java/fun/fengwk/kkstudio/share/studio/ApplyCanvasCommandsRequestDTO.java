package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /api/canvases/{canvasId}/commands} 的 typed command batch。
 *
 * <p>{@code expectedVersion} 是精确的 graph 版本 CAS 游标，公共契约为规范非负十进制字符串（JSON number 经 Jackson coercion
 * 兼容接受，服务端严格校验后转 long）；{@code commandId} 是整批的幂等键（客户端 UUID）。
 */
@Data
public class ApplyCanvasCommandsRequestDTO {

  private String expectedVersion;

  private String commandId;

  private List<CanvasCommandDTO> commands = new ArrayList<>();

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
