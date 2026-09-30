package com.arpitha.systemtests;

import com.arpitha.common.util.TimeZoneNormalizer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * The whole system in Docker, started once and shared by every system test class:
 * PostgreSQL, Kafka, identity-service, api-gateway and the three business services, on a
 * private network. The service containers use the images `docker compose build` produces
 * (the pom runs it before the tests), so the tests exercise exactly what compose runs.
 * Testcontainers removes everything when the test JVM exits.
 */
final class SystemEnvironment {

    static {
        // Same as the services' main(): PostgreSQL rejects legacy zone IDs such as "Asia/Calcutta".
        TimeZoneNormalizer.normalizeDefault();
    }

    static final String ADMIN_EMAIL = "admin@system-test.local";
    static final String ADMIN_PASSWORD = "system-test-admin-password";

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Network NETWORK = Network.newNetwork();

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres")
            .withUsername("postgres")
            .withPassword("postgres")
            // Same script docker-compose uses to create the service databases.
            .withCopyFileToContainer(
                    MountableFile.forHostPath(REPO_ROOT.resolve("docker/postgres/init.sql")),
                    "/docker-entrypoint-initdb.d/init.sql");

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0")
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withListener("kafka:19092");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("redis")
            .withExposedPorts(6379);

    static final GenericContainer<?> ORDER_SERVICE = service("order-service", 8080, "orderdb", Map.of());
    static final GenericContainer<?> INVENTORY_SERVICE = service("inventory-service", 8083, "ecommerce_inventory_db", Map.of());
    static final GenericContainer<?> PRODUCT_SERVICE = service("product-service", 8082, "ecommerce_product_db", Map.of(
            "SPRING_DATA_REDIS_HOST", "redis"));
    static final GenericContainer<?> IDENTITY_SERVICE = service("identity-service", 8084, "ecommerce_identity_db", Map.of(
            "IDENTITY_ADMIN_EMAIL", ADMIN_EMAIL,
            "IDENTITY_ADMIN_PASSWORD", ADMIN_PASSWORD));

    static final GenericContainer<?> API_GATEWAY = new GenericContainer<>(DockerImageName.parse("ecommerce/api-gateway:latest"))
            .withNetwork(NETWORK)
            .withNetworkAliases("api-gateway")
            .withExposedPorts(8000)
            .withEnv("IDENTITY_SERVICE_URL", "http://identity-service:8084")
            .withEnv("ORDER_SERVICE_URL", "http://order-service:8080")
            .withEnv("PRODUCT_SERVICE_URL", "http://product-service:8082")
            .withEnv("INVENTORY_SERVICE_URL", "http://inventory-service:8083")
            .waitingFor(Wait.forHttp("/actuator/health").forPort(8000).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    static {
        POSTGRES.start();
        KAFKA.start();
        REDIS.start();
        // The services are independent of each other at startup, so start them in parallel.
        Startables.deepStart(ORDER_SERVICE, INVENTORY_SERVICE, PRODUCT_SERVICE, IDENTITY_SERVICE, API_GATEWAY).join();
    }

    private SystemEnvironment() {
    }

    private static GenericContainer<?> service(String name, int port, String database, Map<String, String> extraEnv) {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("ecommerce/" + name + ":latest"))
                .withNetwork(NETWORK)
                .withNetworkAliases(name)
                .withExposedPorts(port)
                .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/" + database)
                .withEnv("SPRING_DATASOURCE_USERNAME", "postgres")
                .withEnv("SPRING_DATASOURCE_PASSWORD", "postgres")
                .withEnv("SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:19092")
                .withEnv("SPRING_JPA_SHOW_SQL", "false")
                .withEnv(extraEnv)
                .dependsOn(POSTGRES, KAFKA)
                .waitingFor(Wait.forHttp("/actuator/health").forPort(port).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        return container;
    }

    static String url(GenericContainer<?> container, int port) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(port);
    }

    static String gatewayUrl() {
        return url(API_GATEWAY, 8000);
    }

    /** Keeps every container's log in target/system-test-logs for investigating failures. */
    static void saveLogs() throws IOException {
        Path dir = Files.createDirectories(Path.of("target", "system-test-logs"));
        Files.writeString(dir.resolve("order-service.log"), ORDER_SERVICE.getLogs());
        Files.writeString(dir.resolve("inventory-service.log"), INVENTORY_SERVICE.getLogs());
        Files.writeString(dir.resolve("product-service.log"), PRODUCT_SERVICE.getLogs());
        Files.writeString(dir.resolve("identity-service.log"), IDENTITY_SERVICE.getLogs());
        Files.writeString(dir.resolve("api-gateway.log"), API_GATEWAY.getLogs());
    }
}
