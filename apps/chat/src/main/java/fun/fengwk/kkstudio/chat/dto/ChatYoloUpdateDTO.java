package fun.fengwk.kkstudio.chat.dto;

import lombok.Data;

/** 独立更新 Chat 的发送权限模式，并校验版本。 */
@Data
public class ChatYoloUpdateDTO {
  private boolean yoloEnabled;
  private String expectedVersion;
}
