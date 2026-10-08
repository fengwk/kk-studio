package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.charset.StandardCharsets;

/**
 * 结果级大文本外化：把单个尚未外化的 {@link TextResultContent} 中超过内联阈值的文本落到本地 durable 全文，用有界 head/tail 预览替换正文，并把
 * {@code textOutput} 事实合并进原有 details。
 *
 * <p>只有「恰好一个 {@link TextResultContent}」的结果参与外化：skill.sync 的 JSON（{@link
 * fun.fengwk.kkstudio.harness.common.result.JsonResultContent}）、二进制与多内容结果原样返回。已经携带 {@code
 * detailsJson.textOutput} 的结果（bash/grep/find 已用 {@link OutputSpool} 外化）不再二次落盘，保证既有发布日志不被重复
 * spool。阈值之内的小输出原样返回原始结果， contents/error/details 逐字不变。
 *
 * <p>阈值、预览、捕获预算与失败语义完全复用 {@link OutputSpool} 与 {@link TextOutputStore}：本地写入失败时 {@link OutputSpool}
 * 只降级为有界预览并明确说明全文无法保存且不给路径，绝不谎报成功；本类不提供任何「把失败改成整段大文本内联回传」的兜底，也绝不把失败改写成成功。
 */
final class LargeTextResultSpooler {

  private LargeTextResultSpooler() {}

  /** 若结果是大文本则外化并返回预览结果，否则原样返回。 */
  static EnvironmentCapabilityResult spool(
      TextOutputStore store, EnvironmentCapabilityResult result) {
    if (result.contents().size() != 1
        || !(result.contents().getFirst() instanceof TextResultContent text)
        || alreadyExternalized(result.detailsJson())) {
      return result;
    }
    try (OutputSpool spool = new OutputSpool(store, result.callId())) {
      spool.write(text.text().getBytes(StandardCharsets.UTF_8));
      if (!spool.isSpilled()) {
        return result;
      }
      EnvironmentCapabilityResult spooled = spool.finish(result.error());
      return new EnvironmentCapabilityResult(
          result.callId(),
          spooled.contents(),
          result.error(),
          mergeTextOutput(result.detailsJson(), spooled.detailsJson()));
    }
  }

  /** details 已带 {@code textOutput} 表示结果已由 {@link OutputSpool} 外化，不再二次 spool。 */
  private static boolean alreadyExternalized(String detailsJson) {
    return parseObject(detailsJson).has("textOutput");
  }

  /** 把外化产生的 {@code textOutput} 合并进原有 details，保留调用方已有的其它键。 */
  private static String mergeTextOutput(String baseDetailsJson, String spooledDetailsJson) {
    ObjectNode base = parseObject(baseDetailsJson);
    JsonNode textOutput = parseObject(spooledDetailsJson).path("textOutput");
    base.set("textOutput", textOutput);
    return base.toString();
  }

  /** detailsJson 已由 {@link EnvironmentCapabilityResult} 保证为合法 JSON object，这里直接按 object 读取。 */
  private static ObjectNode parseObject(String detailsJson) {
    return (ObjectNode) JsonValues.readTree(detailsJson);
  }
}
