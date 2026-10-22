package fun.fengwk.kkstudio.harness.environment.daemon;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * 把 Daemon wire 协议的版本、上限、错误码与枚举 wire 值投影为 Daemon 的 TypeScript 常量。
 *
 * <p>Java 协议类是这些事实的唯一来源；Daemon 的严格解码器只引用生成常量，不再各自手写数值。字段形状与校验规则仍由两端解码器各自实现， 并由共享 fixture 对拍。
 */
final class DaemonContractGenerator {

  private static final String HEADER =
      """
      /**
       * 由 Java Daemon 协议常量生成的 wire 契约；请勿手改。
       *
       * 生成器：harness/environment 测试源码 DaemonContractGenerator
       * 重新生成：scripts/dev/verify/generated-contracts/write.sh
       * 漂移门禁：DaemonContractArtifactTest（mvn verify 覆盖）
       */

      """;

  private final StringBuilder out = new StringBuilder(HEADER);

  private DaemonContractGenerator() {}

  /** 渲染完整产物；同一组 Java 常量必定产生逐字节一致的结果。 */
  static String generate() {
    DaemonContractGenerator generator = new DaemonContractGenerator();
    generator.render();
    return generator.out.toString().stripTrailing() + '\n';
  }

  private void render() {
    number(
        "当前 Daemon wire 协议版本；其他版本一律拒绝，不做双协议 fallback。", "PROTOCOL_VERSION", DaemonProtocol.VERSION);
    text(
        "capability catalog 版本；HELLO 上报，Platform 据此判定能否执行普通能力调用。",
        "CAPABILITY_CATALOG_VERSION",
        EnvironmentCapabilityCatalog.VERSION);
    number("READY capabilities 协议版本。", "READY_CAPABILITIES_VERSION", DaemonCapabilities.VERSION);
    number(
        "结果 payload 的 UTF-8 上限（解码前与最终编码输出）。",
        "MAX_PAYLOAD_UTF8_BYTES",
        DaemonCapabilityResultCodec.MAX_PAYLOAD_UTF8_BYTES);
    number(
        "单条控制消息（资源上传/更新）的字符上限：控制面只承载元数据。",
        "MAX_CONTROL_PAYLOAD_CHARS",
        DaemonResourceTransferCodec.MAX_PAYLOAD_CHARS);
    number(
        "details JSON 与 json 内容的 UTF-8 上限。",
        "MAX_DETAILS_JSON_UTF8_BYTES",
        EnvironmentCapabilityResult.MAX_DETAILS_JSON_UTF8_BYTES);
    number("contents 数组元素上限。", "MAX_CONTENT_ITEMS", EnvironmentCapabilityResult.MAX_CONTENT_ITEMS);
    number(
        "resource preview 的 UTF-8 上限。",
        "MAX_PREVIEW_UTF8_BYTES",
        ResourceRef.MAX_PREVIEW_UTF8_BYTES);
    number(
        "resource mediaType 的 ASCII 上限。",
        "MAX_MEDIA_TYPE_ASCII_BYTES",
        ResourceRef.MAX_MEDIA_TYPE_ASCII_BYTES);
    number("resource name 的 UTF-8 上限。", "MAX_NAME_UTF8_BYTES", ResourceRef.MAX_NAME_UTF8_BYTES);
    number(
        "单条上传票据 FAILED 说明的字符上限。",
        "MAX_FAILURE_MESSAGE_CHARS",
        DaemonResourceTransferCodec.MAX_FAILURE_MESSAGE_CHARS);
    number(
        "daemonVersion 的字符上限。",
        "MAX_DAEMON_VERSION_CHARS",
        DaemonCapabilities.MAX_DAEMON_VERSION_CHARS);
    number("capabilityId 的字符上限。", "MAX_CAPABILITY_ID_CHARS", EnvironmentCapabilityId.MAX_LENGTH);
    number("Environment note 的字符上限。", "MAX_NOTE_CHARS", DaemonEnvironmentInfo.MAX_NOTE_CHARS);
    number("预签名上传 URL 的字符上限。", "MAX_PRESIGNED_URL_CHARS", DaemonPresignedPut.MAX_URL_CHARS);
    number("预签名上传请求头的条目上限。", "MAX_PRESIGNED_HEADERS", DaemonPresignedPut.MAX_HEADERS);
    number(
        "预签名上传请求头名称与取值的字符上限。", "MAX_PRESIGNED_HEADER_CHARS", DaemonPresignedPut.MAX_HEADER_CHARS);
    number(
        "UPDATE_RESULT 说明的字符上限。", "MAX_UPDATE_MESSAGE_CHARS", DaemonUpdateResult.MAX_MESSAGE_CHARS);
    text(
        "ERROR payload 的可选 code：目标 Environment 已有活跃租约，按退避重连。",
        "ERROR_CODE_RETRY_LATER",
        DaemonProtocol.ERROR_CODE_RETRY_LATER);
    text(
        "ERROR payload 的可选 code：注册凭证无效，终态失败。",
        "ERROR_CODE_REGISTRATION_REJECTED",
        DaemonProtocol.ERROR_CODE_REGISTRATION_REJECTED);
    union(
        "Daemon wire 协议的消息类型。",
        "MESSAGE_TYPES",
        "MessageType",
        DaemonMessageType.values(),
        Enum::name);
    union(
        "READY 上报的操作系统。",
        "OPERATING_SYSTEMS",
        "OperatingSystem",
        DaemonOperatingSystem.values(),
        DaemonOperatingSystem::wireValue);
    union(
        "READY 上报的 CPU 架构。",
        "ARCHITECTURES",
        "Architecture",
        DaemonArchitecture.values(),
        DaemonArchitecture::wireValue);
    union(
        "资源上传票据的状态。",
        "TICKET_STATES",
        "TicketState",
        DaemonResourceTransferCodec.TicketState.values(),
        Enum::name);
    union("受管更新命令上报的阶段。", "UPDATE_PHASES", "UpdatePhase", DaemonUpdatePhase.values(), Enum::name);
  }

  private void number(String doc, String name, long value) {
    out.append("/** ").append(doc).append(" */\n");
    out.append("export const ").append(name).append(" = ").append(value).append("\n\n");
  }

  private void text(String doc, String name, String value) {
    out.append("/** ").append(doc).append(" */\n");
    out.append("export const ").append(name).append(" = ").append(quote(value)).append("\n\n");
  }

  private <E> void union(
      String doc, String constant, String type, E[] values, Function<E, String> wireValue) {
    List<String> wire = Arrays.stream(values).map(wireValue).toList();
    out.append("/** ").append(doc).append(" */\n");
    out.append("export const ").append(constant).append(" = [\n");
    for (String value : wire) {
      out.append("  ").append(quote(value)).append(",\n");
    }
    out.append("] as const\n\n");
    out.append("export type ")
        .append(type)
        .append(" = (typeof ")
        .append(constant)
        .append(")[number]\n\n");
  }

  private static String quote(String value) {
    if (!value.matches("[A-Za-z0-9_.-]+")) {
      throw new IllegalStateException("unexpected protocol literal: " + value);
    }
    return "'" + value + "'";
  }
}
