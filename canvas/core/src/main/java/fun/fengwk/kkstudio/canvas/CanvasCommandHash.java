package fun.fengwk.kkstudio.canvas;

import fun.fengwk.kkstudio.canvas.CanvasCommand.CreateGroup;
import fun.fengwk.kkstudio.canvas.CanvasCommand.CreateNode;
import fun.fengwk.kkstudio.canvas.CanvasCommand.DeleteGroup;
import fun.fengwk.kkstudio.canvas.CanvasCommand.DeleteNode;
import fun.fengwk.kkstudio.canvas.CanvasCommand.RenameGroup;
import fun.fengwk.kkstudio.canvas.CanvasCommand.RenameNode;
import fun.fengwk.kkstudio.canvas.CanvasCommand.SetNodeFunction;
import fun.fengwk.kkstudio.canvas.CanvasCommand.SetNodeGroup;
import fun.fengwk.kkstudio.canvas.CanvasCommand.SetNodeResources;
import fun.fengwk.kkstudio.canvas.CanvasCommand.UpdateGroupTransform;
import fun.fengwk.kkstudio.canvas.CanvasCommand.UpdateNodeTransform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 命令批的规范化请求指纹（SHA-256 小写十六进制）。
 *
 * <p>同一请求重复发送（网络重试沿用原请求）必须得到相同指纹，因此指纹只依赖命令的规范序列化：类型、结构化字段、函数 args 的规范 JSON 文本与资源意图；字段带长度前缀，避免拼接歧义。
 */
public final class CanvasCommandHash {

  private CanvasCommandHash() {}

  /** 计算整批命令的指纹。 */
  public static String of(List<CanvasCommand> commands) {
    Objects.requireNonNull(commands, "commands");
    StringBuilder canonical = new StringBuilder();
    for (CanvasCommand command : commands) {
      appendCommand(canonical, Objects.requireNonNull(command, "command"));
    }
    return sha256(canonical.toString());
  }

  private static void appendCommand(StringBuilder out, CanvasCommand command) {
    switch (command) {
      case CreateNode value -> {
        out.append("create-node|");
        appendId(out, value.nodeId());
        appendText(out, value.name());
        appendTransform(out, value.transform());
        appendInputs(out, value.resources());
      }
      case RenameNode value -> {
        out.append("rename-node|");
        appendId(out, value.nodeId());
        appendText(out, value.expectedName());
        appendText(out, value.name());
      }
      case SetNodeResources value -> {
        out.append("set-node-resources|");
        appendId(out, value.nodeId());
        appendIds(out, value.expectedResourceIds());
        appendInputs(out, value.resources());
      }
      case SetNodeFunction value -> {
        out.append("set-node-function|");
        appendId(out, value.nodeId());
        appendFunction(out, value.expectedFunction());
        appendFunction(out, value.function());
      }
      case SetNodeGroup value -> {
        out.append("set-node-group|");
        appendId(out, value.nodeId());
        appendId(out, value.expectedGroupId());
        appendId(out, value.groupId());
      }
      case DeleteNode value -> {
        out.append("delete-node|");
        appendId(out, value.nodeId());
        appendIds(out, value.expectedResourceIds());
        appendFunction(out, value.expectedFunction());
      }
      case UpdateNodeTransform value -> {
        out.append("update-node-transform|");
        appendId(out, value.nodeId());
        appendTransform(out, value.transform());
        appendTransform(out, value.expectedTransform());
      }
      case CreateGroup value -> {
        out.append("create-group|");
        appendId(out, value.groupId());
        appendText(out, value.title());
        appendTransform(out, value.transform());
      }
      case RenameGroup value -> {
        out.append("rename-group|");
        appendId(out, value.groupId());
        appendText(out, value.expectedTitle());
        appendText(out, value.title());
      }
      case UpdateGroupTransform value -> {
        out.append("update-group-transform|");
        appendId(out, value.groupId());
        appendTransform(out, value.transform());
        appendTransform(out, value.expectedTransform());
      }
      case DeleteGroup value -> {
        out.append("delete-group|");
        appendId(out, value.groupId());
        appendIds(out, value.expectedMemberNodeIds());
      }
    }
  }

  private static void appendInputs(StringBuilder out, List<CanvasResourceInput> inputs) {
    out.append(inputs.size()).append('#');
    for (CanvasResourceInput input : inputs) {
      switch (input) {
        case CanvasResourceInput.Keep keep -> {
          out.append("keep|");
          appendId(out, keep.resourceId());
        }
        case CanvasResourceInput.Text text -> {
          out.append("text|");
          appendText(out, text.name());
          appendText(out, text.textContent());
        }
        case CanvasResourceInput.Blob blob -> {
          out.append("blob|");
          appendText(out, blob.name());
          appendId(out, blob.blobId());
        }
      }
    }
  }

  private static void appendFunction(StringBuilder out, CanvasFunction function) {
    if (function == null) {
      out.append('-');
      return;
    }
    appendText(out, function.name());
    appendText(out, function.argsJson());
  }

  private static void appendTransform(StringBuilder out, CanvasTransform transform) {
    if (transform == null) {
      out.append('-');
      return;
    }
    out.append(Double.toString(transform.x()))
        .append(',')
        .append(Double.toString(transform.y()))
        .append(',')
        .append(Double.toString(transform.width()))
        .append(',')
        .append(Double.toString(transform.height()))
        .append('|');
  }

  private static void appendIds(StringBuilder out, List<UUID> ids) {
    out.append(ids.size()).append('#');
    for (UUID id : ids) {
      appendId(out, id);
    }
  }

  private static void appendId(StringBuilder out, UUID id) {
    out.append(id == null ? "-" : id.toString()).append('|');
  }

  private static void appendText(StringBuilder out, String value) {
    if (value == null) {
      out.append('-');
      return;
    }
    out.append(value.length()).append(':').append(value);
  }

  private static String sha256(String canonical) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 must be available", error);
    }
  }
}
