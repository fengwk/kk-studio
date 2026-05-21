package fun.fengwk.kkstudio.core.storage.configuration;

import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.core.storage.S3StorageServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * S3 存储配置.
 *
 * @author fengwk
 */
@ConditionalOnProperty(prefix = "kk-circle.storage.s3", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(S3StorageProperties.class)
@Configuration
public class S3StorageConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(S3Client.class)
    public S3Client s3Client(S3StorageProperties properties) {
        validateProperties(properties);
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                .serviceConfiguration(newS3Configuration(properties))
                .httpClientBuilder(ApacheHttpClient.builder())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(S3StorageService.class)
    public S3StorageService s3StorageService(S3StorageProperties properties, S3Client s3Client) {
        return new S3StorageServiceImpl(properties, s3Client);
    }

    private S3Configuration newS3Configuration(S3StorageProperties properties) {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();
    }

    private void validateProperties(S3StorageProperties properties) {
        Assert.hasText(properties.getEndpoint(), "kk-circle.storage.s3.endpoint must not be blank");
        Assert.hasText(properties.getRegion(), "kk-circle.storage.s3.region must not be blank");
        Assert.hasText(properties.getBucket(), "kk-circle.storage.s3.bucket must not be blank");
        Assert.hasText(properties.getAccessKey(), "kk-circle.storage.s3.access-key must not be blank");
        Assert.hasText(properties.getSecretKey(), "kk-circle.storage.s3.secret-key must not be blank");
    }

}
