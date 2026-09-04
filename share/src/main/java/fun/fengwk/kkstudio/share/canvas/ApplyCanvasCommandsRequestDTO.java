package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /api/canvases/{canvasId}/commands} 的 typed command batch。
 *
 * <p>{@code expectedVersion} 是精确的 graph 版本 CAS 游标，公共契约为规范非负十进制字符串；{@code idempotencyKey}
 * 是整批的幂等键（客户端 UUID）。
 */
@Data
public class ApplyCanvasCommandsRequestDTO {

  private String expectedVersion;

  @JsonSetter("expectedVersion")
  public void setExpectedVersion(Object value) {
    if (value != null && !(value instanceof String)) {
      throw new IllegalArgumentException("expectedVersion must be a JSON string");
    }
    this.expectedVersion = (String) value;
  }

  private String idempotencyKey;

  private List<CanvasCommandDTO> commands = new ArrayList<>();

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
