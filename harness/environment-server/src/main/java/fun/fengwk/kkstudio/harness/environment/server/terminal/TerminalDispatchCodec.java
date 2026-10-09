package fun.fengwk.kkstudio.harness.environment.server.terminal;

import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalControlCodec;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRequest;
import fun.fengwk.kkstudio.share.notification.NotificationCodec;
import fun.fengwk.kkstudio.share.notification.NotificationCodecs;

import java.util.Set;
import java.util.UUID;

/**
 * {@code shell.command} topic 的唯一严格 codec：根字段恰为 {@code {leaseToken,request}}。
 *
 * <p>解码拒绝 duplicate/trailing/unknown 字段与非 canonical UUID，嵌套 request 直接复用 {@link
 * TerminalControlCodec}，不定义第二层 控制 DTO。
 */
final class TerminalDispatchCodec implements NotificationCodec<TerminalDispatch> {

  private static final Set<String> FIELDS = Set.of("leaseToken", "request");
  private static final TerminalControlCodec CONTROL_CODEC = new TerminalControlCodec();

  @Override
  public byte[] encode(TerminalDispatch dispatch) {
    if (dispatch == null) {
      throw new IllegalArgumentException("dispatch must not be null");
    }
    return NotificationCodecs.encodeUtf8(ShellTopicJson.write(node(dispatch)));
  }

  @Override
  public TerminalDispatch decode(byte[] payload) {
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
    String text = NotificationCodecs.decodeUtf8(payload);
    ObjectNode root = ShellTopicJson.parse(text, "terminal dispatch");
    ShellTopicJson.requireExactFields(root, FIELDS, "terminal dispatch");
    UUID leaseToken = ShellTopicJson.canonicalUuid(root, "leaseToken", "terminal dispatch");
    ObjectNode requestNode =
        ShellTopicJson.requireObject(root.get("request"), "terminal dispatch.request");
    TerminalRequest request = CONTROL_CODEC.decodeRequest(ShellTopicJson.write(requestNode));
    return new TerminalDispatch(leaseToken, request);
  }

  private static ObjectNode node(TerminalDispatch dispatch) {
    ObjectNode root = ShellTopicJson.newObject();
    root.put("leaseToken", dispatch.leaseToken().toString());
    root.set(
        "request",
        ShellTopicJson.parse(CONTROL_CODEC.encodeRequest(dispatch.request()), "request"));
    return root;
  }
}
