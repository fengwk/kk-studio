package fun.fengwk.kkstudio.web.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Base64;

class InMemoryS3StorageServiceTest {

  /** Web 集成测试替身须拒绝实际字节与显式 checksum 不匹配的 PUT，不能留下对象。 */
  @Test
  void rejectsChecksumMismatchWithoutStoringObject() {
    InMemoryS3StorageService fake = new InMemoryS3StorageService();
    String wrongChecksum = Base64.getEncoder().encodeToString(new byte[32]);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            fake.putObject(
                "bad", new ByteArrayInputStream(new byte[] {1}), 1, null, wrongChecksum));
    assertFalse(fake.hasObject("bad"));
  }
}
