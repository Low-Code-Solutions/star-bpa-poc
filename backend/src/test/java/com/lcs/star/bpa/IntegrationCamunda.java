package com.lcs.star.bpa;

import java.time.Duration;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The integration Camunda for BPA-43 Part B (Q9, Ahsan's item 3 of 29 Oct): {@code camunda/camunda:8.10.1} configured like
 * docker-compose.yml (basic auth, API protected, authorizations on, the demo admin initialisation, PostgreSQL secondary
 * storage), not the Camunda Process Test container. One PostgreSQL 17 holds Camunda's tables (schema public) and the app's
 * schema bpa, migrated by Flyway; compose uses two databases on one server. Started once per test JVM; Ryuk removes it.
 */
final class IntegrationCamunda {

    static final String ADMIN = "demo";
    static final String ADMIN_PASSWORD = "demo";

    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres");

    private static final GenericContainer<?> CAMUNDA = new GenericContainer<>("camunda/camunda:8.10.1")
            .withNetwork(NETWORK)
            .dependsOn(POSTGRES)
            .withEnv("CAMUNDA_DATA_SECONDARY_STORAGE_TYPE", "rdbms")
            .withEnv("CAMUNDA_DATA_SECONDARY_STORAGE_RDBMS_URL", "jdbc:postgresql://postgres:5432/test")
            .withEnv("CAMUNDA_DATA_SECONDARY_STORAGE_RDBMS_USERNAME", "test")
            .withEnv("CAMUNDA_DATA_SECONDARY_STORAGE_RDBMS_PASSWORD", "test")
            .withEnv("CAMUNDA_SECURITY_AUTHENTICATION_METHOD", "basic")
            .withEnv("CAMUNDA_SECURITY_INITIALIZATION_USERS_0_USERNAME", ADMIN)
            .withEnv("CAMUNDA_SECURITY_INITIALIZATION_USERS_0_PASSWORD", ADMIN_PASSWORD)
            .withEnv("CAMUNDA_SECURITY_INITIALIZATION_USERS_0_NAME", "Demo")
            .withEnv("CAMUNDA_SECURITY_INITIALIZATION_USERS_0_EMAIL", "demo@example.com")
            .withEnv("CAMUNDA_SECURITY_INITIALIZATION_DEFAULTROLES_ADMIN_USERS_0", ADMIN)
            .withExposedPorts(8080, 26500, 9600)
            .waitingFor(Wait.forHttp("/actuator/health/status").forPort(9600).withStartupTimeout(Duration.ofMinutes(5)));

    private static boolean migrated;

    private IntegrationCamunda() {
    }

    static synchronized void start() {
        if (!CAMUNDA.isRunning()) {
            CAMUNDA.start();
            awaitAdminLogin();
        }
        if (!migrated) {
            Flyway.configure().dataSource(dataSource()).schemas("bpa").load().migrate();
            migrated = true;
        }
    }

    /** Health is UP before the demo admin initialisation has finished; until then the admin gets 401 (seen in a full run). */
    private static void awaitAdminLogin() {
        var admin = new CamundaRest(restUrl(), ADMIN, ADMIN_PASSWORD);
        long until = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (admin.get("/v2/authentication/me").status() != 200) {
            if (System.nanoTime() > until) {
                throw new IllegalStateException("Camunda admin cannot log in two minutes after the container became healthy");
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    static String restUrl() {
        return "http://" + CAMUNDA.getHost() + ":" + CAMUNDA.getMappedPort(8080);
    }

    static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
