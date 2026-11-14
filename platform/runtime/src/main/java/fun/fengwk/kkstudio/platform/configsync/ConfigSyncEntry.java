package fun.fengwk.kkstudio.platform.configsync;

import fun.fengwk.kkstudio.common.error.DomainException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单条目字段读取器：跟踪已消费键，读取时严格校验类型且不回显值。
 *
 * <p>「已消费键」是未知字段判定的唯一依据：解析器先读完自己认识的字段，再由 {@link #unknownKeys()} 决定条目是否需要整体
 * skip，因此新增字段必须通过这里的读取方法消费，不能直接读 {@code raw}。
 */
final class ConfigSyncEntry {

  private static final String RESOURCE = "config_sync";

  private final Map<String, Object> raw;
  private final String path;
  private final Set<String> consumed = new LinkedHashSet<>();

  ConfigSyncEntry(Map<String, Object> raw, String path) {
    this.raw = raw;
    this.path = path;
  }

  String path() {
    return path;
  }

  Object node(String key) {
    consumed.add(key);
    return raw.get(key);
  }

  boolean contains(String key) {
    return raw.containsKey(key);
  }

  String reqString(String key) {
    String value = optString(key);
    if (value == null) {
      throw DomainException.validation(RESOURCE, path + "." + key + " is required");
    }
    return value;
  }

  /** 必填业务名：非空白且没有前后空白，避免同名条目因不可见空白被当成不同条目。 */
  String reqName(String key) {
    String value = reqString(key);
    if (value.isBlank() || !value.equals(value.strip())) {
      throw DomainException.validation(
          RESOURCE, path + "." + key + " must be non-blank and unpadded");
    }
    return value;
  }

  String optString(String key) {
    consumed.add(key);
    Object value = raw.get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text)) {
      throw DomainException.validation(RESOURCE, path + "." + key + " must be a string");
    }
    return text;
  }

  Long optLong(String key) {
    consumed.add(key);
    Object value = raw.get(key);
    if (value == null) {
      return null;
    }
    return toLong(value, path + "." + key + " must be an integer");
  }

  List<Integer> optIntegerList(String key) {
    consumed.add(key);
    Object value = raw.get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof List<?> list)) {
      throw DomainException.validation(RESOURCE, path + "." + key + " must be a list of integers");
    }
    List<Integer> result = new ArrayList<>(list.size());
    for (Object item : list) {
      result.add(toInt(item, key));
    }
    return result;
  }

  Boolean optBoolean(String key) {
    consumed.add(key);
    Object value = raw.get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof Boolean bool)) {
      throw DomainException.validation(RESOURCE, path + "." + key + " must be a boolean");
    }
    return bool;
  }

  Map<String, String> optStringMap(String key) {
    consumed.add(key);
    Object value = raw.get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw DomainException.validation(RESOURCE, path + "." + key + " must be an object");
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String mapKey)) {
        throw DomainException.validation(RESOURCE, path + "." + key + " keys must be strings");
      }
      Object mapValue = entry.getValue();
      if (mapValue != null && !(mapValue instanceof String)) {
        throw DomainException.validation(RESOURCE, path + "." + key + " values must be strings");
      }
      result.put(mapKey, (String) mapValue);
    }
    return result;
  }

  /** 尚未被任何读取方法消费的键；用于把条目判为「含未声明字段」并整体 skip。 */
  Set<String> unknownKeys() {
    Set<String> unknown = new LinkedHashSet<>(raw.keySet());
    unknown.removeAll(consumed);
    return unknown;
  }

  private int toInt(Object value, String key) {
    if (value == null
        || value instanceof BigDecimal
        || value instanceof Float
        || value instanceof Double) {
      throw DomainException.validation(RESOURCE, path + "." + key + " must contain only integers");
    }
    String message = path + "." + key + " must contain only integers";
    long longValue = toLong(value, message);
    if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
      throw DomainException.validation(RESOURCE, message);
    }
    return (int) longValue;
  }

  /** 整数标量的统一判定：拒绝小数与超出 {@code long} 的 BigInteger，避免静默截断。 */
  private static long toLong(Object value, String message) {
    if (value instanceof BigDecimal || value instanceof Float || value instanceof Double) {
      throw DomainException.validation(RESOURCE, message);
    }
    if (value instanceof BigInteger bigInteger) {
      if (bigInteger.bitLength() > 63) {
        throw DomainException.validation(RESOURCE, message);
      }
      return bigInteger.longValue();
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    throw DomainException.validation(RESOURCE, message);
  }
}
