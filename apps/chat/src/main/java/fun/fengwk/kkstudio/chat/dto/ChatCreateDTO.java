package fun.fengwk.kkstudio.chat.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** Chat 的创建请求体。 */
@Data
public class ChatCreateDTO {

  /** 必填聊天标题：trim 后非空白且 ≤256 字符。 */
  private String title;

  /** 必填且必须已存在的 Agent definition 名：无环绕空白、不得包含 {@code '/'}、≤64 字符。 */
  private String agentName;

  /** 可选默认 Environment name（nullable）：省略/null 表示无默认环境；提供时须为已存在的 Environment 名。 */
  private @Nullable String environmentName;

  /** 可选的发送权限模式（YOLO）：true 时工具调用跳过权限评估直接 Allow；省略/null 时为 false。 */
  private @Nullable Boolean yoloEnabled;
}
