package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 函数目录暴露的版本身份，也是 start 必须回传的 expectedVersion。
 *
 * <p>{@code catalogEpoch} 是 canonical 非负十进制字符串（Java long 的 wire 形式，避免 JS 丢精度）；{@code
 * definitionFingerprint} 是该函数完整定义的 lowercase SHA-256。两者一起表达「客户端看到的函数版本」。
 *
 * <p>两个字段都只接受 JSON 字符串：数字、布尔与 null 一律拒绝，绝不把它们强制转换成文本。
 */
@Data
public class CanvasFunctionVersionDTO {

  @JsonDeserialize(using = CanvasFunctionVersionTextDeserializer.class)
  private String catalogEpoch;

  @JsonDeserialize(using = CanvasFunctionVersionTextDeserializer.class)
  private String definitionFingerprint;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
