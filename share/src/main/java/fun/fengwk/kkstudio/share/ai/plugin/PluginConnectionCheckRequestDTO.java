package fun.fengwk.kkstudio.share.ai.plugin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * {@code POST /api/plugins/{pluginId}/connection-check} 请求体：只携带期望坐标。
 *
 * <p>{@code expectedRevision} 是本节点已应用的该插件配置修订，canonical 非负十进制字符串，不可解析即 400，不匹配即
 * 409。请求体不携带任何让调用方指定远端地址、路径或凭据的字段。
 */
@Data
public class PluginConnectionCheckRequestDTO {

  /** 期望的该插件已应用配置修订（canonical 非负十进制字符串，必填）；数字/布尔/null 一律拒绝，绝不做标量强制转换。 */
  @JsonDeserialize(using = PluginWireTextDeserializer.class)
  private String expectedRevision;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown plugin connection check field: " + fieldName);
  }
}
