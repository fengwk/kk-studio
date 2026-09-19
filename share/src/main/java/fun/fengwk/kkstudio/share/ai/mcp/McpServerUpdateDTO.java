package fun.fengwk.kkstudio.share.ai.mcp;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.Map;

/**
 * {@code PUT /api/ai/mcp-servers/{name}} 请求体；{@link #expectedVersion} 必填。
 *
 * <p>name 不可编辑；更新是对 URL、headers、enabled 与 timeout 的全量替换，并把状态重置为 UNVERIFIED。
 *
 * @author fengwk
 */
@Data
public class McpServerUpdateDTO {

  /** 必填非负十进制字符串；必须与当前 Server 版本一致。 */
  private String expectedVersion;

  /** 必填 Streamable HTTP endpoint URL。 */
  private String url;

  /** 自定义请求 header；可空。 */
  private Map<String, String> headers;

  /** 公共启用开关；可空时按 true 处理。 */
  private Boolean enabled;

  /** 正整数毫秒超时；可空时取默认值。 */
  private Long timeoutMillis;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown mcp server update field: " + fieldName);
  }
}
