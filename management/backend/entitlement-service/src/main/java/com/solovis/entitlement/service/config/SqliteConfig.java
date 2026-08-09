package com.solovis.entitlement.service.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(EntitlementDatabaseProperties.class)
public class SqliteConfig {

	@Bean
	@Primary
	public DataSource entitlementWriteDataSource(EntitlementDatabaseProperties properties) {
		return dataSource(properties, properties.writePoolSize(), "entitlement-write");
	}

	@Bean
	public DataSource entitlementReadDataSource(EntitlementDatabaseProperties properties) {
		return dataSource(properties, properties.readPoolSize(), "entitlement-read");
	}

	private static DataSource dataSource(EntitlementDatabaseProperties properties, int poolSize, String poolName) {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(properties.jdbcUrl());
		config.setDriverClassName("org.sqlite.JDBC");
		config.setMaximumPoolSize(poolSize);
		config.setPoolName(poolName);
		return new HikariDataSource(config);
	}

	@Bean
	public JdbcClient entitlementWriteJdbcClient(
			@Qualifier("entitlementWriteDataSource") DataSource entitlementWriteDataSource) {
		return JdbcClient.create(entitlementWriteDataSource);
	}

	@Bean
	public JdbcClient entitlementReadJdbcClient(
			@Qualifier("entitlementReadDataSource") DataSource entitlementReadDataSource) {
		return JdbcClient.create(entitlementReadDataSource);
	}

	@Bean
	public PlatformTransactionManager entitlementTransactionManager(
			@Qualifier("entitlementWriteDataSource") DataSource entitlementWriteDataSource) {
		return new DataSourceTransactionManager(entitlementWriteDataSource);
	}
}
