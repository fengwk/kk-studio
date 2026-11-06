package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;

import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

/**
 * {@code PUT /api/platform/mcp-servers/{name}} 请求体；{@link #expectedVersion} 必填。
 *
 * <p>name 不可编辑；更新是对 URL、headers、enabled 与 timeout 的全量替换，并把状态重置为 UNVERIFIED。全部字段必填：headers 显式 KEEP
 * 保留现有映射、CLEAR 清空、SET 用携带的 JSON 对象文本整体替换；enabled 与 timeout 按请求值写入，不补默认值。
 */
@Data
public class McpServerUpdateDTO {

  /** 必填非负十进制字符串；必须与当前 Server 版本一致。 */
  private String expectedVersion;

  /** 必填 Streamable HTTP endpoint URL。 */
  private String url;

  /** 必填自定义请求 header 更新：KEEP 保留、CLEAR 清空、SET 用 JSON 对象文本整体替换。 */
  private SecretUpdate headers;

  /** 必填公共启用开关。 */
  private Boolean enabled;

  /** 必填正整数毫秒超时。 */
  private Long timeoutMillis;
}
