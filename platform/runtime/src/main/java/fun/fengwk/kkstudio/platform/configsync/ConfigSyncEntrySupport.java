package fun.fengwk.kkstudio.platform.configsync;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncSkipped;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置同步文档的条目级契约：把一类类别读成条目、判定未知字段 skip，以及把写入路径的校验失败收敛为安全错误。
 *
 * <p>「未知字段」永远是条目级 skip，而结构错误、类型错误、非法有效值与缺必填是硬错误：本类只提供判定与收敛的工具， 由各 {@code *Entries}
 * 协作者决定调用顺序，从而让「先校验 known 字段、再判定未知字段」的既有顺序在所有类别上保持一致。
 */
final class ConfigSyncEntrySupport {

  static final String RESOURCE = "config_sync";

  private ConfigSyncEntrySupport() {}

  /** 把文档中某个类别的列表读成条目；缺失表示空列表，类型不符是硬错误。 */
  static List<ConfigSyncEntry> entries(Map<String, Object> document, ConfigSyncKind kind) {
    String key = kind.wireValue();
    if (!document.containsKey(key)) {
      return List.of();
    }
    Object node = document.get(key);
    if (node == null) {
      throw DomainException.validation(RESOURCE, key + " must be a list");
    }
    if (!(node instanceof List<?> list)) {
      throw DomainException.validation(RESOURCE, key + " must be a list");
    }
    List<ConfigSyncEntry> entries = new ArrayList<>(list.size());
    for (int index = 0; index < list.size(); index++) {
      Object element = list.get(index);
      String path = key + "[" + index + "]";
      if (!(element instanceof Map<?, ?> map)) {
        throw DomainException.validation(RESOURCE, path + " must be an object");
      }
      Map<String, Object> raw = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        raw.put(String.valueOf(e.getKey()), e.getValue());
      }
      entries.add(new ConfigSyncEntry(raw, path));
    }
    return entries;
  }

  static Object requireObject(ConfigSyncEntry entry, String key) {
    Object node = entry.node(key);
    if (!(node instanceof Map<?, ?>)) {
      throw DomainException.validation(RESOURCE, entry.path() + "." + key + " must be an object");
    }
    return node;
  }

  /** 嵌套 config 含未声明字段时整体跳过条目；路径可读但不回显字段值。 */
  static boolean reportUnknownConfig(
      List<String> removed, ConfigSyncKind kind, String name, List<ConfigSyncSkipped> skipped) {
    if (removed.isEmpty()) {
      return false;
    }
    skipped.add(new ConfigSyncSkipped(kind, name, "unsupported field: " + removed.get(0)));
    return true;
  }

  static boolean skipUnknown(
      ConfigSyncEntry entry, ConfigSyncKind kind, String name, List<ConfigSyncSkipped> skipped) {
    Set<String> unknown = entry.unknownKeys();
    if (unknown.isEmpty()) {
      return false;
    }
    String first = unknown.iterator().next();
    skipped.add(
        new ConfigSyncSkipped(kind, name, "unsupported field: " + entry.path() + "." + first));
    return true;
  }

  /** 用写入路径的校验器检查条目；底层消息可能含真实值，统一替换为安全的类别与条目名，不携带 cause。 */
  static void validateEntry(ConfigSyncKind kind, String name, Runnable validation) {
    try {
      validation.run();
    } catch (DomainException error) {
      if (error.code() != DomainErrorCode.VALIDATION) {
        throw error;
      }
      throw DomainException.validation(RESOURCE, kind.wireValue() + " entry is invalid: " + name);
    }
  }

  /** 同一文件内的业务名必须唯一；被 skip 的条目同样占用名字，避免依赖借重复名绕过 skip。 */
  static void requireDistinct(List<String> names, ConfigSyncKind kind) {
    Set<String> seen = new HashSet<>();
    for (String name : names) {
      if (!seen.add(name)) {
        throw DomainException.validation(
            RESOURCE, "duplicate " + kind.wireValue() + " entry in file: " + name);
      }
    }
  }
}
