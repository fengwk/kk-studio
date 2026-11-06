package fun.fengwk.kkstudio.canvas.dto;

import lombok.Data;

import fun.fengwk.kkstudio.canvas.CanvasFunctionUnknownResolution;

/**
 * 人工核查 UNKNOWN Function Run 的严格请求体。
 *
 * <p>{@code resolution} 只能是 RESUME / FAILED / CANCELLED；{@code verification}
 * 是人工核查事实的文本记录，必须非空并会被持久化。
 */
@Data
public class CanvasFunctionUnknownResolutionDTO {

  private String requestId;
  private CanvasFunctionUnknownResolution resolution;
  private String verification;
}
