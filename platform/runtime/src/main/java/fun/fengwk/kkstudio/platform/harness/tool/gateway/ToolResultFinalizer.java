package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.contributor.builtin.environment.FileTool;
import fun.fengwk.kkstudio.harness.spi.ToolResultSizeLimits;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Terminal {@link ToolResult} 的结果级统一终态化器（{@code ToolResultFinalizer}）。
 *
 * <p>统一终态化处理规则：
 *
 * <ul>
 *   <li>按原始 content 顺序用两个换行（{@code \n\n}）拼接所有 Text/Json 内容，形成唯一 canonical textual projection；
 *   <li>若 projection 同时满足内联阈值（默认 &le; 50 KiB 且 &le; 2000 物理行；可信内置 {@code read} 身份为 &le; 320 KiB 且
 *       &le; 2020 物理行），原样内联保留；
 *   <li>任一阈值超出时，将完整 projection 存为一个 managed Resource，删除所有原 Text/Json 内容， 并在第一个原文本位置插入携带小 raw
 *       preview（&le; 2 KiB 且 &le; 20 行）和 {@link TextArtifactMetadata} 的 {@link
 *       ResourceResultContent}，其余非文本 Resource 顺序保持不变；
 *   <li>所有 {@link BinaryResultContent} 外部化为 managed Resource；已有 Resource 只接受已上传的 {@code
 *       blob-upload} 或已授权的 Session 引用并原样透传，其余 scheme 一律拒绝；文本工件的 preview 只从受信 bytes 重新计算；
 *   <li>All-or-nothing plan：第一个 put 之前完成所有确定性校验、全文有界测量、硬上限（{@code tool.maxOutputMib}，超限报 {@code
 *       OUTPUT_TOO_LARGE}）、元数据规划与投影 canonical JSON 尺寸（&le; 1 MiB）验证；
 *   <li>存储失败或契约违反收敛为 {@code UNKNOWN / RESOURCE_STORE_FAILED}。
 * </ul>
 *
 * <p>只读规划（分类、测量与尺寸核验）由 {@link TerminalResultPlan} 承担，文本测量与严格 Unicode 校验由 {@link
 * TerminalTextMetrics} 承担；本类只负责把计划落到资源存储，并在任一失败时按逆序放弃已写入的部分。
 */
@Slf4j
public final class ToolResultFinalizer {

  /** terminal textual projection 的完整内联字节上限（50 KiB）。 */
  public static final int INLINE_MAX_UTF8_BYTES = 50 * 1024;

  /** terminal textual projection 的完整内联物理行数上限（2000 行）。 */
  public static final int INLINE_MAX_LINES = 2000;

  /**
   * 可信内置 {@code read} 身份的文本投影内联 UTF-8 字节上限（320 KiB）。
   *
   * <p>{@code read} 契约自身已把正文限制在 2000 行、60000 个 Unicode code point 以内（不计行号、元数据与行分隔符），最坏 4 字节 code
   * point 正文为 240000 字节，加上 2000 行行号前缀与 header/footer 仍在 320 KiB 内，因此该窗口不需要二次外化。 该上限只对可信内置 {@code
   * read} 身份生效：工具身份就是模型可见 name，跨源/同源同名在 {@code PluginRuntimeToolCatalogSource} 中 fail-closed， 任意
   * MCP 同名工具无法借 {@code read} 名放宽预算。
   */
  public static final int READ_INLINE_MAX_UTF8_BYTES = 320 * 1024;

  /** 可信内置 {@code read} 身份的文本投影内联物理行数上限（2000 行正文加 header/footer 余量）。 */
  public static final int READ_INLINE_MAX_LINES = 2020;

  /** 大输出 raw preview 的 UTF-8 字节上限（2 KiB）。 */
  public static final int TRUNCATED_PREVIEW_MAX_UTF8_BYTES = 2 * 1024;

  /** 大输出 raw preview 的物理行数上限（20 行）。 */
  public static final int TRUNCATED_PREVIEW_MAX_LINES = 20;

  /** 外部化后的 canonical ToolResult JSON 上限（1 MiB）。 */
  public static final int MAX_TERMINAL_RESULT_UTF8_BYTES =
      ToolResultSizeLimits.MAX_TERMINAL_RESULT_UTF8_BYTES;

  public static final String INVALID_RESULT_KIND = "INVALID_RESULT";
  public static final String OUTPUT_TOO_LARGE_KIND = "OUTPUT_TOO_LARGE";
  public static final String RESOURCE_STORE_FAILED_KIND = "RESOURCE_STORE_FAILED";

  private static final String RESOURCE_STORE_FAILED_MESSAGE =
      "Tool result resource persistence failed; outcome is unknown.";

  private final ToolResourceStager resourceStager;
  private final IntSupplier outputLimitBytes;

  public ToolResultFinalizer(ToolResourceStager resourceStager, IntSupplier outputLimitBytes) {
    this.resourceStager = Objects.requireNonNull(resourceStager, "resourceStager");
    this.outputLimitBytes = Objects.requireNonNull(outputLimitBytes, "outputLimitBytes");
    if (outputLimitBytes.getAsInt() <= 0) {
      throw new IllegalArgumentException("tool output size limit must be positive");
    }
  }

  /**
   * 全有或全无的终态化处理：先计算完整投影计划并在首个 put 前完成所有尺寸与格式校验，然后执行必要存储并核对返回引用。
   *
   * @param toolName 工具名称
   * @param result 待终态化的工具结果
   * @return 终态化结果
   */
  public Outcome finalizeResult(String toolName, ToolResult result) {
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(result, "result");

    int maxOutputBytes = outputLimitBytes.getAsInt();
    if (maxOutputBytes <= 0) {
      throw new IllegalStateException("tool output size limit must be positive");
    }
    TerminalResultPlan.Plan plan;
    try {
      plan = TerminalResultPlan.plan(toolName, result, maxOutputBytes);
    } catch (TerminalResultPlan.OutputTooLargeException tooLarge) {
      return Outcome.outputTooLarge(tooLarge.getMessage());
    } catch (IllegalArgumentException invalid) {
      return Outcome.invalid(invalid.getMessage());
    }
    return stage(plan, result, maxOutputBytes);
  }

  /** 逐个落实计划项；任一失败先逆序放弃已写入的上传，再收敛为确定性结果。 */
  private Outcome stage(TerminalResultPlan.Plan plan, ToolResult result, int maxOutputBytes) {
    List<ResultContent> finalContents = new ArrayList<>(plan.items().size());
    List<ResourceRef> stagedResources = new ArrayList<>();
    for (TerminalResultPlan.PlanItem item : plan.items()) {
      if (item instanceof TerminalResultPlan.PlanItem.PassThrough pass) {
        finalContents.add(pass.content());
        continue;
      }
      TerminalResultPlan.PlanItem.StoreToResource store =
          (TerminalResultPlan.PlanItem.StoreToResource) item;
      ResourceRef returned;
      try {
        returned =
            resourceStager.stage(
                store.planned().mediaType(), store.planned().name(), store.bytes());
      } catch (ToolOutputLimitExceededException tooLarge) {
        discard(stagedResources);
        return Outcome.outputTooLarge(tooLarge.getMessage());
      } catch (RuntimeException failure) {
        discard(stagedResources);
        return Outcome.storeFailed();
      }
      if (returned != null && returned.blobUploadId() != null) {
        stagedResources.add(returned);
      }
      if (!sameContentFacts(store.planned(), returned, maxOutputBytes)) {
        discard(stagedResources);
        return Outcome.storeFailed();
      }
      finalContents.add(new ResourceResultContent(returned, store.preview(), store.metadata()));
    }

    return Outcome.success(
        new ToolResult(result.toolCallId(), finalContents, result.error(), result.detailsJson()));
  }

  private void discard(List<ResourceRef> resources) {
    for (int index = resources.size() - 1; index >= 0; index--) {
      ResourceRef resource = resources.get(index);
      try {
        resourceStager.discard(resource);
      } catch (RuntimeException failure) {
        // Staged uploads also have durable expiry; cleanup failure must not mask UNKNOWN.
        log.warn(
            "cannot discard staged tool resource upload {}: {}",
            resource.blobUploadId(),
            failure.getClass().getSimpleName());
      }
    }
  }

  /**
   * 返回该工具身份适用的文本投影内联 UTF-8 字节上限。
   *
   * <p>只有可信内置 {@link FileTool#READ_NAME read} 身份使用 {@link #READ_INLINE_MAX_UTF8_BYTES}；其他工具保持
   * {@link #INLINE_MAX_UTF8_BYTES}。工具身份就是模型可见 name，同名冲突在运行时目录 fail-closed，因此该名称只能解析到内置 read。
   */
  static int inlineMaxUtf8Bytes(String toolName) {
    return FileTool.READ_NAME.equals(toolName) ? READ_INLINE_MAX_UTF8_BYTES : INLINE_MAX_UTF8_BYTES;
  }

  /** 返回该工具身份适用的文本投影内联物理行数上限；只有可信内置 {@code read} 身份使用加宽上限。 */
  static int inlineMaxLines(String toolName) {
    return FileTool.READ_NAME.equals(toolName) ? READ_INLINE_MAX_LINES : INLINE_MAX_LINES;
  }

  /** 严格计算物理行数：空文本为 0；否则为 LF 数量加上未以 LF 结尾时的最后一行。 */
  public static long countPhysicalLines(String text) {
    return TerminalTextMetrics.countPhysicalLines(text);
  }

  /** 提取 UTF-8 安全且不拆行/码点的 raw preview 前缀（&le; 2 KiB 且 &le; 20 物理行）。 只保留前缀，无 tail，无截断标记。 */
  public static String extractRawPreview(String text) {
    return TerminalTextMetrics.extractRawPreview(text);
  }

  private static boolean sameContentFacts(
      ResourceRef expected, ResourceRef actual, int maxOutputBytes) {
    return actual != null
        && actual.blobUploadId() != null
        && actual.size() != null
        && actual.size() <= maxOutputBytes
        && Objects.equals(expected.name(), actual.name())
        && Objects.equals(expected.size(), actual.size())
        && expected.sha256().equals(actual.sha256());
  }

  /** 终态化结果：Success 投递成功；Failed 确定性失败；StoreFailed 存储失败（收敛为 UNKNOWN）。 */
  public sealed interface Outcome permits Outcome.Success, Outcome.Failed, Outcome.StoreFailed {

    record Success(ToolResult result) implements Outcome {}

    record Failed(ToolInvocationError error) implements Outcome {}

    record StoreFailed(ToolInvocationError error) implements Outcome {}

    static Outcome success(ToolResult result) {
      return new Success(result);
    }

    static Outcome invalid(String message) {
      return new Failed(
          new ToolInvocationError(
              INVALID_RESULT_KIND,
              message == null || message.isBlank() ? "Tool result content is invalid." : message));
    }

    static Outcome outputTooLarge(String message) {
      return new Failed(
          new ToolInvocationError(
              OUTPUT_TOO_LARGE_KIND,
              message == null || message.isBlank()
                  ? "Tool output exceeded resource hard limit."
                  : message));
    }

    static Outcome storeFailed() {
      return new StoreFailed(
          new ToolInvocationError(RESOURCE_STORE_FAILED_KIND, RESOURCE_STORE_FAILED_MESSAGE));
    }
  }
}
