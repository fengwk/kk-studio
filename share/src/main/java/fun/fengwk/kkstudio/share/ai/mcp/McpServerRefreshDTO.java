package fun.fengwk.kkstudio.share.ai.mcp;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * {@code POST /api/ai/mcp-servers/{id}/refresh} 请求体；{@link #expectedVersion} 必填。
 *
 * @author fengwk
 */
@Data
public class McpServerRefreshDTO {

  /** 必填非负十进制字符串；必须与当前 Server 版本一致。 */
  private String expectedVersion;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown mcp server refresh field: " + name);
  }
}
