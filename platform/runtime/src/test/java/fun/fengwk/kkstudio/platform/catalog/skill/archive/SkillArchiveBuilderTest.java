package fun.fengwk.kkstudio.platform.catalog.skill.archive;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder.InputEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder.Type;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

class SkillArchiveBuilderTest {
  private static final byte[] MANIFEST =
      "---\nname: demo\ndescription: |\n  hello\n  world\n---\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] SCRIPT = "echo hi\n".getBytes(StandardCharsets.UTF_8);
  private static final List<InputEntry> INPUT =
      List.of(
          new InputEntry("wrap/demo/SKILL.md", Type.FILE, false, MANIFEST),
          new InputEntry("wrap/demo/run", Type.FILE, true, SCRIPT),
          new InputEntry("wrap/ignored/readme", Type.FILE, false, SCRIPT));

  @Test
  void zipTarAndGitProduceSameBytesAndRangeIndex() throws Exception {
    // Equivalent inputs have the same canonical bytes independent of source metadata.
    SkillArchiveBuilder builder = new SkillArchiveBuilder();
    var git = builder.fromEntries(INPUT);
    assertArrayEquals(git.zip(), builder.fromZip(new ByteArrayInputStream(zip(INPUT))).zip());
    assertArrayEquals(git.zip(), builder.fromTarGz(new ByteArrayInputStream(tar(INPUT))).zip());
    assertEquals(List.of("ignored"), git.ignoredDirectories());
    assertEquals("hello\nworld", git.skills().get(0).description());
    try (ZipFile file =
        ZipFile.builder()
            .setSeekableByteChannel(new SeekableInMemoryByteChannel(git.zip()))
            .get()) {
      assertEquals(040755, file.getEntry("demo/").getUnixMode());
      assertEquals(0100644, file.getEntry("demo/SKILL.md").getUnixMode());
      assertEquals(0100755, file.getEntry("demo/run").getUnixMode());
      assertEquals(1980, file.getEntry("demo/run").getTimeLocal().getYear());
    }
    for (var item : git.index()) {
      try (var range =
              new ByteArrayInputStream(
                  git.zip(), (int) item.dataOffset(), (int) item.compressedSize());
          var stream = new InflaterInputStream(range, new Inflater(true))) {
        byte[] contents = stream.readAllBytes();
        CRC32 crc = new CRC32();
        crc.update(contents);
        assertEquals(item.size(), contents.length);
        assertEquals(item.crc32(), crc.getValue());
      }
    }
  }

  @Test
  void rejectsInvalidPathsConflictsTypesAndLimits() throws Exception {
    // Malicious paths and resource exhaustion are rejected before archive publication.
    SkillArchiveBuilder builder = new SkillArchiveBuilder();
    for (String path : List.of("../bad", "/bad", "C:/bad", "a//b", "a/../b", "a\\b")) {
      assertThrows(
          IOException.class,
          () -> builder.fromEntries(List.of(new InputEntry(path, Type.FILE, false, SCRIPT))));
    }
    for (String path : List.of("x/CON.txt", "x/file.", "x/a:b", "x/a?b")) {
      assertThrows(
          IOException.class,
          () -> builder.fromEntries(List.of(new InputEntry(path, Type.FILE, false, SCRIPT))));
    }
    assertThrows(
        IOException.class,
        () ->
            builder.fromEntries(
                List.of(
                    new InputEntry("Demo/file", Type.FILE, false, SCRIPT),
                    new InputEntry("demo/other", Type.FILE, false, SCRIPT))));
    assertThrows(
        IOException.class,
        () ->
            builder.fromZip(
                new ByteArrayInputStream(
                    zip(List.of(new InputEntry("../bad", Type.FILE, false, SCRIPT))))));
    assertThrows(IOException.class, () -> builder.fromEntries(List.of(INPUT.get(0), INPUT.get(0))));
    assertThrows(
        IOException.class,
        () -> builder.fromZip(new ByteArrayInputStream(zip(List.of(INPUT.get(0), INPUT.get(0))))));
    assertThrows(
        IOException.class,
        () ->
            builder.fromEntries(
                List.of(
                    new InputEntry("x", Type.FILE, false, SCRIPT),
                    new InputEntry("x/y", Type.FILE, false, SCRIPT))));
    for (Type type : List.of(Type.LINK, Type.SPECIAL, Type.SUBMODULE)) {
      assertThrows(
          IOException.class,
          () ->
              builder.fromEntries(
                  List.of(
                      new InputEntry("demo/SKILL.md", Type.FILE, false, MANIFEST),
                      new InputEntry("demo/bad", type, false, new byte[0]))));
    }
    assertThrows(IOException.class, () -> builder.fromEntries(List.of()));
    assertThrows(
        IOException.class,
        () ->
            builder.fromEntries(
                List.of(new InputEntry("INVALID/SKILL.md", Type.FILE, false, MANIFEST))));
    var small = new SkillArchiveBuilder(new SkillArchiveBuilder.Limits(10, 20, 2, 10));
    assertThrows(IOException.class, () -> small.fromZip(new ByteArrayInputStream(new byte[11])));
    assertThrows(IOException.class, () -> small.fromEntries(INPUT));
    assertThrows(
        IOException.class,
        () -> small.fromEntries(List.of(new InputEntry("a", Type.FILE, false, new byte[11]))));
  }

  @Test
  void validatesManifestRootsAndTimezone() throws Exception {
    // Determinism must not depend on the server's default timezone.
    var builder = new SkillArchiveBuilder();
    var entries =
        List.of(
            new InputEntry("demo/SKILL.md", Type.FILE, false, MANIFEST),
            new InputEntry("demo/.git/config", Type.FILE, false, SCRIPT),
            new InputEntry("extra/file", Type.FILE, false, SCRIPT));
    var previous = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
      var first = builder.fromEntries(entries);
      assertEquals(List.of("extra"), first.ignoredDirectories());
      assertEquals(1, first.index().size());
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
      assertArrayEquals(first.zip(), builder.fromEntries(entries).zip());
    } finally {
      TimeZone.setDefault(previous);
    }
    for (String yaml :
        List.of(
            "---\nname: other\ndescription: text\n---\n",
            "---\nname: demo\ndescription: ' '\n---\n",
            "---\nname: demo\ndescription: " + "a".repeat(1025) + "\n---\n")) {
      assertThrows(
          IOException.class,
          () ->
              builder.fromEntries(
                  List.of(
                      new InputEntry(
                          "demo/SKILL.md",
                          Type.FILE,
                          false,
                          yaml.getBytes(StandardCharsets.UTF_8)))));
    }
  }

  @Test
  void rejectsArchiveLinksAndSizeBoundaries() throws Exception {
    // Exercise link metadata in both actual upload formats, not just Git entry types.
    var zipBytes = new ByteArrayOutputStream();
    try (var zip = new ZipArchiveOutputStream(zipBytes)) {
      var manifest = new ZipArchiveEntry("demo/SKILL.md");
      zip.putArchiveEntry(manifest);
      zip.write(MANIFEST);
      zip.closeArchiveEntry();
      var link = new ZipArchiveEntry("demo/link");
      link.setUnixMode(0120777);
      zip.putArchiveEntry(link);
      zip.write(SCRIPT);
      zip.closeArchiveEntry();
    }
    var tarBytes = new ByteArrayOutputStream();
    try (var tar = new TarArchiveOutputStream(new GZIPOutputStream(tarBytes))) {
      var manifest = new TarArchiveEntry("demo/SKILL.md");
      manifest.setSize(MANIFEST.length);
      tar.putArchiveEntry(manifest);
      tar.write(MANIFEST);
      tar.closeArchiveEntry();
      var link = new TarArchiveEntry("demo/link", TarConstants.LF_LINK);
      link.setLinkName("target");
      tar.putArchiveEntry(link);
      tar.closeArchiveEntry();
    }
    var builder = new SkillArchiveBuilder();
    assertThrows(
        IOException.class, () -> builder.fromZip(new ByteArrayInputStream(zipBytes.toByteArray())));
    assertThrows(
        IOException.class,
        () -> builder.fromTarGz(new ByteArrayInputStream(tarBytes.toByteArray())));
    var totalLimited = new SkillArchiveBuilder(new SkillArchiveBuilder.Limits(10000, 10, 20, 9));
    assertThrows(
        IOException.class,
        () ->
            totalLimited.fromEntries(
                List.of(
                    new InputEntry("a", Type.FILE, false, SCRIPT),
                    new InputEntry("b", Type.FILE, false, SCRIPT))));
    var countLimited = new SkillArchiveBuilder(new SkillArchiveBuilder.Limits(10000, 100, 2, 100));
    assertThrows(
        IOException.class,
        () ->
            countLimited.fromEntries(
                List.of(
                    new InputEntry("a", Type.FILE, false, SCRIPT),
                    new InputEntry("b", Type.FILE, false, SCRIPT),
                    new InputEntry("c", Type.FILE, false, SCRIPT))));
  }

  @Test
  void rejectsMalformedContentAndDirectoryConflicts() throws Exception {
    // Input limits also cover implicit directories and non-file payloads.
    var builder = new SkillArchiveBuilder();
    assertThrows(
        IOException.class,
        () -> builder.fromEntries(List.of(new InputEntry("a", Type.DIRECTORY, false, SCRIPT))));
    assertThrows(
        IOException.class,
        () ->
            builder.fromEntries(
                List.of(
                    new InputEntry("a/b", Type.FILE, false, SCRIPT),
                    new InputEntry("a", Type.FILE, false, SCRIPT))));
    assertThrows(
        IOException.class,
        () ->
            builder.fromEntries(
                List.of(
                    new InputEntry("demo/SKILL.md", Type.FILE, false, new byte[] {(byte) 0xff}))));
    var limited = new SkillArchiveBuilder(new SkillArchiveBuilder.Limits(100000, 1, 1, 100));
    assertThrows(
        IOException.class,
        () -> limited.fromEntries(List.of(new InputEntry("a/b", Type.FILE, false, new byte[0]))));
    var zip = zip(List.of(new InputEntry("demo/SKILL.md", Type.FILE, false, MANIFEST)));
    try (var file =
        ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(zip)).get()) {
      var entry = file.getEntry("demo/SKILL.md");
      zip[(int) entry.getDataOffset()] ^= 0x7f;
    }
    assertThrows(IOException.class, () -> builder.fromZip(new ByteArrayInputStream(zip)));
  }

  @Test
  void acceptsSmallZip64AndRejectsForgedCounts() throws Exception {
    // Preflight must handle ZIP64 and cannot trust its declared entry count.
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipArchiveOutputStream(bytes)) {
      zip.setUseZip64(Zip64Mode.Always);
      zip.putArchiveEntry(new ZipArchiveEntry("demo/SKILL.md"));
      zip.write(MANIFEST);
      zip.closeArchiveEntry();
    }
    var builder = new SkillArchiveBuilder();
    assertEquals(1, builder.fromZip(new ByteArrayInputStream(bytes.toByteArray())).skills().size());
    byte[] ordinary = zip(List.of(new InputEntry("demo/SKILL.md", Type.FILE, false, MANIFEST)));
    ordinary[ordinary.length - 14] = 0;
    ordinary[ordinary.length - 12] = 0;
    assertThrows(IOException.class, () -> builder.fromZip(new ByteArrayInputStream(ordinary)));
  }

  @Test
  void boundsTarPaddingEvenAfterEndMarker() throws Exception {
    // A compressed stream can inflate arbitrarily even without any tar files.
    var bytes = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(bytes)) {
      gzip.write(new byte[12000]);
    }
    var builder = new SkillArchiveBuilder(new SkillArchiveBuilder.Limits(10000, 1, 1, 1));
    assertThrows(
        IOException.class, () -> builder.fromTarGz(new ByteArrayInputStream(bytes.toByteArray())));
  }

  @Test
  void limitsCanonicalZipDuringWriting() throws Exception {
    // Incompressible content can exceed the compressed-output limit although input limits pass.
    byte[] noise = new byte[4096];
    new Random(42).nextBytes(noise);
    var entries =
        List.of(
            new InputEntry("demo/SKILL.md", Type.FILE, false, MANIFEST),
            new InputEntry("demo/noise", Type.FILE, false, noise));
    int size = new SkillArchiveBuilder().fromEntries(entries).zip().length;
    assertTrue(size > 4096);
    var exact = new SkillArchiveBuilder(new SkillArchiveBuilder.Limits(size, 10000, 10, 5000));
    assertEquals(size, exact.fromEntries(entries).zip().length);
    var limited =
        new SkillArchiveBuilder(new SkillArchiveBuilder.Limits(size - 1, 10000, 10, 5000));
    IOException error = assertThrows(IOException.class, () -> limited.fromEntries(entries));
    assertTrue(error.getMessage().contains("Output ZIP size limit"), error.getMessage());
    var duringPayload =
        new SkillArchiveBuilder(new SkillArchiveBuilder.Limits(512, 10000, 10, 5000));
    assertThrows(IOException.class, () -> duringPayload.fromEntries(entries));
  }

  @Test
  void normalizesTarDotPathsAndRejectsAliases() throws Exception {
    // Common tar root and directory entries normalize to the same tree as Git objects.
    var builder = new SkillArchiveBuilder();
    var entries =
        List.of(
            new InputEntry("./", Type.DIRECTORY, false, new byte[0]),
            new InputEntry("./demo/", Type.DIRECTORY, false, new byte[0]),
            new InputEntry("./demo/./SKILL.md", Type.FILE, false, MANIFEST));
    assertArrayEquals(
        builder
            .fromEntries(List.of(new InputEntry("demo/SKILL.md", Type.FILE, false, MANIFEST)))
            .zip(),
        builder.fromTarGz(new ByteArrayInputStream(tar(entries))).zip());
    for (var duplicate :
        List.of(
            List.of(
                new InputEntry("./", Type.DIRECTORY, false, new byte[0]),
                new InputEntry(".", Type.DIRECTORY, false, new byte[0])),
            List.of(
                new InputEntry("./demo/SKILL.md", Type.FILE, false, MANIFEST),
                new InputEntry("demo/SKILL.md", Type.FILE, false, MANIFEST)),
            List.of(
                new InputEntry("./demo/", Type.DIRECTORY, false, new byte[0]),
                new InputEntry("demo", Type.DIRECTORY, false, new byte[0])))) {
      assertThrows(IOException.class, () -> builder.fromEntries(duplicate));
      assertThrows(
          IOException.class, () -> builder.fromTarGz(new ByteArrayInputStream(tar(duplicate))));
    }
    assertThrows(
        IOException.class,
        () ->
            builder.fromEntries(
                List.of(new InputEntry("./demo/../../escape", Type.FILE, false, SCRIPT))));
    assertThrows(
        IOException.class,
        () -> builder.fromEntries(List.of(new InputEntry("demo/.", Type.FILE, false, SCRIPT))));
    assertThrows(
        IOException.class,
        () ->
            builder.fromZip(
                new ByteArrayInputStream(
                    zip(
                        List.of(
                            new InputEntry("./demo/SKILL.md", Type.FILE, false, MANIFEST),
                            new InputEntry("demo/SKILL.md", Type.FILE, false, MANIFEST))))));
  }

  @Test
  void manifestErrorsIncludeFullPathAndReason() {
    // Invalid UTF-8 and scanner errors must identify the original skill file.
    var builder = new SkillArchiveBuilder();
    for (byte[] content :
        List.of(
            new byte[] {(byte) 0xff},
            "---\nname: wrong\ndescription: text\n---\n".getBytes(StandardCharsets.UTF_8),
            "---\nname: demo\ndescription: ' '\n---\n".getBytes(StandardCharsets.UTF_8))) {
      IOException error =
          assertThrows(
              IOException.class,
              () ->
                  builder.fromEntries(
                      List.of(new InputEntry("wrapper/demo/SKILL.md", Type.FILE, false, content))));
      assertTrue(error.getMessage().contains("wrapper/demo/SKILL.md"), error.getMessage());
      assertNotNull(error.getCause());
      assertTrue(error.getMessage().contains(error.getCause().getMessage()), error.getMessage());
    }
  }

  @Test
  void skippingDecompressedBytesConsumesTheSameLimit() throws Exception {
    // skip must not bypass the limit even when the underlying stream implements skip directly.
    try (var stream =
        new SkillArchiveBuilder.BoundedStream(new ByteArrayInputStream(new byte[5]), 3)) {
      assertEquals(2, stream.skip(2));
      assertEquals(0, stream.read());
      assertThrows(IOException.class, () -> stream.skip(1));
    }
  }

  private static byte[] zip(List<InputEntry> inputs) throws IOException {
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipArchiveOutputStream(out)) {
      for (var item : inputs) {
        var entry = new ZipArchiveEntry(item.path());
        entry.setUnixMode(item.executable() ? 0100755 : 0100644);
        zip.putArchiveEntry(entry);
        zip.write(item.content());
        zip.closeArchiveEntry();
      }
    }
    return out.toByteArray();
  }

  private static byte[] tar(List<InputEntry> inputs) throws IOException {
    var out = new ByteArrayOutputStream();
    try (var tar = new TarArchiveOutputStream(new GZIPOutputStream(out))) {
      for (var item : inputs) {
        var entry =
            new TarArchiveEntry(
                item.path()
                    + (item.type() == Type.DIRECTORY && !item.path().endsWith("/") ? "/" : ""));
        entry.setMode(item.executable() ? 0755 : 0644);
        entry.setSize(item.content().length);
        tar.putArchiveEntry(entry);
        tar.write(item.content());
        tar.closeArchiveEntry();
      }
    }
    return out.toByteArray();
  }
}
