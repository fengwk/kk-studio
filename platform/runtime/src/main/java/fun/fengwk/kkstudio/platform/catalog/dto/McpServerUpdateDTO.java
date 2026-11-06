package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

/**
 * {@code PUT /api/platform/mcp-servers/{name}} 请求体；{@link #expectedVersion} 必填。
 *
 * <p>name 不可编辑；更新是对 URL、headers、enabled 与 timeout 的全量替换，并把状态重置为 UNVERIFIED。headers 省略或 KEEP
 * 保留现有映射，CLEAR 清空，SET 用携带的 JSON 对象文本整体替换。
 */
@Data
public class McpServerUpdateDTO {

  /** 必填非负十进制字符串；必须与当前 Server 版本一致。 */
  private String expectedVersion;

  /** 必填 Streamable HTTP endpoint URL。 */
  private String url;

  /** 自定义请求 header：省略或 KEEP 保留，CLEAR 清空，SET 用 JSON 对象文本整体替换。 */
  private @Nullable SecretUpdate headers;

  /** 公共启用开关；可空时按 true 处理。 */
  private @Nullable Boolean enabled;

  /** 正整数毫秒超时；可空时取默认值。 */
  private @Nullable Long timeoutMillis;
}
