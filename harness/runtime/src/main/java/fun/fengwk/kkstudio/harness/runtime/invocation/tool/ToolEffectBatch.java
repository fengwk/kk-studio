package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;

import java.util.List;
import java.util.Objects;

/**
 * 一次成功 Tool invocation 产生的 durable branch effects。
 *
 * <p>effects 只描述待由 Harness Core 在 Tool terminal apply 事务中追加的透明 {@link CustomEntryPayload}； Tool
 * 执行端不得直接写 Store。列表顺序即最终 Entry 顺序，单次 invocation 最多 {@value #MAX_CUSTOM_ENTRIES} 条。
 */
public record ToolEffectBatch(List<CustomEntryPayload> customEntries) {

  public static final int MAX_CUSTOM_ENTRIES = 16;
  public static final ToolEffectBatch EMPTY = new ToolEffectBatch(List.of());

  public ToolEffectBatch {
    customEntries = List.copyOf(Objects.requireNonNull(customEntries, "customEntries"));
    if (customEntries.size() > MAX_CUSTOM_ENTRIES) {
      throw new IllegalArgumentException(
          "customEntries must contain at most " + MAX_CUSTOM_ENTRIES + " entries");
    }
    for (CustomEntryPayload customEntry : customEntries) {
      Objects.requireNonNull(customEntry, "customEntries[]");
    }
  }

  public boolean isEmpty() {
    return customEntries.isEmpty();
  }
}
