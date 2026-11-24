package com.shiptrack.api;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

/**
 * The single controlled datasource for the one-shot {@code migrate} profile.
 *
 * <p>Spring Boot's datasource auto-configuration binds every {@code spring.datasource.*}
 * property, including Hikari's {@code dataSourceClassName}/{@code dataSourceProperties},
 * which make Hikari instantiate an arbitrary {@code DataSource} with its own URL and
 * connection properties — bypassing the URL the credential guard verified. Instead of
 * fighting that binding, the migrate profile defines its own {@link DataSource} bean, so
 * Boot's auto-configuration backs off. The URL is re-validated here with
 * {@link MigrateUrlPolicy}, the driver is fixed, and the credentials come from
 * {@code DB_MIGRATION_USER}/{@code DB_MIGRATION_PASSWORD} only. Flyway consumes this
 * bean as well, so the checked address, the account and the actual connection cannot
 * diverge (技术栈使用说明 §3.7).
 */
@Configuration(proxyBeanMethods = false)
@Profile("migrate")
class MigrateDataSourceConfiguration {

    private static final String URL_PROPERTY = "spring.datasource.url";

    @Bean
    DataSource dataSource(Environment environment) {
        String url = MigrateUrlPolicy.requireSupported(environment.getProperty(URL_PROPERTY));
        String username = environment.getProperty("DB_MIGRATION_USER");
        String password = environment.getProperty("DB_MIGRATION_PASSWORD");

        // Re-check at the point of construction: HikariConfig's no-arg constructor reads
        // hikaricp.configurationFile, so a property set after the guard ran must still
        // stop the bean from being built.
        MigrateCredentialGuardConfiguration.requireNoHikariConfigurationFile(environment);

        // The no-arg constructor keeps the pool lazy: a missing database then fails when
        // Flyway upgrades, not while the application context is being built.
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("migrate-datasource");
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(2);
        dataSource.setMinimumIdle(1);
        dataSource.setConnectionTimeout(10_000);
        // Keep the documented UTC session convention for the migration connection.
        dataSource.setConnectionInitSql("SET time_zone = '+00:00'");
        return dataSource;
    }
}
