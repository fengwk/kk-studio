package fun.fengwk.kkstudio.platform.catalog.skill.archive;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 规范化 ZIP 归档的条目索引：{@code {"entries":[{"path","size","dataOffset","compressedSize","crc32"}]}}。
 *
 * <p>索引只由服务端读取最终生成的 ZIP 得出，保存在 {@code storage_blob.archive_index}；读取单个文件时按 {@code dataOffset} +
 * {@code compressedSize} 做范围读取，只解压目标条目。解码对未知字段、重复路径与非法取值一律 fail-closed。
 */
public record SkillArchiveIndex(List<Entry> entries) {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private static final TypeReference<SkillArchiveIndex> TYPE = new TypeReference<>() {};

  private static final Pattern CRC32 = Pattern.compile("^[0-9a-f]{8}$");

  public SkillArchiveIndex {
    Objects.requireNonNull(entries, "entries");
    if (entries.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("archive index entries must not contain null");
    }
    Set<String> paths = new HashSet<>();
    for (Entry entry : entries) {
      if (!paths.add(entry.path())) {
        throw new IllegalArgumentException("duplicate archive index path: " + entry.path());
      }
    }
  }

  /** 归档中的单个文件条目。 */
  public record Entry(String path, long size, long dataOffset, long compressedSize, String crc32) {

    public Entry {
      if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")) {
        throw new IllegalArgumentException("invalid archive entry path: " + path);
      }
      for (String segment : path.split("/", -1)) {
        if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
          throw new IllegalArgumentException("invalid archive entry path: " + path);
        }
      }
      if (size < 0 || dataOffset < 0 || compressedSize <= 0) {
        throw new IllegalArgumentException("invalid archive entry range: " + path);
      }
      if (crc32 == null || !CRC32.matcher(crc32).matches()) {
        throw new IllegalArgumentException("invalid archive entry crc32: " + path);
      }
    }
  }

  /** 从构建器读出的条目构造索引；crc32 以 8 位小写 hex 保存。 */
  public static SkillArchiveIndex from(List<SkillArchiveBuilder.IndexEntry> index) {
    Objects.requireNonNull(index, "index");
    return new SkillArchiveIndex(
        index.stream()
            .map(
                entry ->
                    new Entry(
                        entry.path(),
                        entry.size(),
                        entry.dataOffset(),
                        entry.compressedSize(),
                        String.format(Locale.ROOT, "%08x", entry.crc32())))
            .toList());
  }

  /** 编码为 jsonb 值。 */
  public String encode() {
    try {
      return MAPPER.writeValueAsString(this);
    } catch (JacksonException error) {
      throw new IllegalStateException("archive index cannot be serialized", error);
    }
  }

  /** 严格解码 jsonb 值。 */
  public static SkillArchiveIndex decode(String json) {
    if (json == null) {
      throw new IllegalArgumentException("archive index is null");
    }
    try {
      SkillArchiveIndex index = MAPPER.readValue(json, TYPE);
      if (index == null || index.entries() == null) {
        throw new IllegalArgumentException("archive index is invalid");
      }
      return index;
    } catch (JacksonException error) {
      throw new IllegalArgumentException("archive index is invalid", error);
    }
  }

  /** 按路径查找条目；不存在返回 null。 */
  public Entry find(String path) {
    for (Entry entry : entries) {
      if (entry.path().equals(path)) {
        return entry;
      }
    }
    return null;
  }
}
