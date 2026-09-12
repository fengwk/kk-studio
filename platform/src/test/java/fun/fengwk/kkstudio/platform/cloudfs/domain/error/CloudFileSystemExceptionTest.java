package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNodeKind;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

/** 针对 CloudFS 异常类的全字段与多构造器覆盖测试。 */
class CloudFileSystemExceptionTest {

  /** 验证各领域异常对象的字段存取与构造器行为。 */
  @Test
  void testExceptions() {
    CloudPath path = CloudPath.of("/test/path.txt");
    CloudPath otherPath = CloudPath.of("/test/other");

    // CloudCycleException
    CloudCycleException cycleEx = new CloudCycleException(path, otherPath);
    assertEquals(path, cycleEx.getSourcePath());
    assertEquals(otherPath, cycleEx.getTargetPath());

    // CloudNodeAlreadyExistsException
    CloudNodeAlreadyExistsException existsEx = new CloudNodeAlreadyExistsException(path);
    assertEquals(path, existsEx.getPath());

    // CloudNodeKindConflictException
    CloudNodeKindConflictException kindEx =
        new CloudNodeKindConflictException(path, CloudNodeKind.TEXT, CloudNodeKind.DIRECTORY);
    assertEquals(path, kindEx.getPath());
    assertEquals(CloudNodeKind.TEXT, kindEx.getExpectedKind());
    assertEquals(CloudNodeKind.DIRECTORY, kindEx.getActualKind());

    // CloudNodeNotFoundException
    CloudNodeNotFoundException notFoundPath = new CloudNodeNotFoundException(path);
    assertEquals(path, notFoundPath.getPath());
    CloudNodeNotFoundException notFoundMsg = new CloudNodeNotFoundException("msg only");
    assertNull(notFoundMsg.getPath());

    // CloudRevisionConflictException
    CloudRevisionConflictException revEx = new CloudRevisionConflictException(path, 2L, 1L);
    assertEquals(path, revEx.getPath());
    assertEquals(2L, revEx.getCurrentRevision());
    assertEquals(1L, revEx.getExpectedRevision());

    // CloudVersionConflictException
    CloudVersionConflictException verEx = new CloudVersionConflictException(path, 5L, 3L);
    assertEquals(path, verEx.getPath());
    assertEquals(5L, verEx.getCurrentVersion());
    assertEquals(3L, verEx.getExpectedVersion());

    // CloudPathForbiddenException
    CloudPathForbiddenException forbidEx = new CloudPathForbiddenException(path, "forbidden");
    assertEquals(path, forbidEx.getPath());

    // CloudPathValidationException
    CloudPathValidationException valEx1 = new CloudPathValidationException("invalid");
    assertEquals("invalid", valEx1.getMessage());
    RuntimeException cause = new RuntimeException("cause");
    CloudPathValidationException valEx2 = new CloudPathValidationException("invalid", cause);
    assertSame(cause, valEx2.getCause());

    // CloudArtifactConflictException
    CloudArtifactConflictException artEx = new CloudArtifactConflictException(path, "conflict");
    assertEquals(path, artEx.getPath());

    // CloudDirectoryNotEmptyException
    CloudDirectoryNotEmptyException notEmptyEx = new CloudDirectoryNotEmptyException(path, 3);
    assertEquals(path, notEmptyEx.getPath());

    // CloudEditAmbiguousException
    CloudEditAmbiguousException ambEx = new CloudEditAmbiguousException(path, 4);
    assertEquals(path, ambEx.getPath());
    assertEquals(4, ambEx.getMatchCount());

    // CloudEditPatternNotFoundException
    CloudEditPatternNotFoundException notFoundPat = new CloudEditPatternNotFoundException(path);
    assertEquals(path, notFoundPat.getPath());

    // CloudFileSystemValidationException
    CloudFileSystemValidationException fsVal1 = new CloudFileSystemValidationException("err");
    assertEquals("err", fsVal1.getMessage());
    CloudFileSystemValidationException fsVal2 =
        new CloudFileSystemValidationException("err", cause);
    assertSame(cause, fsVal2.getCause());
  }
}
