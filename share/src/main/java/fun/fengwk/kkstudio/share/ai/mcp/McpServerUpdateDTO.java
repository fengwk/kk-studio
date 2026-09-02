package fun.fengwk.kkstudio.share.ai.mcp;

import lombok.Data;

/**
 * {@code PUT /api/ai/mcp-servers/{id}} 请求体；{@link #expectedVersion} 必填。
 *
 * <p>{@code name} 与 {@code id} 不是可编辑字段。{@code bearerToken} 是显式三态：{@code null} 保留现有值、空字符串清除、非空
 * 替换——序列化时被省略的字段与显式 {@code null} 语义一致，请求体不存在该字段即保留。
 *
 * @author fengwk
 */
@Data
public class McpServerUpdateDTO {

  /** 可选新 endpoint URL；null 表示不修改。 */
  private String url;

  /** Bearer token 三态更新（见类注释）。 */
  private String bearerToken;

  /** 可选新超时（正整数毫秒）；null 表示不修改。 */
  private Long timeoutMillis;

  /** 必填非负十进制字符串；必须与当前 Server 版本一致。 */
  private String expectedVersion;
}
