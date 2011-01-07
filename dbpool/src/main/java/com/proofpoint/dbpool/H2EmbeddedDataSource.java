package com.proofpoint.dbpool;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.io.Resources;
import com.google.inject.Inject;
import com.proofpoint.dbpool.H2EmbeddedDataSourceConfig.Cipher;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.util.ScriptReader;

import java.io.File;
import java.io.Reader;
import java.io.FileNotFoundException;
import static java.lang.Math.ceil;

import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static java.util.concurrent.TimeUnit.SECONDS;

public class H2EmbeddedDataSource extends ManagedDataSource
{
    @Inject
    public H2EmbeddedDataSource(H2EmbeddedDataSourceConfig config)
            throws Exception
    {
        super(createH2EmbeddedConnectionPoolDataSource(config),
                config.getMaxConnections(),
                config.getMaxConnectionWait());

        Preconditions.checkNotNull(config.getFilename());
        if (config.getFilename().isEmpty()) {
            throw new IllegalArgumentException("filename is empty");
        }

        Connection connection = getConnection();
        try {
            setConfig(connection, "CACHE_SIZE", config.getCacheSize());
            setConfig(connection, "COMPRESS_LOB", config.getCompressLob());
            setConfig(connection, "DB_CLOSE_DELAY ", "-1");

            String fileName = config.getInitScript();

            File file = new File(fileName);
            URL url;
            if (file.exists()) {
                url = file.toURI().toURL();
            }
            else {
                url = Resources.getResource(fileName);
            }

            if (url == null) {
                throw new FileNotFoundException(fileName);
            }

            Reader reader = Resources.newReaderSupplier(url, Charsets.UTF_8).getInput();
            try {
                ScriptReader scriptReader = new ScriptReader(reader);
                for (String statement = scriptReader.readStatement(); statement != null; statement = scriptReader.readStatement()) {
                    executeCommand(connection, statement);
                }
            }
            finally {
                reader.close();
            }

            // run last so script can contain literals
            setConfig(connection, "ALLOW_LITERALS", config.getAllowLiterals());
        }
        finally {
            closeQuietly(connection);
        }
    }

    private static JdbcDataSource createH2EmbeddedConnectionPoolDataSource(H2EmbeddedDataSourceConfig config)
            throws Exception
    {

        StringBuilder urlBuilder = new StringBuilder()
                .append("jdbc:h2:").append(config.getFilename())
                .append(";ALLOW_LITERALS=").append(config.getAllowLiterals())
                .append(";CACHE_SIZE=").append(config.getCacheSize());

        if (config.getCipher() != Cipher.NONE) {
            urlBuilder.append(";CIPHER=").append(config.getCipher());
        }

        String url = urlBuilder.toString();

        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        dataSource.setUser(config.getUsername());
        if (config.getCipher() != Cipher.NONE) {
            dataSource.setPassword(config.getFilePassword() + " " + config.getPassword());
        }
        else {
            dataSource.setPassword(config.getPassword());
        }
        dataSource.setLoginTimeout((int) ceil(config.getMaxConnectionWait().convertTo(SECONDS)));

        return dataSource;
    }

    private void setConfig(Connection connection, String name, Object value)
            throws SQLException
    {
        Statement statement = connection.createStatement();
        try {
            String command = String.format("SET %s %s", name, value);
            int count = statement.executeUpdate(command);
            if (count != 0) {
                throw new SQLException("Failed to execute command: " + command);
            }
        }
        finally {
            closeQuietly(statement);
        }
    }


    private void executeCommand(Connection connection, String command)
            throws SQLException
    {
        Statement statement = connection.createStatement();
        try {
            statement.executeUpdate(command);
        }
        finally {
            closeQuietly(statement);
        }
    }

    private static void closeQuietly(Statement statement)
    {
        try {
            statement.close();
        }
        catch (SQLException ignored) {
        }
    }

    private static void closeQuietly(Connection connection)
    {
        try {
            connection.close();
        }
        catch (SQLException ignored) {
        }
    }
}
