package fun.fengwk.kkstudio.core.storage;

import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.InputStream;

/**
 * S3 存储服务.
 *
 * @author fengwk
 */
public interface S3StorageService {

    PutObjectResponse putObject(String key, InputStream content, long contentLength, String contentType);

    boolean exists(String key);

    String getPublicUrl(String key);

    byte[] download(String key);

}
