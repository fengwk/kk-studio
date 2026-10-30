package fun.fengwk.kkstudio.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.LinkedHashMap;
import java.util.Map;

class ApiResponseTest {

  @Test
  void buildsSuccessEnvelopesFromHttpStatus() {
    ApiResponse<String> ok = ApiResponse.ok("payload");
    assertEquals(200, ok.getStatus());
    assertEquals("OK", ok.getCode());
    assertEquals("OK", ok.getMessage());
    assertEquals("payload", ok.getData());
    assertNull(ok.getErrors());
    assertTrue(ok.isSuccess());

    ApiResponse<String> created = ApiResponse.created("payload");
    assertEquals(201, created.getStatus());
    assertEquals("CREATED", created.getCode());
    assertTrue(created.isSuccess());

    ApiResponse<String> accepted = ApiResponse.accepted("payload");
    assertEquals(202, accepted.getStatus());
    assertEquals("ACCEPTED", accepted.getCode());
    assertTrue(accepted.isSuccess());

    ApiResponse<Void> noContent = ApiResponse.noContent();
    assertEquals(204, noContent.getStatus());
    assertEquals("NO_CONTENT", noContent.getCode());
    assertNull(noContent.getData());
    assertTrue(noContent.isSuccess());

    assertNull(ApiResponse.ok().getData());
    assertNull(ApiResponse.accepted().getData());
  }

  @Test
  void derivesSuccessOnlyFromTwoHundredStatuses() {
    assertFalse(
        ApiResponse.error(HttpStatusCode.valueOf(199), "PROBE", "probe", Map.of()).isSuccess());
    assertTrue(
        ApiResponse.error(HttpStatusCode.valueOf(299), "PROBE", "probe", Map.of()).isSuccess());
    assertFalse(
        ApiResponse.error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "bad request", Map.of())
            .isSuccess());
  }

  @Test
  void errorCopiesAndFreezesContextWithoutData() {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("detail", "original");

    ApiResponse<Void> response =
        ApiResponse.error(HttpStatus.CONFLICT, "CONFLICT", "conflict", source);
    source.put("detail", "mutated");

    assertEquals(409, response.getStatus());
    assertEquals("CONFLICT", response.getCode());
    assertEquals("conflict", response.getMessage());
    assertNull(response.getData());
    assertEquals(Map.of("detail", "original"), response.getErrors());
    assertThrows(UnsupportedOperationException.class, () -> response.getErrors().put("x", "y"));
    assertFalse(response.isSuccess());
  }

  @Test
  void rejectsIncompleteErrorEnvelope() {
    assertThrows(
        NullPointerException.class, () -> ApiResponse.error(null, "CODE", "message", Map.of()));
    assertThrows(
        NullPointerException.class,
        () -> ApiResponse.error(HttpStatus.BAD_REQUEST, null, "message", Map.of()));
    assertThrows(
        NullPointerException.class,
        () -> ApiResponse.error(HttpStatus.BAD_REQUEST, "CODE", null, Map.of()));
    assertThrows(
        NullPointerException.class,
        () -> ApiResponse.error(HttpStatus.BAD_REQUEST, "CODE", "message", null));
  }
}
