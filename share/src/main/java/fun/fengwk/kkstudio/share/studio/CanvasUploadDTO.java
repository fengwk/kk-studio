package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

@Data
public class CanvasUploadDTO {
  private String id;
  private String canvasId;
  private String kind;
  private String filename;
  private String declaredMediaType;
  private String declaredSize;
  private String expiresAt;
  private String createdAt;
}
