package fun.fengwk.kkstudio.share.ai.mcp;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.Map;

/**
 * {@code POST /api/ai/mcp-servers} 请求体。
 *
 * <p>只接受 Streamable HTTP 传输参数：配置保存不发起任何网络 I/O。headers 值可写 {@code ${VAR}}，由 Backend
 * 在发起请求或发现前从进程环境整值替换。
 *
 * @author fengwk
 */
@Data
public class McpServerCreateDTO {

  /** 必填唯一名：{@code ^[a-z][a-z0-9_]*$} 且 ≤32 字符，创建后不可变。 */
  private String name;

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
    throw new IllegalArgumentException("unknown mcp server create field: " + fieldName);
  }
}
