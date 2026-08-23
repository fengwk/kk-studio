package fun.fengwk.kkstudio.share.canvas;

import lombok.Data;

import java.util.Map;

/** Canvas Resource 直读预签名响应；不暴露 bucket 或对象 key。 */
@Data
public class CanvasPresignedUrlDTO {

  private String method;
  private String url;
  private Map<String, String> headers;
  private String expiresAt;
}
