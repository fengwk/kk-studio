package fun.fengwk.kkstudio.canvas.function;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonArray;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 启动时冻结的 Canvas Function 定义与 adapter 注册目录，键是全局唯一的函数名。 */
public final class CanvasFunctionCatalog {

  private static final Comparator<CanvasResourceKind> KIND_ORDER = Comparator.comparing(Enum::name);

  private final Map<String, RegisteredFunction> byName;
  private final List<RegisteredFunction> ordered;

  private CanvasFunctionCatalog(
      Map<String, RegisteredFunction> byName, List<RegisteredFunction> ordered) {
    this.byName = Map.copyOf(byName);
    this.ordered = List.copyOf(ordered);
  }

  public static CanvasFunctionCatalog from(Collection<? extends CanvasFunctionAdapter> adapters) {
    Objects.requireNonNull(adapters, "adapters");
    Map<String, RegisteredFunction> registered = new LinkedHashMap<>();
    for (CanvasFunctionAdapter adapter : adapters) {
      Objects.requireNonNull(adapter, "adapter");
      List<CanvasFunctionDefinition> functions =
          List.copyOf(Objects.requireNonNull(adapter.functions(), "adapter.functions"));
      if (functions.isEmpty()) {
        throw new IllegalArgumentException(
            "CanvasFunctionAdapter must declare at least one function");
      }
      for (CanvasFunctionDefinition function : functions) {
        Objects.requireNonNull(function, "adapter.functions contains null");
      }
      boolean enabled = adapter.enabled();
      String unavailableReason = adapter.unavailableReason();
      for (CanvasFunctionDefinition function : functions) {
        RegisteredFunction entry =
            new RegisteredFunction(function, adapter, enabled, unavailableReason);
        if (registered.putIfAbsent(function.name(), entry) != null) {
          throw new IllegalArgumentException("duplicate Canvas Function name: " + function.name());
        }
      }
    }
    List<RegisteredFunction> ordered = new ArrayList<>(registered.values());
    ordered.sort(Comparator.comparing(value -> value.function().name()));
    return new CanvasFunctionCatalog(registered, ordered);
  }

  public List<RegisteredFunction> list() {
    return ordered;
  }

  public RegisteredFunction require(String name) {
    RegisteredFunction registered = byName.get(name);
    if (registered == null) {
      throw new IllegalArgumentException("unknown Canvas Function: " + name);
    }
    return registered;
  }

  /**
   * 完整函数定义的 canonical 摘要：逐字段按固定顺序喂入 SHA-256。文本字段以 UTF-8 字节长度前缀消歧，argsSchema 递归按对象键字典序后取自身 canonical
   * 文本，最后输出 64 位小写 hex。
   *
   * <p>它是目录与执行接受共用的同一事实：客户端从目录拿到的 fingerprint 与 start 时对「即将执行的冻结定义」重算的结果必须一致，任何定义内容变化都会换掉指纹。
   *
   * <p>摘要刻意不把完整定义重新包装成一个更大的 JSON 文档：包装会让原本合法的最大 argsSchema 超出 {@link CanvasJson#MAX_LENGTH}
   * 而被拒绝。逐字段 digest 与每个字段自身的预算一致，因此任何可注册的定义都一定能取到指纹。
   */
  public static String definitionFingerprint(CanvasFunctionDefinition definition) {
    Objects.requireNonNull(definition, "definition");
    MessageDigest digest = sha256();
    updateText(digest, definition.name());
    updateText(digest, definition.description());
    updateText(digest, sortObjectKeys(definition.argsSchema()).write());
    updateCount(digest, definition.outputs().size());
    for (CanvasFunctionOutputSpec output : definition.outputs()) {
      updateText(digest, output.kind().name());
      updateNullableText(digest, output.name());
    }
    CanvasFunctionReferencePolicy policy = definition.referencePolicy();
    List<CanvasResourceKind> allowedKinds = new ArrayList<>(policy.allowedKinds());
    allowedKinds.sort(KIND_ORDER);
    updateCount(digest, allowedKinds.size());
    for (CanvasResourceKind kind : allowedKinds) {
      updateText(digest, kind.name());
    }
    updateCount(digest, policy.maxReferences());
    Map<CanvasResourceKind, Integer> maxByKind = new TreeMap<>(policy.maxByKind());
    updateCount(digest, maxByKind.size());
    for (Map.Entry<CanvasResourceKind, Integer> entry : maxByKind.entrySet()) {
      updateText(digest, entry.getKey().name());
      updateCount(digest, entry.getValue());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /** 递归按对象键字典序重建 JSON 树；数组顺序与标量原样保留，因此输入顺序不改变摘要。 */
  private static CanvasJson sortObjectKeys(CanvasJson value) {
    if (value instanceof JsonObject object) {
      Map<String, CanvasJson> ordered = new TreeMap<>();
      for (Map.Entry<String, CanvasJson> entry : object.values().entrySet()) {
        ordered.put(entry.getKey(), sortObjectKeys(entry.getValue()));
      }
      return new JsonObject(ordered);
    }
    if (value instanceof JsonArray array) {
      List<CanvasJson> items = new ArrayList<>(array.values().size());
      for (CanvasJson item : array.values()) {
        items.add(sortObjectKeys(item));
      }
      return new JsonArray(items);
    }
    return value;
  }

  /** 文本字段以字节长度前缀消歧，避免相邻字段拼接出的字节流与另一种字段组合相同。 */
  private static void updateText(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    updateCount(digest, bytes.length);
    digest.update(bytes);
  }

  /** null 输出槽位名（按节点名推导）用不与任何字符串混淆的独立记号。 */
  private static void updateNullableText(MessageDigest digest, String value) {
    if (value == null) {
      updateCount(digest, -1);
      return;
    }
    updateText(digest, value);
  }

  private static void updateCount(MessageDigest digest, int value) {
    digest.update((byte) (value >>> 24));
    digest.update((byte) (value >>> 16));
    digest.update((byte) (value >>> 8));
    digest.update((byte) value);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 must be available", error);
    }
  }

  public record RegisteredFunction(
      CanvasFunctionDefinition function,
      CanvasFunctionAdapter adapter,
      boolean enabled,
      String unavailableReason) {

    public RegisteredFunction {
      Objects.requireNonNull(function, "function");
      Objects.requireNonNull(adapter, "adapter");
      if (enabled && unavailableReason != null) {
        throw new IllegalArgumentException("enabled adapter must not expose unavailableReason");
      }
      if (!enabled && (unavailableReason == null || unavailableReason.isBlank())) {
        throw new IllegalArgumentException("disabled adapter must expose unavailableReason");
      }
    }

    public void requireAvailable() {
      if (!enabled) {
        throw new IllegalArgumentException("Canvas Function is unavailable: " + unavailableReason);
      }
    }
  }
}
