package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import fun.fengwk.kkstudio.share.ai.runtime.HarnessUserMessageContentDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * Canvas 空 Thread 的原子首次发送（{@code POST /api/canvases/{canvasId}/thread/messages}）： 创建绑定到本画布的 Thread
 * 并一次性发送有序 USER_MESSAGE contents。commandId 是幂等键：同一请求重复提交返回同一 Thread 与 document。
 */
@Data
public class CanvasThreadFirstSendRequestDTO {

  private String commandId;

  private CanvasThreadBranchSettingsDTO branchSettings;

  private Boolean yoloEnabled;

  private List<HarnessUserMessageContentDTO> contents = new ArrayList<>();

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
