package fun.fengwk.kkstudio.platform.catalog.skill.archive;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillNames;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitException;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillManifestScanner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

/**
 * Bounded, in-memory construction of a canonical skill archive. No input path is written to disk.
 */
public final class SkillArchiveBuilder {
  /**
   * Bounds derived from the measured local sample; Git readers must enforce them before loading.
   */
  public static final long MAX_ARCHIVE_BYTES = 64L << 20;

  public static final long MAX_TOTAL_BYTES = 128L << 20;
  public static final int MAX_PATHS = 5000;
  public static final long MAX_FILE_BYTES = 16L << 20;

  private static final Limits DEFAULTS =
      new Limits(MAX_ARCHIVE_BYTES, MAX_TOTAL_BYTES, MAX_PATHS, MAX_FILE_BYTES);
  private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);
  private final Limits limits;

  public SkillArchiveBuilder() {
    this(DEFAULTS);
  }

  SkillArchiveBuilder(Limits limits) {
    this.limits = limits;
  }

  record Limits(long compressed, long total, int entries, long file) {}

  public enum Type {
    FILE,
    DIRECTORY,
    LINK,
    SUBMODULE,
    SPECIAL
  }

  /**
   * Git callers supply object contents, not checked-out filesystem paths. Before reading any
   * object, callers must use its object metadata to enforce {@link #MAX_FILE_BYTES}, {@link
   * #MAX_TOTAL_BYTES} and {@link #MAX_PATHS}. This builder cannot bound allocations already made by
   * its caller.
   */
  public record InputEntry(String path, Type type, boolean executable, byte[] content) {}

  public record IndexEntry(
      String path, long dataOffset, long compressedSize, long size, long crc32) {}

  public record Result(
      byte[] zip,
      List<SkillManifestEntry> skills,
      List<String> ignoredDirectories,
      List<IndexEntry> index) {}

  public Result fromZip(InputStream input) throws IOException {
    byte[] bytes = readBounded(input, limits.compressed(), "ZIP");
    checkZipDirectory(bytes);
    Accumulator acc = new Accumulator();
    try (ZipFile zip =
        ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get()) {
      Enumeration<ZipArchiveEntry> entries = zip.getEntries();
      while (entries.hasMoreElements()) {
        ZipArchiveEntry entry = entries.nextElement();
        int mode = entry.getUnixMode();
        int kind = mode & 0170000;
        Type type =
            kind == 0120000
                ? Type.LINK
                : entry.isDirectory() && (kind == 0 || kind == 0040000)
                    ? Type.DIRECTORY
                    : !entry.isDirectory() && (kind == 0 || kind == 0100000)
                        ? Type.FILE
                        : Type.SPECIAL;
        if (!zip.canReadEntryData(entry)) {
          throw new IOException("Unsupported ZIP entry: " + entry.getName());
        }
        byte[] data;
        try (InputStream stream = zip.getInputStream(entry)) {
          data = readBounded(stream, limits.file(), entry.getName());
        }
        CRC32 crc = new CRC32();
        crc.update(data);
        if (entry.getSize() != data.length || entry.getCrc() != crc.getValue()) {
          throw new IOException("Invalid ZIP size or CRC: " + entry.getName());
        }
        acc.add(new InputEntry(entry.getName(), type, (mode & 0111) != 0, data));
      }
    }
    return build(acc);
  }

  // ZipFile materializes the central directory before exposing entries. Count it first so a
  // small archive with a huge number of empty entries cannot exhaust memory during construction.
  private void checkZipDirectory(byte[] bytes) throws IOException {
    int end = -1;
    for (int p = bytes.length - 22; p >= Math.max(0, bytes.length - 65557); p--) {
      if (u32(bytes, p) == 0x06054b50L && p + 22L + u16(bytes, p + 20) == bytes.length) {
        end = p;
        break;
      }
    }
    if (end < 0 || u16(bytes, end + 4) != 0 || u16(bytes, end + 6) != 0) {
      throw new IOException("Invalid or multi-disk ZIP");
    }
    long declared = u16(bytes, end + 10);
    long start = u32(bytes, end + 16);
    long size = u32(bytes, end + 12);
    long directoryEnd = end;
    if ((end >= 20 && u32(bytes, end - 20) == 0x07064b50L)
        || declared == 0xffff
        || start == 0xffffffffL
        || size == 0xffffffffL) {
      int locator = end - 20;
      if (locator < 0
          || u32(bytes, locator) != 0x07064b50L
          || u32(bytes, locator + 4) != 0
          || u32(bytes, locator + 16) != 1) {
        throw new IOException("Invalid ZIP64 locator");
      }
      long record = u64(bytes, locator + 8);
      if (record < 0 || record > locator - 56L) {
        throw new IOException("Invalid ZIP64 offset");
      }
      int z = (int) record;
      long recordSize = u64(bytes, z + 4);
      if (u32(bytes, z) != 0x06064b50L
          || recordSize < 44
          || recordSize != locator - record - 12
          || u32(bytes, z + 16) != 0
          || u32(bytes, z + 20) != 0
          || u64(bytes, z + 24) != u64(bytes, z + 32)) {
        throw new IOException("Invalid ZIP64 directory");
      }
      declared = u64(bytes, z + 32);
      size = u64(bytes, z + 40);
      start = u64(bytes, z + 48);
      directoryEnd = record;
    } else if (declared != u16(bytes, end + 8)) {
      throw new IOException("Invalid ZIP directory count");
    }
    if (declared < 0
        || declared > limits.entries()
        || size < 0
        || start < 0
        || start > directoryEnd
        || size != directoryEnd - start) {
      throw new IOException("Invalid or oversized ZIP directory");
    }
    int p = (int) start;
    int count = 0;
    while (p < directoryEnd) {
      if (directoryEnd - p < 46 || u32(bytes, p) != 0x02014b50L) {
        throw new IOException("Invalid ZIP directory entry");
      }
      long next = p + 46L + u16(bytes, p + 28) + u16(bytes, p + 30) + u16(bytes, p + 32);
      if (next > directoryEnd || ++count > limits.entries()) {
        throw new IOException("ZIP directory limit exceeded");
      }
      p = (int) next;
    }
    if (count != declared) {
      throw new IOException("Invalid ZIP directory count");
    }
  }

  private static int u16(byte[] bytes, int offset) {
    return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8;
  }

  private static long u32(byte[] bytes, int offset) {
    return (u16(bytes, offset) & 0xffffL) | (long) u16(bytes, offset + 2) << 16;
  }

  private static long u64(byte[] bytes, int offset) {
    return u32(bytes, offset) | u32(bytes, offset + 4) << 32;
  }

  public Result fromTarGz(InputStream input) throws IOException {
    byte[] bytes = readBounded(input, limits.compressed(), "tar.gz");
    Accumulator acc = new Accumulator();
    // Bound headers, PAX metadata and padding too, not just regular file payloads.
    try (InputStream gzip =
            GzipCompressorInputStream.builder()
                .setInputStream(new ByteArrayInputStream(bytes))
                .setDecompressConcatenated(true)
                .get();
        InputStream bounded = new BoundedStream(gzip, limits.total() + limits.entries() * 8192L);
        TarArchiveInputStream tar = new TarArchiveInputStream(bounded)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        Type type =
            entry.isDirectory()
                ? Type.DIRECTORY
                : entry.isSymbolicLink() || entry.isLink()
                    ? Type.LINK
                    : entry.isFile() && !entry.isSparse() ? Type.FILE : Type.SPECIAL;
        if (!entry.isCheckSumOK() || !tar.canReadEntryData(entry)) {
          throw new IOException("Invalid tar entry: " + entry.getName());
        }
        byte[] data = readBounded(tar, limits.file(), entry.getName());
        acc.add(new InputEntry(entry.getName(), type, (entry.getMode() & 0111) != 0, data));
      }
      // Force gzip trailer verification and account for trailing decompressed bytes.
      byte[] buffer = new byte[8192];
      while (bounded.read(buffer) != -1) {}
    }
    return build(acc);
  }

  public Result fromEntries(Iterable<InputEntry> entries) throws IOException {
    Accumulator acc = new Accumulator();
    for (InputEntry entry : entries) {
      acc.add(entry);
    }
    return build(acc);
  }

  private final class Accumulator {
    final Map<String, InputEntry> entries = new TreeMap<>();
    final Set<String> explicit = new HashSet<>();
    final Map<String, String> portablePaths = new TreeMap<>();
    long total;

    void add(InputEntry input) throws IOException {
      if (input == null || input.type() == null || input.content() == null) {
        throw new IOException("Null input entry");
      }
      String path = normalize(input.path(), input.type() == Type.DIRECTORY);
      if (!explicit.add(path) || explicit.size() > limits.entries()) {
        throw new IOException("Duplicate path or too many entries: " + path);
      }
      if (input.content().length > limits.file()
          || (total += input.content().length) > limits.total()) {
        throw new IOException("Input size limit exceeded: " + path);
      }
      if (input.type() == Type.DIRECTORY && input.content().length != 0) {
        throw new IOException("Directory has content: " + path);
      }
      if (path.isEmpty()) {
        return;
      }
      String[] segments = path.split("/");
      String parent = "";
      for (int i = 0; i < segments.length - 1; i++) {
        parent = parent.isEmpty() ? segments[i] : parent + "/" + segments[i];
        checkPortable(parent);
        InputEntry old =
            entries.putIfAbsent(parent, new InputEntry(parent, Type.DIRECTORY, false, new byte[0]));
        if (old != null && old.type() != Type.DIRECTORY) {
          throw new IOException("Path conflict: " + parent);
        }
      }
      checkPortable(path);
      InputEntry old = entries.get(path);
      if (old != null && (old.type() != Type.DIRECTORY || input.type() != Type.DIRECTORY)) {
        throw new IOException("Path conflict: " + path);
      }
      entries.put(
          path, new InputEntry(path, input.type(), input.executable(), input.content().clone()));
      if (entries.size() > limits.entries()) {
        throw new IOException("Too many paths: " + path);
      }
    }

    private void checkPortable(String path) throws IOException {
      String key = Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
      String previous = portablePaths.putIfAbsent(key, path);
      if (previous != null && !previous.equals(path)) {
        throw new IOException("Path conflict: " + previous + " and " + path);
      }
    }
  }

  private static String normalize(String path, boolean directory) throws IOException {
    if (path == null
        || path.isEmpty()
        || path.startsWith("/")
        || path.contains("\\")
        || path.matches("^[A-Za-z]:.*")
        || (!directory && (path.equals(".") || path.endsWith("/.")))
        || path.codePoints().anyMatch(Character::isISOControl)
        || !StandardCharsets.UTF_8.newEncoder().canEncode(path)) {
      throw new IOException("Invalid path: " + path);
    }
    if (path.length() > 4096) {
      throw new IOException("Path too long: " + path);
    }
    String candidate =
        directory && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    List<String> parts = new ArrayList<>();
    for (String part : candidate.split("/", -1)) {
      if (part.equals(".")) {
        continue;
      }
      String upper = part.toUpperCase(Locale.ROOT);
      String stem = upper.split("\\.", 2)[0];
      if (part.isEmpty()
          || part.equals("..")
          || part.endsWith(".")
          || part.endsWith(" ")
          || part.chars().anyMatch(c -> "<>:\"|?*".indexOf(c) >= 0)
          || stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
        throw new IOException("Invalid path: " + path);
      }
      parts.add(part);
    }
    if (parts.isEmpty() && !directory) {
      throw new IOException("Invalid path: " + path);
    }
    return String.join("/", parts);
  }

  private Result build(Accumulator acc) throws IOException {
    String root = "";
    Set<String> dirs = childDirectories(acc.entries, root);
    if (skillsAt(acc.entries, root, dirs).isEmpty() && dirs.size() == 1) {
      root = dirs.iterator().next() + "/";
      dirs = childDirectories(acc.entries, root);
    }
    Set<String> skills = skillsAt(acc.entries, root, dirs);
    if (skills.isEmpty()) {
      throw new IOException("No skills found");
    }
    List<String> ignored = new ArrayList<>(dirs);
    ignored.removeAll(skills);
    List<SkillManifestEntry> manifest = new ArrayList<>();
    Map<String, InputEntry> output = new TreeMap<>();
    for (String skill : skills) {
      try {
        SkillNames.canonicalSkillName(skill);
        byte[] content = acc.entries.get(root + skill + "/SKILL.md").content();
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
        manifest.add(SkillManifestScanner.parseFrontmatter(skill, text));
      } catch (IllegalArgumentException | SkillGitException | CharacterCodingException e) {
        throw new IOException("Invalid " + root + skill + "/SKILL.md: " + e.getMessage(), e);
      }
      String prefix = root + skill;
      for (InputEntry entry : acc.entries.values()) {
        if (entry.path().equals(prefix) || entry.path().startsWith(prefix + "/")) {
          if (entry.type() != Type.FILE && entry.type() != Type.DIRECTORY) {
            throw new IOException("Forbidden entry type: " + entry.path());
          }
          String name = entry.path().substring(root.length());
          if (Arrays.asList(name.split("/")).contains(".git")) {
            continue;
          }
          output.put(name + (entry.type() == Type.DIRECTORY ? "/" : ""), entry);
        }
      }
    }
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ZipArchiveOutputStream zip =
        new ZipArchiveOutputStream(new BoundedOutputStream(buffer, limits.compressed()))) {
      zip.setEncoding("UTF-8");
      zip.setMethod(ZipEntry.DEFLATED);
      for (Map.Entry<String, InputEntry> item : output.entrySet()) {
        ZipArchiveEntry entry = new ZipArchiveEntry(item.getKey());
        entry.setTimeLocal(ZIP_TIME);
        entry.setUnixMode(
            item.getValue().type() == Type.DIRECTORY
                ? 040755
                : item.getValue().executable() ? 0100755 : 0100644);
        zip.putArchiveEntry(entry);
        zip.write(item.getValue().content());
        zip.closeArchiveEntry();
      }
    }
    byte[] bytes = buffer.toByteArray();
    List<IndexEntry> index = index(bytes, output);
    return new Result(bytes, List.copyOf(manifest), List.copyOf(ignored), index);
  }

  private List<IndexEntry> index(byte[] bytes, Map<String, InputEntry> expected)
      throws IOException {
    List<IndexEntry> index = new ArrayList<>();
    try (ZipFile zip =
        ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get()) {
      Enumeration<ZipArchiveEntry> entries = zip.getEntries();
      int count = 0;
      while (entries.hasMoreElements()) {
        ZipArchiveEntry entry = entries.nextElement();
        InputEntry source = expected.get(entry.getName());
        if (source == null
            || entry.getMethod() != ZipEntry.DEFLATED
            || entry.getDataOffset() < 0
            || entry.getCompressedSize() < 0
            || entry.getDataOffset() + entry.getCompressedSize() > bytes.length) {
          throw new IOException("Invalid output entry: " + entry.getName());
        }
        byte[] data;
        try (InputStream stream = zip.getInputStream(entry)) {
          data = readBounded(stream, limits.file(), entry.getName());
        }
        CRC32 crc = new CRC32();
        crc.update(data);
        if (!Arrays.equals(data, source.content())
            || entry.getSize() != data.length
            || entry.getCrc() != crc.getValue()) {
          throw new IOException("Invalid output content: " + entry.getName());
        }
        if (!entry.isDirectory()) {
          index.add(
              new IndexEntry(
                  entry.getName(),
                  entry.getDataOffset(),
                  entry.getCompressedSize(),
                  entry.getSize(),
                  crc.getValue()));
        }
        count++;
      }
      if (count != expected.size()) {
        throw new IOException("Missing output entries");
      }
    }
    return List.copyOf(index);
  }

  private static Set<String> childDirectories(Map<String, InputEntry> entries, String root) {
    Set<String> result = new TreeSet<>();
    for (InputEntry entry : entries.values()) {
      if (entry.path().startsWith(root)) {
        String name = entry.path().substring(root.length());
        if (!name.contains("/") && entry.type() == Type.DIRECTORY) {
          result.add(name);
        }
      }
    }
    return result;
  }

  private static Set<String> skillsAt(
      Map<String, InputEntry> entries, String root, Set<String> dirs) {
    Set<String> skills = new TreeSet<>();
    for (String dir : dirs) {
      InputEntry file = entries.get(root + dir + "/SKILL.md");
      if (file != null && file.type() == Type.FILE) {
        skills.add(dir);
      }
    }
    return skills;
  }

  private static byte[] readBounded(InputStream stream, long limit, String path)
      throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = stream.read(buffer)) != -1) {
      if ((long) output.size() + n > limit) {
        throw new IOException("Size limit exceeded: " + path);
      }
      output.write(buffer, 0, n);
    }
    return output.toByteArray();
  }

  private static final class BoundedOutputStream extends FilterOutputStream {
    private long remaining;

    BoundedOutputStream(OutputStream output, long limit) {
      super(output);
      remaining = limit;
    }

    @Override
    public void write(int value) throws IOException {
      if (remaining == 0) {
        throw new IOException("Output ZIP size limit exceeded");
      }
      out.write(value);
      remaining--;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      if (length > remaining) {
        throw new IOException("Output ZIP size limit exceeded");
      }
      out.write(bytes, offset, length);
      remaining -= length;
    }
  }

  static final class BoundedStream extends FilterInputStream {
    private long remaining;

    BoundedStream(InputStream input, long limit) {
      super(input);
      remaining = limit;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int n = super.read(bytes, offset, (int) Math.min(length, remaining + 1));
      if (n > remaining) {
        throw new IOException("Decompressed tar limit exceeded");
      }
      if (n > 0) {
        remaining -= n;
      }
      return n;
    }

    @Override
    public long skip(long count) throws IOException {
      byte[] buffer = new byte[8192];
      long skipped = 0;
      while (skipped < count) {
        int n = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
        if (n < 0) {
          break;
        }
        skipped += n;
      }
      return skipped;
    }
  }
}
