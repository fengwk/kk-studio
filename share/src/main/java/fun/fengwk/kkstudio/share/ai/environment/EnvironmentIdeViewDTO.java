package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** IDE view 响应：标识 view/Environment 与当前目录，并携带受控的短时一次性 entryUrl。 */
@Data
public class EnvironmentIdeViewDTO {

  private String viewId;
  private String environmentId;
  private String directory;

  /** Studio 根相对的短时一次性 bootstrap 入口；CHANGE_DIRECTORY 无需更换 iframe URL 时为 null。 */
  private @Nullable String entryUrl;
}
