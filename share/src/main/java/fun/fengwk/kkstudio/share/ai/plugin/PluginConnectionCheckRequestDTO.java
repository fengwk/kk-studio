package fun.fengwk.kkstudio.share.ai.plugin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * {@code POST /api/plugins/{pluginId}/connection-check} 请求体：只携带期望坐标。
 *
 * <p>{@code expectedEpoch} 是全局 catalog 修订、{@code expectedRevision} 是该插件的配置修订，两者都是 canonical
 * 非负十进制字符串（仓库既有的 Long wire 约定），不可解析即 400，任何与已应用坐标的不匹配或尚未应用都是 409。请求体不携带任何让调用方指定远端地址、路径或凭据的字段。
 */
@Data
public class PluginConnectionCheckRequestDTO {

  /** 期望的全局 catalog epoch（canonical 非负十进制字符串，必填）；数字/布尔/null 一律拒绝，绝不做标量强制转换。 */
  @JsonDeserialize(using = PluginWireTextDeserializer.class)
  private String expectedEpoch;

  /** 期望的该插件已应用配置修订（canonical 非负十进制字符串，必填）；数字/布尔/null 一律拒绝，绝不做标量强制转换。 */
  @JsonDeserialize(using = PluginWireTextDeserializer.class)
  private String expectedRevision;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown plugin connection check field: " + fieldName);
  }
}
