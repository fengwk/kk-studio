package fun.fengwk.kkstudio.share.ai.chat;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import fun.fengwk.kkstudio.share.ai.runtime.EnvironmentBindingDTO;

/** {@code /api/ai/chat} 的创建请求体。 */
@Data
public class ChatCreateDTO {

  /** 必填聊天标题：trim 后非空白且 ≤256 字符。 */
  private String title;

  /** 必填且必须已存在的 Agent definition 名：无环绕空白、不得包含 {@code '/'}、≤64 字符。 */
  private String agentName;

  /**
   * 可选的默认分支完整 Environment binding（canonical 路由名称 + workspace path）；新空面板/线程草稿以此为起点， 用户发送前可更改或清空。null
   * 表示无默认环境。
   */
  private EnvironmentBindingDTO environment;

  /** 可选的发送权限模式（YOLO）：true 时工具调用跳过权限评估直接 Allow；省略时使用部署级 ToolSettings 的 defaultYolo。 */
  private Boolean yoloEnabled;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown chat create field: " + name);
  }
}
