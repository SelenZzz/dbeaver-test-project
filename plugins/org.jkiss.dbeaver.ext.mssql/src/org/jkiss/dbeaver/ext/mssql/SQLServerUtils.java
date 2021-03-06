/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2021 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jkiss.dbeaver.ext.mssql;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.mssql.model.*;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.exec.DBCEntityMetaData;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCDataSource;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.impl.jdbc.exec.JDBCConnectionImpl;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.rdb.DBSCatalog;
import org.jkiss.dbeaver.model.struct.rdb.DBSForeignKeyModifyRule;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.utils.CommonUtils;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * SQLServerUtils
 */
public class SQLServerUtils {

    private static final Log log = Log.getLog(SQLServerUtils.class);


    public static boolean isDriverSqlServer(DBPDriver driver) {
        return driver.getSampleURL().contains(":sqlserver");
    }

    public static boolean isDriverGeneric(DBPDriver driver) {
        return driver.getId().contains("generic");
    }

    public static boolean isDriverAzure(DBPDriver driver) {
        return driver.getId().contains("azure");
    }

    public static boolean isDriverJtds(DBPDriver driver) {
        return driver.getSampleURL().startsWith("jdbc:jtds");
    }

    public static boolean isWindowsAuth(DBPConnectionConfiguration connectionInfo) {
        return CommonUtils.toBoolean(connectionInfo.getProviderProperty(SQLServerConstants.PROP_CONNECTION_WINDOWS_AUTH)) ||
                CommonUtils.toBoolean(connectionInfo.getProperties().get(SQLServerConstants.PROP_CONNECTION_INTEGRATED_SECURITY));
    }

    public static boolean isActiveDirectoryAuth(DBPConnectionConfiguration connectionInfo) {
        return SQLServerConstants.AUTH_ACTIVE_DIRECTORY_PASSWORD.equals(
            connectionInfo.getProperty(SQLServerConstants.PROP_CONNECTION_AUTHENTICATION));
    }

    public static void setCurrentDatabase(JDBCSession session, String schema) throws SQLException {
        JDBCUtils.executeSQL(session,
            "use " + DBUtils.getQuotedIdentifier(session.getDataSource(), schema));
    }

    public static void setCurrentSchema(JDBCSession session, String currentUser, String schema) throws SQLException {
        if (!CommonUtils.isEmpty(currentUser)) {
            JDBCUtils.executeSQL(session,
                "alter user " + DBUtils.getQuotedIdentifier(session.getDataSource(), currentUser) +
                    " with default_schema = " + DBUtils.getQuotedIdentifier(session.getDataSource(), schema));
        }
    }

    public static String getCurrentUser(JDBCSession session) throws SQLException {
        // See https://stackoverflow.com/questions/4101863/sql-server-current-user-name
        return JDBCUtils.queryString(
            session,
            "select original_login()");
    }

    public static String getCurrentDatabase(JDBCSession session) throws SQLException {
        return JDBCUtils.queryString(
            session,
            "select db_name()");
    }

    public static String getCurrentSchema(JDBCSession session) throws SQLException {
        return JDBCUtils.queryString(
            session,
            "select schema_name()");
    }

    public static boolean isShowAllSchemas(DBPDataSource dataSource) {
        return CommonUtils.toBoolean(dataSource.getContainer().getConnectionConfiguration().getProviderProperty(SQLServerConstants.PROP_SHOW_ALL_SCHEMAS));
    }

    public static boolean supportsCrossDatabaseQueries(JDBCDataSource dataSource) {
        boolean isSqlServer = isDriverSqlServer(dataSource.getContainer().getDriver());
        if (isSqlServer && !dataSource.isServerVersionAtLeast(SQLServerConstants.SQL_SERVER_2005_VERSION_MAJOR,0)) {
            return false;
        }
        boolean isDriverAzure = isSqlServer && isDriverAzure(dataSource.getContainer().getDriver());
        if (isDriverAzure && !dataSource.isServerVersionAtLeast(SQLServerConstants.SQL_SERVER_2012_VERSION_MAJOR, 0)) {
            return false;
        }
        return true;
    }

    public static String getSystemSchemaFQN(JDBCDataSource dataSource, String catalog, String systemSchema) {
        return catalog != null && supportsCrossDatabaseQueries(dataSource) ?
                DBUtils.getQuotedIdentifier(dataSource, catalog) + "." + systemSchema :
                systemSchema;
    }

    public static String getSystemTableName(SQLServerDatabase database, String tableName) {
        return SQLServerUtils.getSystemSchemaFQN(database.getDataSource(), database.getName(), SQLServerConstants.SQL_SERVER_SYSTEM_SCHEMA) + "." + tableName;
    }

    public static String getSystemTableFQN(@NotNull JDBCDataSource dataSource, @NotNull DBSCatalog database, @NotNull String tableName, boolean isSQLServer) {
        return SQLServerUtils.getSystemSchemaFQN(
            dataSource,
            database.getName(),
            isSQLServer? SQLServerConstants.SQL_SERVER_SYSTEM_SCHEMA : SQLServerConstants.SYBASE_SYSTEM_SCHEMA)
            + "." + tableName;
    }

    public static String getExtendedPropsTableName(SQLServerDatabase database) {
        return getSystemTableName(database, SQLServerConstants.SYS_TABLE_EXTENDED_PROPERTIES);
    }

    public static DBSForeignKeyModifyRule getForeignKeyModifyRule(int actionNum) {
        switch (actionNum) {
            case 0: return DBSForeignKeyModifyRule.NO_ACTION;
            case 1: return DBSForeignKeyModifyRule.CASCADE;
            case 2: return DBSForeignKeyModifyRule.SET_NULL;
            case 3: return DBSForeignKeyModifyRule.SET_DEFAULT;
            default:
                return DBSForeignKeyModifyRule.NO_ACTION;
        }
    }

    public static String extractSource(@NotNull DBRProgressMonitor monitor, @NotNull SQLServerDatabase database, @NotNull SQLServerObject object) throws DBException {
        SQLServerDataSource dataSource = database.getDataSource();
        String systemSchema = getSystemSchemaFQN(dataSource, database.getName(), SQLServerConstants.SQL_SERVER_SYSTEM_SCHEMA);
        try (JDBCSession session = DBUtils.openMetaSession(monitor, dataSource, "Read source code")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement("SELECT definition FROM " + systemSchema + ".sql_modules WHERE object_id = ?")) {
                dbStat.setLong(1, object.getObjectId());
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    StringBuilder sql = new StringBuilder();
                    while (dbResult.nextRow()) {
                        sql.append(dbResult.getString(1));
                    }
                    return sql.toString();
                }
            }
        } catch (SQLException e) {
            throw new DBException(e, dataSource);
        }
    }

    public static String extractSource(@NotNull DBRProgressMonitor monitor, @NotNull SQLServerSchema schema, @NotNull  String objectName) throws DBException {
        SQLServerDataSource dataSource = schema.getDataSource();
        String systemSchema = getSystemSchemaFQN(dataSource, schema.getDatabase().getName(), SQLServerConstants.SQL_SERVER_SYSTEM_SCHEMA);
        try (JDBCSession session = DBUtils.openMetaSession(monitor, dataSource, "Read source code")) {
            String objectFQN = DBUtils.getQuotedIdentifier(dataSource, schema.getName()) + "." + DBUtils.getQuotedIdentifier(dataSource, objectName);
            String sqlQuery = systemSchema + ".sp_helptext '" + objectFQN + "'";
            if (dataSource.isDataWarehouseServer(monitor)) {
                sqlQuery = "SELECT definition FROM sys.sql_modules WHERE object_id = (OBJECT_ID(N'" + objectFQN + "'))";
            }
            try (JDBCPreparedStatement dbStat = session.prepareStatement(sqlQuery)) {
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    StringBuilder sql = new StringBuilder();
                    while (dbResult.nextRow()) {
                        sql.append(dbResult.getString(1));
                    }
                    return sql.toString();
                }
            }
        } catch (SQLException e) {
            throw new DBException(e, dataSource);
        }
    }

    public static boolean isCommentSet(DBRProgressMonitor monitor, SQLServerDatabase database, SQLServerObjectClass objectClass, long majorId, long minorId) {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, database, "Check extended property")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT 1 FROM "+ SQLServerUtils.getExtendedPropsTableName(database) +
                " WHERE [class] = ? AND [major_id] = ? AND [minor_id] = ? AND [name] = N'MS_Description'")) {
                dbStat.setInt(1, objectClass.getClassId());
                dbStat.setLong(2, majorId);
                dbStat.setLong(3, minorId);
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                        return true;
                    }
                    return false;
                }
            }
        } catch (Throwable e) {
            log.debug("Error checking extended property in dictionary", e);
            return false;
        }
    }

    @NotNull
    public static SQLServerAuthentication detectAuthSchema(DBPConnectionConfiguration connectionInfo) {
        // Detect auth schema
        // Now we use only PROP_AUTHENTICATION but here we support all legacy SQL Server configs
        SQLServerAuthentication auth = isWindowsAuth(connectionInfo) ? SQLServerAuthentication.WINDOWS_INTEGRATED :
            (isActiveDirectoryAuth(connectionInfo) ? SQLServerAuthentication.AD_PASSWORD : SQLServerAuthentication.SQL_SERVER_PASSWORD);

        {
            String authProp = connectionInfo.getProviderProperty(SQLServerConstants.PROP_AUTHENTICATION);
            if (authProp != null) {
                try {
                    auth = SQLServerAuthentication.valueOf(authProp);
                } catch (IllegalArgumentException e) {
                    log.warn("Bad auth schema: " + authProp);
                }
            }
        }

        return auth;
    }

    public static String changeCreateToAlterDDL(SQLDialect sqlDialect, String ddl) {
        String firstKeyword = SQLUtils.getFirstKeyword(sqlDialect, ddl);
        if ("CREATE".equalsIgnoreCase(firstKeyword)) {
            return ddl.replaceFirst(firstKeyword, "ALTER");
        }
        return ddl;
    }

    public static boolean isTableType(SQLServerTableBase table) {
        return table instanceof SQLServerTableType;
    }

    public static SQLServerTableBase getTableFromQuery(DBCSession session, SQLQuery sqlQuery, SQLServerDataSource dataSource) throws DBException, SQLException {
        DBCEntityMetaData singleSource = sqlQuery.getSingleSource();
        String catalogName = null;
        if (singleSource != null) {
            catalogName = singleSource.getCatalogName();
        }
        Connection original = null;
        if (session instanceof JDBCConnectionImpl) {
            original = ((JDBCConnectionImpl) session).getOriginal();
        }
        if (catalogName == null && original != null) {
            catalogName = original.getCatalog();
        }
        if (catalogName != null) {
            SQLServerDatabase database = dataSource.getDatabase(catalogName);
            String schemaName = null;
            if (singleSource != null) {
                schemaName = singleSource.getSchemaName();
            }
            if (schemaName == null && original != null) {
                schemaName = original.getSchema();
            }
            if (database != null && schemaName != null) {
                SQLServerSchema schema = database.getSchema(schemaName);
                if (schema != null && singleSource != null) {
                    return schema.getTable(session.getProgressMonitor(), singleSource.getEntityName());
                }
            }
        }
        return null;
    }

    public static JDBCPreparedStatement prepareTableStatisticLoadStatement(@NotNull JDBCSession session, @NotNull JDBCDataSource dataSource, @NotNull DBSCatalog catalog, long schemaId, @Nullable DBSTable table, boolean isSQLServer) throws SQLException {
        String query;
        if (isSQLServer) {
            query = "SELECT t.name, p.rows, SUM(a.total_pages) * 8 AS totalSize, SUM(a.used_pages) * 8 AS usedSize\n" +
                "FROM " + SQLServerUtils.getSystemTableFQN(dataSource, catalog, "tables", true) + " t\n" +
                "INNER JOIN " + SQLServerUtils.getSystemTableFQN(dataSource, catalog, "indexes", true) + " i ON t.OBJECT_ID = i.object_id\n" +
                "INNER JOIN " + SQLServerUtils.getSystemTableFQN(dataSource, catalog, "partitions", true) + " p ON i.object_id = p.OBJECT_ID AND i.index_id = p.index_id\n" +
                "INNER JOIN " + SQLServerUtils.getSystemTableFQN(dataSource, catalog, "allocation_units", true) + " a ON p.partition_id = a.container_id\n" +
                "LEFT OUTER JOIN " + SQLServerUtils.getSystemTableFQN(dataSource, catalog, "schemas", true) + " s ON t.schema_id = s.schema_id\n" +
                "WHERE t.schema_id = ?\n" + (table != null ? "AND t.object_id=?\n" : "") +
                "GROUP BY t.name, p.rows";
        } else {
            query = "SELECT convert(varchar(100),o.name) AS 'name',\n" +
                "row_count(db_id(), o.id) AS 'rows',\n" +
                "data_pages(db_id(), o.id, 0) AS 'pages',\n" +
                "data_pages(db_id(), o.id, 0) * (@@maxpagesize) AS 'totalSize'\n" +
                "FROM " + SQLServerUtils.getSystemTableFQN(dataSource, catalog, "sysobjects", false) +  " o\n" +
                "WHERE type = 'U'\n" +
                "AND o.uid = ?\n" +
                (table != null ? " AND 'name'=?\n" : "") +
                "ORDER BY 'name'";
        }
        JDBCPreparedStatement dbStat = session.prepareStatement(query);
        dbStat.setLong(1, schemaId);
        if (table != null) {
            if (isSQLServer) {
                SQLServerTable sqlServerTable = (SQLServerTable) table;
                dbStat.setLong(2, sqlServerTable.getObjectId());
            } else {
                dbStat.setString(2, table.getName());
            }
        }
        return dbStat;
    }

}
