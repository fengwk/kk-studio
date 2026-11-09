package fun.fengwk.kkstudio.platform.daemon.protocol;

import com.google.protobuf.InvalidProtocolBufferException;

import fun.fengwk.kkstudio.platform.daemon.DaemonSkillPackage;
import fun.fengwk.kkstudio.platform.daemon.DaemonSkillStatePackage;

import java.util.List;
import java.util.Objects;

/**
 * 控制协议的 protobuf binary codec 与领域映射。
 *
 * <p>负责四字节长度前缀成帧、protobuf 解析/序列化以及 proto 与领域对象的映射。{@link ControlProtocolValidator} 在 {@link
 * #decodeBinary} / {@link #encodeBinary} 的 wire 边界施加一次应用层语义校验；映射方法只转换已校验的 proto，不重复校验。
 */
public final class ControlProtocolCodec {

  /** 控制协议版本；用于握手与 426 升级载荷。 */
  public static final int PROTOCOL_VERSION = 3;

  private ControlProtocolCodec() {}

  /** 解码一条裸 protobuf 消息并施加全部应用校验。 */
  public static Envelope decodeBinary(byte[] bytes) {
    requireMessageLength(bytes);
    try {
      return ControlProtocolValidator.validate(Envelope.parseFrom(bytes));
    } catch (InvalidProtocolBufferException error) {
      throw new ControlProtocolException("control message is not valid protobuf", error);
    }
  }

  /** 校验后编码一条裸 protobuf 消息。 */
  public static byte[] encodeBinary(Envelope envelope) {
    ControlProtocolValidator.validate(envelope);
    int size = envelope.getSerializedSize();
    if (size == 0 || size > ControlFraming.MAX_MESSAGE_BYTES) {
      throw new ControlProtocolException(
          "control message length must be in 1.." + ControlFraming.MAX_MESSAGE_BYTES + " bytes");
    }
    return envelope.toByteArray();
  }

  /** 校验后编码为一条带四字节长度前缀的帧。 */
  public static byte[] encodeFrame(Envelope envelope) {
    return ControlFraming.frame(encodeBinary(envelope));
  }

  /** 构建一条完整的期望技能集合；编码边界负责施加协议校验。 */
  public static SkillSync toProtoSkillSync(List<DaemonSkillPackage> packages) {
    Objects.requireNonNull(packages, "packages");
    SkillSync.Builder sync = SkillSync.newBuilder();
    for (DaemonSkillPackage skillPackage : packages) {
      Objects.requireNonNull(skillPackage, "packages[]");
      sync.addPackages(
          SkillPackage.newBuilder()
              .setPackageName(skillPackage.packageName())
              .setSha256(skillPackage.sha256())
              .setSizeBytes(skillPackage.sizeBytes())
              .setDownloadUrl(skillPackage.downloadUrl())
              .build());
    }
    SkillSync proto = sync.build();
    return proto;
  }

  /** 把已校验的状态上报 proto 转换为领域集合。 */
  public static List<DaemonSkillStatePackage> toDomainSkillState(SkillState proto) {
    Objects.requireNonNull(proto, "proto");
    return proto.getPackagesList().stream()
        .map(
            state ->
                new DaemonSkillStatePackage(
                    state.getPackageName(),
                    state.hasInstalledSha256() ? state.getInstalledSha256() : null,
                    state.hasLocalPath() ? state.getLocalPath() : null,
                    state.hasError() ? state.getError() : null))
        .toList();
  }

  private static void requireMessageLength(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > ControlFraming.MAX_MESSAGE_BYTES) {
      throw new ControlProtocolException(
          "control message length must be in 1.." + ControlFraming.MAX_MESSAGE_BYTES + " bytes");
    }
  }
}
