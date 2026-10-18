package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 函数目录暴露的版本身份，也是 start 必须回传的 expectedVersion。
 *
 * <p>{@code definitionFingerprint} 是该函数完整定义的 lowercase SHA-256，表达「客户端看到的函数版本」；只接受 JSON 字符串。
 */
@Data
public class CanvasFunctionVersionDTO {

  @JsonDeserialize(using = CanvasFunctionVersionTextDeserializer.class)
  private String definitionFingerprint;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
