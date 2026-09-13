package fun.fengwk.kkstudio.share.cloudfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

/**
 * Cloud Files wire DTO 契约测试。
 *
 * <p>验证：durable version/revision 为规范非负十进制字符串，required-nullable 字段显式包含，未知字段拒绝。
 */
class CloudFsDtoContractTest {

  private static final String[] REQUIRED_NULLABLE_FIELDS = {
    "CloudNodeDTO.id",
    "CloudNodeDTO.blobId",
    "CloudNodeDTO.mediaType",
    "CloudNodeDTO.sizeBytes",
    "CloudNodeDTO.sha256",
    "CloudNodeDTO.revision",
    "CloudNodeDTO.createdAt",
    "CloudNodeDTO.updatedAt",
    "CloudBlobMetadataDTO.mediaType",
    "CloudBlobMetadataDTO.sizeBytes",
    "CloudBlobMetadataDTO.sha256",
    "CloudBlobMetadataDTO.createdAt",
    "CloudBlobMetadataDTO.text",
    "CloudFileSnapshotDTO.children",
    "CloudFileSnapshotDTO.text",
    "CloudFileSnapshotDTO.blob",
    "CloudTextWindowDTO.revision",
    "CloudTextWindowDTO.nextOffset",
    "CloudTextWindowDTO.content"
  };

  private static final String[] VERSION_DECIMAL_STRING_FIELDS = {
    "CloudNodeDTO.version",
    "CloudNodeDTO.revision",
    "CloudNodeDTO.sizeBytes",
    "CloudBlobMetadataDTO.sizeBytes",
    "CloudTextWindowDTO.revision",
    "CloudTextWriteRequestDTO.expectedRevision",
    "CloudTextEditRequestDTO.expectedRevision",
    "CloudNodeMoveRequestDTO.expectedVersion"
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

  @Test
  void durableVersionsAndSizesAreWireDecimalStrings() throws Exception {
    for (String field : VERSION_DECIMAL_STRING_FIELDS) {
      Field versionField = field(field);
      assertEquals(
          String.class,
          versionField.getType(),
          field + " must be String so the wire is always a decimal string");
    }
  }

  @Test
  void requestSettersRejectNonStringScalars() {
    CloudTextWriteRequestDTO writeReq = new CloudTextWriteRequestDTO();
    writeReq.setExpectedRevision("0");
    assertEquals("0", writeReq.getExpectedRevision());
    writeReq.setExpectedRevision(null);
    assertNull(writeReq.getExpectedRevision());
    assertThrows(IllegalArgumentException.class, () -> writeReq.setExpectedRevision(0L));

    CloudTextEditRequestDTO editReq = new CloudTextEditRequestDTO();
    editReq.setExpectedRevision("1");
    assertEquals("1", editReq.getExpectedRevision());
    assertThrows(IllegalArgumentException.class, () -> editReq.setExpectedRevision(1));

    CloudNodeMoveRequestDTO moveReq = new CloudNodeMoveRequestDTO();
    moveReq.setExpectedVersion("3");
    assertEquals("3", moveReq.getExpectedVersion());
    assertThrows(IllegalArgumentException.class, () -> moveReq.setExpectedVersion(3L));
  }

  @Test
  void dtosRejectUnknownFields() {
    CloudNodeDTO node = new CloudNodeDTO();
    assertThrows(IllegalArgumentException.class, () -> node.rejectUnknownField("foo", "bar"));

    CloudTextWindowDTO window = new CloudTextWindowDTO();
    assertThrows(IllegalArgumentException.class, () -> window.rejectUnknownField("foo", "bar"));

    CloudBlobMetadataDTO blob = new CloudBlobMetadataDTO();
    assertThrows(IllegalArgumentException.class, () -> blob.rejectUnknownField("foo", "bar"));

    CloudFileSnapshotDTO snapshot = new CloudFileSnapshotDTO();
    assertThrows(IllegalArgumentException.class, () -> snapshot.rejectUnknownField("foo", "bar"));

    CloudTextWriteRequestDTO writeReq = new CloudTextWriteRequestDTO();
    assertThrows(IllegalArgumentException.class, () -> writeReq.rejectUnknownField("foo", "bar"));

    CloudTextEditRequestDTO editReq = new CloudTextEditRequestDTO();
    assertThrows(IllegalArgumentException.class, () -> editReq.rejectUnknownField("foo", "bar"));

    CloudDirectoryCreateRequestDTO dirReq = new CloudDirectoryCreateRequestDTO();
    assertThrows(IllegalArgumentException.class, () -> dirReq.rejectUnknownField("foo", "bar"));

    CloudNodeMoveRequestDTO moveReq = new CloudNodeMoveRequestDTO();
    assertThrows(IllegalArgumentException.class, () -> moveReq.rejectUnknownField("foo", "bar"));

    CloudFindRequestDTO findReq = new CloudFindRequestDTO();
    assertThrows(IllegalArgumentException.class, () -> findReq.rejectUnknownField("foo", "bar"));

    CloudFindResultDTO findRes = new CloudFindResultDTO();
    assertThrows(IllegalArgumentException.class, () -> findRes.rejectUnknownField("foo", "bar"));

    CloudGrepRequestDTO grepReq = new CloudGrepRequestDTO();
    assertThrows(IllegalArgumentException.class, () -> grepReq.rejectUnknownField("foo", "bar"));

    CloudGrepMatchDTO match = new CloudGrepMatchDTO();
    assertThrows(IllegalArgumentException.class, () -> match.rejectUnknownField("foo", "bar"));

    CloudGrepResultDTO grepRes = new CloudGrepResultDTO();
    assertThrows(IllegalArgumentException.class, () -> grepRes.rejectUnknownField("foo", "bar"));

    CloudBlobMountRequestDTO mountReq = new CloudBlobMountRequestDTO();
    assertThrows(IllegalArgumentException.class, () -> mountReq.rejectUnknownField("foo", "bar"));

    CloudTextLineDTO line = new CloudTextLineDTO();
    assertThrows(IllegalArgumentException.class, () -> line.rejectUnknownField("foo", "bar"));
  }

  private static Field field(String ownerAndField) throws NoSuchFieldException {
    String[] parts = ownerAndField.split("\\.");
    String owner = "fun.fengwk.kkstudio.share.cloudfs." + parts[0];
    try {
      return Class.forName(owner).getDeclaredField(parts[1]);
    } catch (ClassNotFoundException error) {
      throw new AssertionError("unknown DTO: " + owner, error);
    }
  }
}
