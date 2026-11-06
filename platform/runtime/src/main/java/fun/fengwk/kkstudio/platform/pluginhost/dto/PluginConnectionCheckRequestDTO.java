package fun.fengwk.kkstudio.platform.pluginhost.dto;

import lombok.Data;

/**
 * {@code POST /api/platform/plugins/{pluginId}/check-connection} 请求体：只携带期望版本。
 *
 * <p>{@code expectedVersion} 是本节点已应用的该插件配置版本，canonical 非负十进制字符串，不匹配即
 * 409。请求体不携带任何让调用方指定远端地址、路径或凭据的字段。
 */
@Data
public class PluginConnectionCheckRequestDTO {

  /** 期望的该插件已应用配置版本（canonical 非负十进制字符串）。 */
  private String expectedVersion;
}
