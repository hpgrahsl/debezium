/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.postgresql;

import static org.junit.Assert.assertNotNull;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.stream.Collectors;

import io.debezium.config.Configuration;
import io.debezium.connector.postgresql.connection.PostgresConnection;
import io.debezium.connector.postgresql.connection.ReplicationConnection;
import io.debezium.jdbc.JdbcConfiguration;

/**
 * A utility for integration test cases to connect the PostgreSQL server running in the Docker container created by this module's
 * build.
 *
 * @author Horia Chiorean
 */
public final class TestHelper {

    protected static final String TEST_SERVER = "test_server";
    protected static final String PK_FIELD = "pk";

    private TestHelper() {
    }

    /**
     * Obtain a replication connection instance for the given slot name.
     *
     * @param slotName the name of the logical decoding slot
     * @param dropOnClose true if the slot should be dropped upon close
     * @return the PostgresConnection instance; never null
     * @throws SQLException if there is a problem obtaining a replication connection
     */
    public static ReplicationConnection createForReplication(String slotName, boolean dropOnClose) throws SQLException {
        return ReplicationConnection.builder(defaultJdbcConfig())
                                    .withSlot(slotName)
                                    .dropSlotOnClose(dropOnClose)
                                    .build();
    }

    /**
     * Obtain a default DB connection.
     *
     * @return the PostgresConnection instance; never null
     */
    public static PostgresConnection create() {
        return new PostgresConnection(defaultJdbcConfig());
    }

    /**
     * Executes a JDBC statement using the default jdbc config without autocommitting the connection
     *
     * @param statement an array of statement
     */
    public static void execute(String statement) {
        try (PostgresConnection connection = create()) {
            connection.setAutoCommit(false);
            connection.executeWithoutCommitting(statement);
            Connection jdbcConn = connection.connection();
            if (!statement.endsWith("ROLLBACK;")) {
                jdbcConn.commit();
            } else {
                jdbcConn.rollback();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Drops all the public non system schemas from the DB.
     *
     * @throws SQLException if anything fails.
     */
    public static void dropAllSchemas() throws SQLException {
        String lineSeparator = System.lineSeparator();
        Set<String> schemaNames = schemaNames();
        if (!schemaNames.contains(PostgresSchema.PUBLIC_SCHEMA_NAME)) {
            schemaNames.add(PostgresSchema.PUBLIC_SCHEMA_NAME);
        }
        String dropStmts = schemaNames.stream()
                                      .map(schema -> "\"" + schema.replaceAll("\"", "\"\"") + "\"")
                                      .map(schema -> "DROP SCHEMA IF EXISTS " + schema + " CASCADE;")
                                      .collect(Collectors.joining(lineSeparator));
        TestHelper.execute(dropStmts);
    }

    protected static Set<String> schemaNames() throws SQLException {
        try (PostgresConnection connection = create()) {
            return connection.readAllSchemaNames(Filters.IS_SYSTEM_SCHEMA.negate());
        }
    }

    private static JdbcConfiguration defaultJdbcConfig() {
        return JdbcConfiguration.copy(Configuration.fromSystemProperties("database."))
                                .withDefault(JdbcConfiguration.DATABASE, "postgres")
                                .withDefault(JdbcConfiguration.HOSTNAME, "localhost")
                                .withDefault(JdbcConfiguration.PORT, 5432)
                                .withDefault(JdbcConfiguration.USER, "postgres")
                                .withDefault(JdbcConfiguration.PASSWORD, "postgres")
                                .build();
    }

    protected static Configuration.Builder defaultConfig() {
        JdbcConfiguration jdbcConfiguration = defaultJdbcConfig();
        Configuration.Builder builder = Configuration.create();
        jdbcConfiguration.forEach((field, value) -> builder.with(PostgresConnectorConfig.DATABASE_CONFIG_PREFIX + field, value));
        return builder.with(PostgresConnectorConfig.SERVER_NAME, TEST_SERVER)
                      .with(PostgresConnectorConfig.DROP_SLOT_ON_STOP, true)
                      .with(PostgresConnectorConfig.STATUS_UPDATE_INTERVAL_MS, 100);
    }

    protected static void executeDDL(String ddlFile) throws Exception {
        URL ddlTestFile = TestHelper.class.getClassLoader().getResource(ddlFile);
        assertNotNull("Cannot locate " + ddlFile, ddlTestFile);
        String statements = Files.readAllLines(Paths.get(ddlTestFile.toURI()))
                                 .stream()
                                 .collect(Collectors.joining(System.lineSeparator()));
        try (PostgresConnection connection = create()) {
            connection.executeWithoutCommitting(statements);
        }
    }

    protected static String topicName(String suffix) {
        return TestHelper.TEST_SERVER + "." + suffix;
    }
}
