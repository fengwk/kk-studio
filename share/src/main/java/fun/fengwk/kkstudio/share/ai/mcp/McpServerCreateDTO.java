package fun.fengwk.kkstudio.share.ai.mcp;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import lombok.ToString;

/**
 * {@code POST /api/ai/mcp-servers} 请求体。
 *
 * <p>以单份配置 JSON 提交连接与传输参数，配置保存时不发起网络或本地进程 I/O。
 *
 * @author fengwk
 */
@Data
public class McpServerCreateDTO {

  /** 必填唯一名：{@code ^[a-z][a-z0-9_]*$} 且 ≤32 字符，创建后不可变。 */
  private String name;

  /** 必填严格 JSON 格式配置文本。 */
  @ToString.Exclude private String configJson;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown mcp server create field: " + fieldName);
  }
}
