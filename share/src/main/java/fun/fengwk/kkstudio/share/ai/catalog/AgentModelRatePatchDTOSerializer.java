package fun.fengwk.kkstudio.share.ai.catalog;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 只输出补丁中实际声明的字段：未声明的 reasoning 保持缺失，显式 null 才表示回到继承输出。
 *
 * <p>普通 Jackson 会把 {@code reasoning} 字段的 null 一律写成 JSON null，从而把“不修改”误变成“重置为继承”。
 */
public final class AgentModelRatePatchDTOSerializer
    extends ValueSerializer<AgentModelRatePatchDTO> {

  @Override
  public void serialize(
      AgentModelRatePatchDTO value, JsonGenerator generator, SerializationContext context) {
    generator.writeStartObject();
    writeDecimal(generator, "input", value.getInput());
    writeDecimal(generator, "output", value.getOutput());
    writeDecimal(generator, "cacheRead", value.getCacheRead());
    writeDecimal(generator, "cacheWrite", value.getCacheWrite());
    if (value.getCacheWriteByTtl() != null) {
      generator.writeName("cacheWriteByTtl");
      generator.writeStartObject();
      for (Map.Entry<String, BigDecimal> entry : value.getCacheWriteByTtl().entrySet()) {
        generator.writeName(entry.getKey());
        generator.writeString(entry.getValue().toPlainString());
      }
      generator.writeEndObject();
    }
    if (value.isReasoningSpecified()) {
      generator.writeName("reasoning");
      if (value.getReasoning() == null) {
        generator.writeNull();
      } else {
        generator.writeString(value.getReasoning().toPlainString());
      }
    }
    generator.writeEndObject();
  }

  private static void writeDecimal(JsonGenerator generator, String name, BigDecimal value) {
    if (value != null) {
      generator.writeName(name);
      generator.writeString(value.toPlainString());
    }
  }
}
