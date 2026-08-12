package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 首次发送响应：绑定后的 Thread id 与携带 threadId 的最新 document。 */
@Data
public class CanvasThreadFirstSendResponseDTO {

  private String threadId;

  private CanvasDocumentDTO document;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
