package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

import java.util.Map;

/** Canvas upload reserve 响应；不暴露 bucket 或对象 key。 */
@Data
public class CanvasUploadReservationDTO {

  private String uploadId;
  private String method;
  private String url;
  private Map<String, String> headers;
  private String expiresAt;
}
