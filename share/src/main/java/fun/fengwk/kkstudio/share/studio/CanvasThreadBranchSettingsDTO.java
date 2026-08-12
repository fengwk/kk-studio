package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Canvas Thread 的 branch settings 快照。与 harness runtime 的 HarnessBranchSettingsDTO 同构， model 选择是冻结的
 * provider/model/variant 三元组。
 */
@Data
public class CanvasThreadBranchSettingsDTO {

  private String environmentName;

  private String agentName;

  private CanvasThreadModelSelectionDTO model;

  private List<String> activeTools = new ArrayList<>();

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }

  /** 冻结的 provider/model/variant 三元组。 */
  @Data
  public static class CanvasThreadModelSelectionDTO {

    private String providerName;

    private String modelName;

    private String variant;

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
      throw new IllegalArgumentException("unknown field: " + field);
    }
  }
}
