package fun.fengwk.kkstudio.share.ai.mcp;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import lombok.ToString;

/**
 * {@code PUT /api/ai/mcp-servers/{id}} 请求体；{@link #expectedVersion} 必填。
 *
 * <p>{@code name} 与 {@code id} 不可编辑。更新执行配置的完整替换。
 *
 * @author fengwk
 */
@Data
public class McpServerUpdateDTO {

  /** 必填非负十进制字符串；必须与当前 Server 版本一致。 */
  private String expectedVersion;

  /** 必填全量替换配置严格 JSON 文本。 */
  @ToString.Exclude private String configJson;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown mcp server update field: " + fieldName);
  }
}
