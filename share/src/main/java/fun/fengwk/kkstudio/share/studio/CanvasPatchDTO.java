package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 幂等 graph patch：command 响应、changes 回放与 SSE 恢复统一使用它。 baseVersion -> version 表示一次连续前进；version <=
 * 客户端当前版本时忽略，baseVersion != 客户端当前版本时视为 gap，必须通过 changes 恢复。两者 wire 均为规范非负十进制字符串。
 */
@Data
public class CanvasPatchDTO {

  private String baseVersion;

  private String version;

  private List<CanvasGroupPatchDTO> groups = new ArrayList<>();

  private List<CanvasNodePatchDTO> nodes = new ArrayList<>();

  private List<CanvasLinkPatchDTO> links = new ArrayList<>();

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
