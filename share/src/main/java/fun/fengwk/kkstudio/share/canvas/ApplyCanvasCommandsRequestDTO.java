package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;

/**
 * {@code POST /api/canvases/{canvasId}/commands} 的 typed command batch。
 *
 * <p>{@code idempotencyKey} 是整批的幂等键（客户端 UUID）。重试沿用同一个键与同一批命令：服务端只返回当时记录的接受位置，
 * 不重新执行，也不把新运行状态冒充旧请求结果；同一键换成不同请求指纹会被拒绝。批内命令的前置条件来自编辑起点，服务端不接受整张旧快照覆盖。
 */
@Data
public class ApplyCanvasCommandsRequestDTO {

  private String idempotencyKey;

  private List<CanvasCommandDTO> commands;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
