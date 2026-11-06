package fun.fengwk.kkstudio.platform.pluginhost.dto;

import lombok.Data;

/**
 * {@code POST /api/platform/plugins/{pluginId}/auth/prepare} 请求体：选择要登录的 region。
 *
 * <p>region 必须是该 Plugin {@link PluginAuthKindDTO.DeepLink#regionCandidates()}
 * 声明的候选之一，服务端据此返回固定的公开官方登录链接。
 */
@Data
public class PluginAuthPrepareRequestDTO {

  /** Plugin 声明的 region 候选之一。 */
  private String region;
}
