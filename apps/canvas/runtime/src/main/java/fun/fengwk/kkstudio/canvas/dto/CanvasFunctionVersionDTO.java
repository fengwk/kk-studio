package fun.fengwk.kkstudio.canvas.dto;

import lombok.Data;

/**
 * 函数目录暴露的版本身份，也是 start 必须回传的 expectedVersion。
 *
 * <p>{@code definitionFingerprint} 是该函数完整定义的 lowercase SHA-256，表达「客户端看到的函数版本」。
 */
@Data
public class CanvasFunctionVersionDTO {

  private String definitionFingerprint;
}
