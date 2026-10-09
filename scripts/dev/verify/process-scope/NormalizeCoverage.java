import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.IncompatibleExecDataVersionException;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把三平台收集到的 JaCoCo exec 输入规范化成官方 Reader 一定能完整读取的副本。
 *
 * <p>被终止的 helper JVM 会留下一条只写了一半的执行数据记录：它前面的记录都合法完整，最后一个 {@code
 * BLOCK_EXECUTIONDATA} 的类型字节之后的内容缺失。官方 Reader 读到这里会抛 {@code EOFException}，于是整份文件无法参与合并，
 * 三平台汇总随之失败——尽管那条残缺记录本来就不携带任何可用的覆盖信息。
 *
 * <p>这里不另写一套 JaCoCo 格式解析，而是直接用官方 {@link ExecutionDataReader} 定位「最后一条完整记录」的字节边界：子类在
 * 每个 block 读取成功后，用 {@link ByteArrayInputStream#available()} 反推已消费的 offset。数据流不做预读（
 * {@code CompactDataInput} 直接读底层字节），因此 offset 精确等于完整记录的长度。
 *
 * <p>恢复范围被收得很窄，只处理上面那一种真实故障：
 *
 * <ul>
 *   <li>仅当文件路径直接位于 {@code jacoco-helper} 目录下、且读取在最后一条 {@code BLOCK_EXECUTIONDATA} 记录处遇到 {@code
 *       EOFException}，并且此前已有完整 header、至少一条完整 session 与至少一条完整执行数据时才裁掉尾部残缺字节；
 *   <li>父进程 {@code jacoco.exec} 与任何其它文件都必须被官方 Reader 完整读出（父 exec 还必须有完整 header、session 与执行数据）；
 *   <li>坏 magic、坏版本、未知 block、header/session 截断、完整 payload 损坏等一律失败，不做恢复，也不用零值占位。
 * </ul>
 *
 * <p>正常文件按原字节复制，因此所有完整记录都保留；只有真实残缺的那一个尾部记录会被丢弃，并明确报告文件与丢弃字节数。错误输出只含
 * 规则、路径与 offset 这类元数据，绝不回显 session id、类名或原始异常信息。
 *
 * <p>以 JDK21 单文件源码方式运行：{@code java -cp <org.jacoco.core.jar> NormalizeCoverage.java
 * <exec-files.txt> <output-dir>}。它读取清单里的每个 exec，把规范化结果写成输出目录下的确定性文件名，并写出指向这些输出的新清单。
 * 所有输出都会按真实路径整体预检，绝不覆盖任何输入文件或源清单；非法环境（缺输入、输出与受保护文件相撞）在写任何东西之前失败。
 */
public final class NormalizeCoverage {

  private static final String HELPER_DIRECTORY = "jacoco-helper";

  private static final String MANIFEST_NAME = "exec-files.txt";

  private NormalizeCoverage() {}

  public static void main(String[] args) {
    try {
      run(args);
    } catch (NormalizationException e) {
      System.err.println("FAIL " + e.getMessage());
      System.exit(1);
    } catch (IOException e) {
      System.err.println(
          "FAIL coverage inputs could not be read or written: " + e.getClass().getSimpleName());
      System.exit(1);
    }
  }

  private static void run(String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println("usage: NormalizeCoverage.java <exec-files.txt> <output-dir>");
      System.exit(2);
      return;
    }
    Path manifestSource = Path.of(args[0]).toAbsolutePath().normalize();
    List<Path> inputs = readManifest(manifestSource);
    if (inputs.isEmpty()) {
      throw new NormalizationException(manifestSource + ": no exec input is listed");
    }
    Path output = Path.of(args[1]).toAbsolutePath().normalize();
    Files.createDirectories(output);
    Path outputReal = output.toRealPath();

    // 受保护路径 = 每个输入 + 源清单自身。源清单也必须受保护：输出目录若就是它的父目录，同名输出清单会把它覆盖掉。
    Set<Path> protectedReal = new LinkedHashSet<>();
    protectedReal.add(manifestSource.toRealPath());
    for (Path input : inputs) {
      if (!Files.isRegularFile(input)) {
        throw failure(input, "input is missing", 0);
      }
      protectedReal.add(input.toRealPath());
    }

    // 先把全部输出路径整体 preflight 一遍，确认没有一个会落到受保护文件上，再开始写任何字节。
    List<Path> targets = new ArrayList<>();
    for (int index = 0; index < inputs.size(); index++) {
      Path target = outputReal.resolve(String.format("%04d.exec", index));
      requireWritableTarget(target, protectedReal);
      targets.add(target);
    }
    Path manifestTarget = outputReal.resolve(MANIFEST_NAME);
    requireWritableTarget(manifestTarget, protectedReal);

    List<String> normalized = new ArrayList<>();
    int recovered = 0;
    for (int index = 0; index < inputs.size(); index++) {
      Path input = inputs.get(index);
      Recovery recovery = normalize(input, targets.get(index));
      normalized.add(targets.get(index).toString());
      if (recovery != null) {
        recovered++;
        System.out.printf(
            "recovered incomplete helper tail: %s kept=%d dropped=%d%n",
            input, recovery.keptBytes(), recovery.droppedBytes());
      }
    }
    Files.write(manifestTarget, String.join("\n", normalized).getBytes(StandardCharsets.UTF_8));
    System.out.printf(
        "normalized %d exec files: %d complete, %d recovered%n",
        inputs.size(), inputs.size() - recovered, recovered);
  }

  /**
   * 写之前确认一个输出路径不会覆盖受保护文件。
   *
   * <p>不跟随符号链接写输出（悬空链接同样危险），并对其余已存在的路径用 {@link Files#isSameFile} 判同：同路径、符号链接别名与
   * 硬链接都会因此被拦下，而不是只看字符串路径。
   */
  private static void requireWritableTarget(Path target, Set<Path> protectedReal)
      throws IOException {
    if (Files.isSymbolicLink(target)) {
      throw failure(target, "the normalized output is a symbolic link", 0);
    }
    if (Files.exists(target)) {
      for (Path protector : protectedReal) {
        if (Files.isSameFile(target, protector)) {
          throw failure(
              target, "the normalized output would overwrite an input or the manifest", 0);
        }
      }
    }
  }

  private static List<Path> readManifest(Path manifest) throws IOException {
    List<Path> inputs = new ArrayList<>();
    for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
      String value = line.strip();
      if (!value.isEmpty()) {
        inputs.add(Path.of(value));
      }
    }
    return inputs;
  }

  /**
   * 校验一个输入：完整文件原样复制，真实残缺的 helper 尾部只保留最后一条完整记录。
   *
   * @return 发生尾部裁剪时返回被保留/丢弃的字节数，完整文件返回 {@code null}
   */
  private static Recovery normalize(Path input, Path target) throws IOException {
    byte[] bytes = Files.readAllBytes(input);
    boolean helper = isHelperFile(input);
    ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
    TrackingReader reader = new TrackingReader(stream);
    try {
      reader.read();
    } catch (IncompatibleExecDataVersionException e) {
      throw failure(input, "incompatible execution data version", reader.completeOffset);
    } catch (EOFException e) {
      if (helper && reader.isRecoverableTail()) {
        Files.write(target, Arrays.copyOf(bytes, (int) reader.completeOffset));
        return new Recovery(reader.completeOffset, bytes.length - reader.completeOffset);
      }
      throw failure(input, reader.truncationRule(), reader.completeOffset);
    } catch (IOException e) {
      throw failure(input, reader.corruptionRule(), reader.completeOffset);
    }
    if (stream.available() != 0) {
      throw failure(input, "trailing bytes after the last complete record", reader.completeOffset);
    }
    // helper 允许「启动后没写任何记录」的空 dump：它本身是合法（零 block）流，官方 CLI 也会接受；父 exec 则必须有完整数据。
    if (!helper && (reader.sessionBlocks == 0 || reader.executionBlocks == 0)) {
      throw failure(
          input, "the parent exec has no complete coverage record", reader.completeOffset);
    }
    Files.write(target, bytes);
    return null;
  }

  /** 只有直接位于 {@code jacoco-helper} 目录下的文件才可能来自被终止的 helper JVM。 */
  private static boolean isHelperFile(Path input) {
    Path parent = input.toAbsolutePath().normalize().getParent();
    return parent != null && HELPER_DIRECTORY.equals(String.valueOf(parent.getFileName()));
  }

  private static NormalizationException failure(Path input, String rule, long offset) {
    return new NormalizationException(input + ": " + rule + " (offset " + offset + ")");
  }

  /** 用官方 Reader 逐 block 读取并记录完整记录边界的读取器。 */
  private static final class TrackingReader extends ExecutionDataReader {

    private final ByteArrayInputStream stream;
    private final long size;
    private byte currentType;
    private long completeOffset;
    private boolean headerComplete;
    private int sessionBlocks;
    private int executionBlocks;

    TrackingReader(ByteArrayInputStream stream) throws IOException {
      super(stream);
      this.stream = stream;
      this.size = stream.available();
      setSessionInfoVisitor(session -> {});
      setExecutionDataVisitor(execution -> {});
    }

    @Override
    protected boolean readBlock(byte type) throws IOException {
      currentType = type;
      boolean result = super.readBlock(type);
      // 只有 super.readBlock 完整返回后才推进边界，因此残缺尾部不会污染 offset。
      completeOffset = size - stream.available();
      if (type == ExecutionDataWriter.BLOCK_HEADER) {
        headerComplete = true;
      } else if (type == ExecutionDataWriter.BLOCK_SESSIONINFO) {
        sessionBlocks++;
      } else if (type == ExecutionDataWriter.BLOCK_EXECUTIONDATA) {
        executionBlocks++;
      }
      return result;
    }

    /** 尾部残缺必须发生在最后一条执行数据记录上，且此前已有完整 header、session 与执行数据。 */
    boolean isRecoverableTail() {
      return currentType == ExecutionDataWriter.BLOCK_EXECUTIONDATA
          && headerComplete
          && sessionBlocks >= 1
          && executionBlocks >= 1;
    }

    String truncationRule() {
      if (currentType == ExecutionDataWriter.BLOCK_HEADER) {
        return "truncated header";
      }
      if (currentType == ExecutionDataWriter.BLOCK_SESSIONINFO) {
        return "truncated session record";
      }
      if (currentType == ExecutionDataWriter.BLOCK_EXECUTIONDATA) {
        return "truncated execution record without a complete predecessor";
      }
      return "truncated coverage record";
    }

    String corruptionRule() {
      if (currentType == ExecutionDataWriter.BLOCK_HEADER) {
        return "invalid header";
      }
      if (currentType == ExecutionDataWriter.BLOCK_SESSIONINFO) {
        return "corrupt session record";
      }
      if (currentType == ExecutionDataWriter.BLOCK_EXECUTIONDATA) {
        return "corrupt execution record";
      }
      return "not an execution data file";
    }
  }

  private record Recovery(long keptBytes, long droppedBytes) {}

  /** 只携带规范化规则、路径与 offset 的失败，不向调用方暴露原始异常或数据内容。 */
  private static final class NormalizationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    NormalizationException(String message) {
      super(message, null, false, false);
    }
  }
}
