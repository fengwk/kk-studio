package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;

/** {@code POST /api/platform/mcp-servers/{name}/discover} 请求体：显式发现的 CAS 坐标。 */
@Data
public class McpServerDiscoverDTO {

  /** 必填非负版本号；必须与当前 Server 版本一致。 */
  private Long expectedVersion;
}
