package fun.fengwk.kkstudio.harness.environment.server.terminal;

import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalControlCodec;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalResponse;
import fun.fengwk.kkstudio.share.notification.NotificationCodec;
import fun.fengwk.kkstudio.share.notification.NotificationCodecs;

import java.util.Set;
import java.util.UUID;

/**
 * {@code shell.event} topic 的唯一严格 codec：根字段恰为 {@code
 * {ownerNodeId,leaseToken,daemonInstanceId,response}}。
 *
 * <p>解码拒绝 duplicate/trailing/unknown 字段与非 canonical UUID，嵌套 response 直接复用 {@link
 * TerminalControlCodec}，不定义第二层 控制 DTO。
 *
 * <p>{@code daemonInstanceId} 是四个字段中唯一允许显式 {@code null} 的字段，且只允许「NOT_EXECUTED 错误且无终端
 * identity」这一联合场景；字段本身绝不省略， 也不接受非 canonical 或零 UUID。{@link TerminalDelivery} 的构造约束是最终判据，codec 只负责把
 * JSON null 如实映射进去。
 */
final class TerminalDeliveryCodec implements NotificationCodec<TerminalDelivery> {

  private static final Set<String> FIELDS =
      Set.of("ownerNodeId", "leaseToken", "daemonInstanceId", "response");
  private static final TerminalControlCodec CONTROL_CODEC = new TerminalControlCodec();

  @Override
  public byte[] encode(TerminalDelivery delivery) {
    if (delivery == null) {
      throw new IllegalArgumentException("delivery must not be null");
    }
    return NotificationCodecs.encodeUtf8(ShellTopicJson.write(node(delivery)));
  }

  @Override
  public TerminalDelivery decode(byte[] payload) {
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
    String text = NotificationCodecs.decodeUtf8(payload);
    ObjectNode root = ShellTopicJson.parse(text, "terminal delivery");
    ShellTopicJson.requireExactFields(root, FIELDS, "terminal delivery");
    UUID ownerNodeId = ShellTopicJson.canonicalUuid(root, "ownerNodeId", "terminal delivery");
    UUID leaseToken = ShellTopicJson.canonicalUuid(root, "leaseToken", "terminal delivery");
    UUID daemonInstanceId =
        ShellTopicJson.optionalCanonicalUuid(root, "daemonInstanceId", "terminal delivery");
    ObjectNode responseNode =
        ShellTopicJson.requireObject(root.get("response"), "terminal delivery.response");
    TerminalResponse response = CONTROL_CODEC.decodeResponse(ShellTopicJson.write(responseNode));
    return new TerminalDelivery(ownerNodeId, leaseToken, daemonInstanceId, response);
  }

  private static ObjectNode node(TerminalDelivery delivery) {
    ObjectNode root = ShellTopicJson.newObject();
    root.put("ownerNodeId", delivery.ownerNodeId().toString());
    root.put("leaseToken", delivery.leaseToken().toString());
    if (delivery.daemonInstanceId() == null) {
      root.putNull("daemonInstanceId");
    } else {
      root.put("daemonInstanceId", delivery.daemonInstanceId().toString());
    }
    root.set(
        "response",
        ShellTopicJson.parse(CONTROL_CODEC.encodeResponse(delivery.response()), "response"));
    return root;
  }
}
