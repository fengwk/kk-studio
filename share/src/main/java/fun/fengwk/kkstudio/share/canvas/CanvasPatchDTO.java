package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次已提交变化的实体 patch，command 响应与事件提示共用。
 *
 * <p>{@code revision} 是服务端接受位置（规范非负十进制字符串），只用于同步排序、补漏与确认接受，不是普通编辑的整图前置版本。 每个实体列表都是本次前进的完整变化集：UPSERT
 * 携带完整投影，REMOVE 只携带身份；重复消息可忽略，revision 有缺口时读取快照。 幂等重放只返回当时记录的 revision 与空变化集，不冒充新的执行状态。
 */
@Data
public class CanvasPatchDTO {

  private String revision;

  private List<CanvasGroupPatchDTO> groups = new ArrayList<>();

  private List<CanvasNodePatchDTO> nodes = new ArrayList<>();

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
