package fun.fengwk.kkstudio.share.notification;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;

/** Strict canonical scalar codecs used by statically declared domain topics. */
public final class NotificationCodecs {
  public static final NotificationCodec<UUID> UUID_CODEC =
      text(UUID::toString, NotificationCodecs::uuid);
  public static final NotificationCodec<Long> VERSION =
      text(
          Object::toString,
          value -> {
            long version = Long.parseLong(value);
            if (version < 0 || !Long.toString(version).equals(value)) {
              throw new IllegalArgumentException("invalid version hint");
            }
            return version;
          });
  public static final NotificationCodec<VersionHint> VERSION_HINT =
      text(
          value -> value.entityId() + ":" + value.version(),
          value -> {
            String[] fields = value.split(":", -1);
            if (fields.length != 2) {
              throw new IllegalArgumentException("invalid entity version hint");
            }
            return new VersionHint(
                uuid(fields[0]), VERSION.decode(fields[1].getBytes(StandardCharsets.UTF_8)));
          });
  public static final NotificationCodec<EntityHint> ENTITY_HINT =
      text(
          value -> value.entityId() == null ? "" : value.entityId().toString(),
          value -> new EntityHint(value.isEmpty() ? null : uuid(value)));
  public static final NotificationCodec<NotificationSignal> SIGNAL =
      text(
          ignored -> "",
          value -> {
            if (!value.isEmpty()) {
              throw new IllegalArgumentException("unexpected signal payload");
            }
            return NotificationSignal.CHANGED;
          });
  public static final NotificationCodec<String> NAME =
      text(
          value -> value,
          value -> {
            if (value.isBlank() || value.indexOf('\0') >= 0) {
              throw new IllegalArgumentException("invalid notification name");
            }
            return value;
          });

  public static <T> NotificationCodec<T> text(
      Function<T, String> encode, Function<String, T> decode) {
    return new NotificationCodec<>() {
      @Override
      public byte[] encode(T value) {
        String encoded = encode.apply(value);
        decode.apply(encoded);
        return encodeUtf8(encoded);
      }

      @Override
      public T decode(byte[] bytes) {
        String text = decodeUtf8(bytes);
        T value = decode.apply(text);
        if (!encode.apply(value).equals(text)) {
          throw new IllegalArgumentException("noncanonical notification payload");
        }
        return value;
      }
    };
  }

  /**
   * Strict UTF-8 encoding: a lone UTF-16 surrogate is rejected instead of being silently replaced
   * by {@code ?}, so the locally encoded bytes and the remotely decoded text always agree.
   */
  public static byte[] encodeUtf8(String text) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(text));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException("invalid notification text", error);
    }
  }

  /**
   * Strict UTF-8 decoding: malformed bytes are rejected instead of silently becoming replacement
   * characters.
   */
  public static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException("invalid notification UTF-8", error);
    }
  }

  private static UUID uuid(String text) {
    UUID uuid = UUID.fromString(text);
    if (!uuid.toString().equals(text)) {
      throw new IllegalArgumentException("noncanonical UUID hint");
    }
    return uuid;
  }

  private NotificationCodecs() {}
}
