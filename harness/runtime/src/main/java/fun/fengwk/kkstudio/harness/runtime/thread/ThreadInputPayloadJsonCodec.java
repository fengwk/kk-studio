package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.kernel.session.EntryPayload;
import fun.fengwk.kkstudio.harness.kernel.session.EntryType;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInputPayload;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.RuntimeEntryPayloadJsonCodec;

import java.util.Objects;

/**
 * final ThreadInput payload 的严格 canonical codec。
 *
 * <p>Input type 由 durable {@code input_type} 列承载，因此 payload JSON 不再重复 discriminator。配置命令直接编码完整
 * {@link RuntimeConfigSnapshot}；消息命令直接编码可写入 {@code harness_entry} 的最终 payload。委托的两个 codec 均拒绝
 * duplicate field、trailing token、未知字段与错误类型。
 */
public final class ThreadInputPayloadJsonCodec {

  private static final RuntimeConfigJsonCodec CONFIG_CODEC = new RuntimeConfigJsonCodec();
  private static final RuntimeEntryPayloadJsonCodec ENTRY_CODEC =
      new RuntimeEntryPayloadJsonCodec();

  /** 编码与 payload 自报 type 一致的 canonical JSON。 */
  public String encode(ThreadInputPayload payload) {
    Objects.requireNonNull(payload, "payload");
    if (payload instanceof RuntimeConfigInputPayload config) {
      return CONFIG_CODEC.encode(config.snapshot());
    }
    if (payload instanceof RuntimeEntryInputPayload entry) {
      return ENTRY_CODEC.encode(entry.payload());
    }
    throw new IllegalArgumentException(
        "unsupported thread input payload: " + payload.getClass().getName());
  }

  /** 按 durable input type 严格解码 payload。 */
  public ThreadInputPayload decode(ThreadInputType type, String json) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(json, "json");
    return switch (type) {
      case SET_AGENT, SET_MODEL, SET_YOLO -> new RuntimeConfigInputPayload(
          type, CONFIG_CODEC.decode(json));
      case USER_MESSAGE -> decodeUser(json);
      case CUSTOM_MESSAGE -> decodeCustom(json);
    };
  }

  private static RuntimeEntryInputPayload decodeUser(String json) {
    EntryPayload payload = ENTRY_CODEC.decode(EntryType.MESSAGE, json);
    return new RuntimeEntryInputPayload(
        ThreadInputType.USER_MESSAGE, (MessageEntryPayload) payload);
  }

  private static RuntimeEntryInputPayload decodeCustom(String json) {
    EntryPayload payload = ENTRY_CODEC.decode(EntryType.CUSTOM_MESSAGE, json);
    return new RuntimeEntryInputPayload(
        ThreadInputType.CUSTOM_MESSAGE, (CustomMessageEntryPayload) payload);
  }
}
