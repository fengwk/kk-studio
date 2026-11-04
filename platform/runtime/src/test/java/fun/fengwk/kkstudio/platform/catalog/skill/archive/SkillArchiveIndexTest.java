package fun.fengwk.kkstudio.platform.catalog.skill.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** {@link SkillArchiveIndex} 严格编解码与条目校验的单元测试。 */
class SkillArchiveIndexTest {

  @Test
  void shouldEncodeAndStrictlyDecode() {
    SkillArchiveIndex index =
        new SkillArchiveIndex(
            List.of(
                new SkillArchiveIndex.Entry("dev/SKILL.md", 100L, 50L, 80L, "0000abcd"),
                new SkillArchiveIndex.Entry("dev/run.sh", 200L, 150L, 120L, "12345678")));

    String json = index.encode();
    assertNotNull(json);

    SkillArchiveIndex decoded = SkillArchiveIndex.decode(json);
    assertEquals(2, decoded.entries().size());
    SkillArchiveIndex.Entry first = decoded.find("dev/SKILL.md");
    assertNotNull(first);
    assertEquals("dev/SKILL.md", first.path());
    assertEquals(100L, first.size());
    assertEquals(50L, first.dataOffset());
    assertEquals(80L, first.compressedSize());
    assertEquals("0000abcd", first.crc32());

    SkillArchiveIndex.Entry second = decoded.find("dev/run.sh");
    assertNotNull(second);
    assertEquals("dev/run.sh", second.path());

    assertNull(decoded.find("missing/path"));
  }

  @Test
  void shouldRejectDecodeOnNullMalformedOrUnknownProperties() {
    assertThrows(IllegalArgumentException.class, () -> SkillArchiveIndex.decode(null));
    assertThrows(IllegalArgumentException.class, () -> SkillArchiveIndex.decode(""));
    assertThrows(IllegalArgumentException.class, () -> SkillArchiveIndex.decode("{invalid-json}"));
    assertThrows(IllegalArgumentException.class, () -> SkillArchiveIndex.decode("{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillArchiveIndex.decode("{\"entries\":[],\"unexpectedField\":\"value\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SkillArchiveIndex.decode(
                "{\"entries\":[{\"path\":\"a\",\"size\":1,\"dataOffset\":0,\"compressedSize\":1,\"crc32\":\"12345678\"}],\"entries\":[]}"));
  }

  @Test
  void shouldRejectDuplicatePathsInIndex() {
    SkillArchiveIndex.Entry entry1 =
        new SkillArchiveIndex.Entry("dup/path.txt", 10L, 0L, 10L, "12345678");
    SkillArchiveIndex.Entry entry2 =
        new SkillArchiveIndex.Entry("dup/path.txt", 20L, 20L, 20L, "abcdef01");

    assertThrows(
        IllegalArgumentException.class, () -> new SkillArchiveIndex(List.of(entry1, entry2)));
  }

  @Test
  void shouldRejectNullEntriesOrElements() {
    assertThrows(NullPointerException.class, () -> new SkillArchiveIndex(null));
    SkillArchiveIndex.Entry valid =
        new SkillArchiveIndex.Entry("ok/path.txt", 10L, 0L, 10L, "12345678");
    List<SkillArchiveIndex.Entry> listWithNull = new ArrayList<>();
    listWithNull.add(valid);
    listWithNull.add(null);
    assertThrows(IllegalArgumentException.class, () -> new SkillArchiveIndex(listWithNull));
  }

  @Test
  void shouldRejectInvalidEntryPaths() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry(null, 10L, 0L, 10L, "12345678"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("", 10L, 0L, 10L, "12345678"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("/leading/slash", 10L, 0L, 10L, "12345678"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("back\\slash", 10L, 0L, 10L, "12345678"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("double//slash", 10L, 0L, 10L, "12345678"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("dot/./segment", 10L, 0L, 10L, "12345678"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("dotdot/../segment", 10L, 0L, 10L, "12345678"));
  }

  @Test
  void shouldRejectInvalidEntryRangeAndCrc() {
    // Negative size
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("valid/path", -1L, 0L, 10L, "12345678"));
    // Negative dataOffset
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("valid/path", 10L, -1L, 10L, "12345678"));
    // Non-positive compressedSize (<= 0)
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("valid/path", 10L, 0L, 0L, "12345678"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("valid/path", 10L, 0L, -5L, "12345678"));

    // Invalid CRC32: null, wrong length, uppercase, non-hex
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("valid/path", 10L, 0L, 10L, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("valid/path", 10L, 0L, 10L, "1234567"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("valid/path", 10L, 0L, 10L, "123456789"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("valid/path", 10L, 0L, 10L, "1234ABCD"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillArchiveIndex.Entry("valid/path", 10L, 0L, 10L, "1234567g"));
  }

  @Test
  void shouldCreateFromArchiveBuilderIndexEntries() {
    SkillArchiveBuilder.IndexEntry builderEntry =
        new SkillArchiveBuilder.IndexEntry("dev/SKILL.md", 10L, 20L, 42L, 0x12345678L);
    SkillArchiveIndex index = SkillArchiveIndex.from(List.of(builderEntry));
    assertEquals(1, index.entries().size());
    SkillArchiveIndex.Entry entry = index.entries().get(0);
    assertEquals("dev/SKILL.md", entry.path());
    assertEquals(42L, entry.size());
    assertEquals(10L, entry.dataOffset());
    assertEquals(20L, entry.compressedSize());
    assertEquals("12345678", entry.crc32());
  }
}
