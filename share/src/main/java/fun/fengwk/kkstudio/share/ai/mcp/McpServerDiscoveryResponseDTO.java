package fun.fengwk.kkstudio.share.ai.mcp;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationDTO;

/**
 * MCP Server 显式发现响应 DTO。
 *
 * <p>对 Remote Server 返回同步完成后的安全 Server 投影且 operation 为 null；对 Local Server 返回当前安全 Server 投影以及创建的 异步
 * {@link EnvironmentOperationDTO}。
 *
 * @author fengwk
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpServerDiscoveryResponseDTO {

  /** 安全 Server 投影。 */
  private McpServerDTO server;

  /** 可空的异步 Environment 管理操作（Local 发现时非空，Remote 发现时为 null）。 */
  private EnvironmentOperationDTO operation;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown mcp server discovery response field: " + fieldName);
  }
}
