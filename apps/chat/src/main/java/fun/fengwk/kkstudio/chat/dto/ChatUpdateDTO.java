package fun.fengwk.kkstudio.chat.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 整体替换 Chat 的标题、Agent 与默认环境，并校验版本。 */
@Data
public class ChatUpdateDTO {
  private String title;
  private String agentName;
  private @Nullable String environmentName;
  private Long expectedVersion;
}
