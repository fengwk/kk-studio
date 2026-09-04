package fun.fengwk.kkstudio.share.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

/** Storage wire DTO 契约：required-nullable 字段显式发射 null，请求侧 sizeBytes 保持 Long。 */
class StorageDtoContractTest {

  private static final String[] REQUIRED_NULLABLE_FIELDS = {
    "StorageUploadDTO.blobId",
    "StorageUploadDTO.presignedPut",
    "StoragePresignedUrlDTO.mediaType",
    "StoragePresignedUrlDTO.sizeBytes"
  };

  @Test
  void requiredNullableFieldsAreAlwaysIncluded() throws Exception {
    for (String field : REQUIRED_NULLABLE_FIELDS) {
      Field nullable = field(field);
      JsonInclude include = nullable.getAnnotation(JsonInclude.class);
      assertNotNull(include, field + " must declare @JsonInclude");
      assertEquals(
          JsonInclude.Include.ALWAYS,
          include.value(),
          field + " must explicitly emit null even under the global NON_NULL default");
    }
  }

  /** 请求侧 sizeBytes 保持 Long，setter 只接收 Jackson 对 JSON integer 使用的整数类型。 */
  @Test
  void reserveRequestSizeBytesStaysLong() throws Exception {
    Field sizeBytes = StorageUploadReserveRequestDTO.class.getDeclaredField("sizeBytes");
    assertEquals(Long.class, sizeBytes.getType());

    StorageUploadReserveRequestDTO request = new StorageUploadReserveRequestDTO();
    request.setSizeBytes(7);
    assertEquals(7L, request.getSizeBytes());
    request.setSizeBytes(8L);
    assertEquals(8L, request.getSizeBytes());
    request.setSizeBytes(null);
    assertNull(request.getSizeBytes());
    assertThrows(IllegalArgumentException.class, () -> request.setSizeBytes("9"));
    assertThrows(IllegalArgumentException.class, () -> request.setSizeBytes(9.5D));
  }

  private static Field field(String ownerAndField) throws NoSuchFieldException {
    String[] parts = ownerAndField.split("\\.");
    String owner = "fun.fengwk.kkstudio.share.storage." + parts[0];
    try {
      return Class.forName(owner).getDeclaredField(parts[1]);
    } catch (ClassNotFoundException error) {
      throw new AssertionError("unknown DTO: " + owner, error);
    }
  }
}
