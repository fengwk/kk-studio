package fun.fengwk.kkstudio.platform.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.error.DomainException;

import java.util.UUID;

class ChatIdsTest {

  @Test
  void parseCanonicalUuidSucceeds() {
    // 测试意图：合法的规范小写 UUID 能够正常解析
    UUID expected = UUID.randomUUID();
    UUID actual = ChatIds.parseUuid(expected.toString(), "chatId");
    assertEquals(expected, actual);
  }

  @Test
  void parseUuidRejectsLeadingOrTrailingWhitespace() {
    // 测试意图：收紧行为：带首尾空白的 UUID 必须被严格拒绝，不得做隐式 trim
    UUID id = UUID.randomUUID();
    assertThrows(DomainException.class, () -> ChatIds.parseUuid(" " + id, "chatId"));
    assertThrows(DomainException.class, () -> ChatIds.parseUuid(id + " ", "chatId"));
    assertThrows(DomainException.class, () -> ChatIds.parseUuid(" " + id + " ", "chatId"));
  }

  @Test
  void parseUuidRejectsNullOrBlankOrMalformed() {
    // 测试意图：null、空串及畸形格式均转为 DomainException
    assertThrows(DomainException.class, () -> ChatIds.parseUuid(null, "chatId"));
    assertThrows(DomainException.class, () -> ChatIds.parseUuid("", "chatId"));
    assertThrows(DomainException.class, () -> ChatIds.parseUuid("not-a-uuid", "chatId"));
    assertThrows(
        DomainException.class,
        () -> ChatIds.parseUuid(UUID.randomUUID().toString().toUpperCase(), "chatId"));
  }

  @Test
  void formatReturnsStringOrNull() {
    // 测试意图：格式化保持空安全且往返一致
    assertNull(ChatIds.format(null));
    UUID id = UUID.randomUUID();
    assertEquals(id.toString(), ChatIds.format(id));
  }
}
