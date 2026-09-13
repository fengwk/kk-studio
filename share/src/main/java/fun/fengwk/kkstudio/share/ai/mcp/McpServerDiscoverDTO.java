package fun.fengwk.kkstudio.share.ai.mcp;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * {@code POST /api/ai/mcp-servers/{id}/discover} 请求体；{@link #expectedVersion} 必填。
 *
 * @author fengwk
 */
@Data
public class McpServerDiscoverDTO {

  /** 必填非负十进制字符串；必须与当前 Server 版本一致。 */
  private String expectedVersion;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown mcp server discover field: " + fieldName);
  }
}
