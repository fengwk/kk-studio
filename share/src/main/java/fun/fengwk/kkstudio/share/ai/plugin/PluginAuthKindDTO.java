package fun.fengwk.kkstudio.share.ai.plugin;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Plugin 认证交互类型：一个封闭的 wire union，当前只有 {@code DEEP_LINK}。
 *
 * <p>管理面不接受任意 Plugin JSON schema。新增认证交互必须先扩展这个 sealed type、共享 DTO 与静态前端，不能从 JAR 下载或执行动态 UI 代码。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(@JsonSubTypes.Type(value = PluginAuthKindDTO.DeepLink.class, name = "DEEP_LINK"))
public sealed interface PluginAuthKindDTO permits PluginAuthKindDTO.DeepLink {

  /**
   * 固定深链登录：用户在浏览器完成登录后把回调 deep link 粘贴回来，Platform 只接收 {@code callbackUrl}，不接收任何 token 参数。
   *
   * @param regionCandidates Plugin 声明的固定 region 候选（非空、去重）；prepare 请求只能选其中之一， loginUrl 只会是该 region
   *     的公开官方地址
   */
  record DeepLink(List<String> regionCandidates) implements PluginAuthKindDTO {}
}
