package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

import java.util.List;

/**
 * 本地分支草稿的请求预览入参：草稿尚未落库为 Thread，因此只携带分支起点与命令批，不携带任何 cursor。
 *
 * <p>{@code startEntryId} 是草稿所基于的 source head Entry（canonical UUID string），必须属于 path 上的 Session；
 * {@code commands} 沿用与正式接受完全相同的 {@link HarnessCommandCreateDTO} 严格 wire 格式。
 */
@Data
public class HarnessDraftPreviewRequestDTO {

  /** 草稿分支起点 Entry：canonical UUID string，必须属于目标 Session。 */
  private String startEntryId;

  /** 非空、按产品顺序排列的 SET_* + USER_MESSAGE 命令列表。 */
  private List<HarnessCommandCreateDTO> commands = List.of();

  @JsonSetter("startEntryId")
  public void setStartEntryId(Object value) {
    this.startEntryId = HarnessRuntimeDtoSupport.requireJsonString(value, "startEntryId");
  }

  @JsonSetter("commands")
  public void setCommands(List<HarnessCommandCreateDTO> value) {
    this.commands = value;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown draft preview field: " + name);
  }
}
