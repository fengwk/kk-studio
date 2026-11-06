package fun.fengwk.kkstudio.canvas.dto;

import lombok.Data;

@Data
public class CreateCanvasRequestDTO {

  /** 必填画布标题：trim 后不得为空白，服务端另行拒绝控制字符与超长标题。 */
  private String title;
}
