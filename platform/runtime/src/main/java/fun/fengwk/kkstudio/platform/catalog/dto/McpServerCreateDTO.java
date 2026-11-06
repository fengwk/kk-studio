package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

/**
 * {@code POST /api/platform/mcp-servers} 请求体。
 *
 * <p>只接受 Streamable HTTP 传输参数：配置保存不发起任何网络 I/O。headers 作为整体秘密提交，{@code SET} 的值是 header 映射的 JSON
 * 对象文本，值可写 {@code ${VAR}}，由 Backend 在发起请求或发现前从进程环境整值替换。
 */
@Data
public class McpServerCreateDTO {

  /** 必填唯一名：{@code ^[a-z][a-z0-9_]*$} 且 ≤32 字符，创建后不可变。 */
  private String name;

  /** 必填 Streamable HTTP endpoint URL。 */
  private String url;

  /** 自定义请求 header：省略或 KEEP 表示无 header，CLEAR 同 KEEP，SET 携带 JSON 对象文本。 */
  private @Nullable SecretUpdate headers;

  /** 公共启用开关；可空时按 true 处理。 */
  private @Nullable Boolean enabled;

  /** 正整数毫秒超时；可空时取默认值。 */
  private @Nullable Long timeoutMillis;
}
