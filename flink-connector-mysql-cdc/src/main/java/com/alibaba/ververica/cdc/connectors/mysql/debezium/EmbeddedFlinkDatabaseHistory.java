/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.ververica.cdc.connectors.mysql.debezium;

import com.alibaba.ververica.cdc.connectors.mysql.debezium.task.context.StatefulTaskContext;
import com.alibaba.ververica.cdc.connectors.mysql.source.split.MySqlSplitState;
import io.debezium.config.Configuration;
import io.debezium.relational.TableId;
import io.debezium.relational.Tables;
import io.debezium.relational.ddl.DdlParser;
import io.debezium.relational.history.DatabaseHistory;
import io.debezium.relational.history.DatabaseHistoryException;
import io.debezium.relational.history.DatabaseHistoryListener;
import io.debezium.relational.history.HistoryRecord;
import io.debezium.relational.history.HistoryRecordComparator;
import io.debezium.relational.history.JsonTableChangeSerializer;
import io.debezium.relational.history.TableChanges;
import io.debezium.relational.history.TableChanges.TableChange;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.alibaba.ververica.cdc.connectors.mysql.debezium.task.context.StatefulTaskContext.SchemaStateUtils.registerHistory;
import static com.alibaba.ververica.cdc.connectors.mysql.debezium.task.context.StatefulTaskContext.SchemaStateUtils.retrieveHistory;

/**
 * A {@link DatabaseHistory} implementation which store the latest table schema in Flink state.
 *
 * <p>It stores/recovers history using data offered by {@link MySqlSplitState}.
 */
public class EmbeddedFlinkDatabaseHistory implements DatabaseHistory {

    public static final String DATABASE_HISTORY_INSTANCE_NAME = "database.history.instance.name";

    private final JsonTableChangeSerializer tableChangesSerializer =
            new JsonTableChangeSerializer();

    private ConcurrentMap<TableId, TableChange> tableSchemas;
    private String instanceName;
    private DatabaseHistoryListener listener;
    private boolean storeOnlyMonitoredTablesDdl;
    private boolean skipUnparseableDDL;
    private boolean useCatalogBeforeSchema;

    @Override
    public void configure(
            Configuration config,
            HistoryRecordComparator comparator,
            DatabaseHistoryListener listener,
            boolean useCatalogBeforeSchema) {
        this.instanceName = config.getString(DATABASE_HISTORY_INSTANCE_NAME);
        this.listener = listener;
        this.storeOnlyMonitoredTablesDdl = config.getBoolean(STORE_ONLY_MONITORED_TABLES_DDL);
        this.skipUnparseableDDL = config.getBoolean(SKIP_UNPARSEABLE_DDL_STATEMENTS);
        this.useCatalogBeforeSchema = useCatalogBeforeSchema;

        // recover
        this.tableSchemas = new ConcurrentHashMap<>();
        for (TableChange tableChange : retrieveHistory(instanceName)) {
            tableSchemas.put(tableChange.getId(), tableChange);
        }
        // register
        registerHistory(instanceName, tableSchemas.values());
    }

    @Override
    public void start() {
        listener.started();
    }

    @Override
    public void record(
            Map<String, ?> source, Map<String, ?> position, String databaseName, String ddl)
            throws DatabaseHistoryException {
        throw new UnsupportedOperationException(
                "The FlinkDatabaseSchemaHistory needs debezium provides the schema.");
    }

    @Override
    public void record(
            Map<String, ?> source,
            Map<String, ?> position,
            String databaseName,
            String schemaName,
            String ddl,
            TableChanges changes)
            throws DatabaseHistoryException {
        final HistoryRecord record =
                new HistoryRecord(source, position, databaseName, schemaName, ddl, changes);
        for (TableChange change : changes) {
            switch (change.getType()) {
                case CREATE:
                case ALTER:
                    tableSchemas.put(change.getId(), change);
                    break;
                case DROP:
                    tableSchemas.remove(change.getId());
                    break;
                default:
                    // impossible
                    throw new RuntimeException(
                            String.format("Unknown change type: %s.", change.getType()));
            }
        }
        listener.onChangeApplied(record);
    }

    @Override
    public void recover(
            Map<String, ?> source, Map<String, ?> position, Tables schema, DdlParser ddlParser) {
        listener.recoveryStarted();
        for (TableChange tableChange : tableSchemas.values()) {
            schema.overwriteTable(tableChange.getTable());
        }
        listener.recoveryStopped();
    }

    @Override
    public void stop() {
        if (instanceName != null) {
            StatefulTaskContext.SchemaStateUtils.removeHistory(instanceName);
        }
        listener.stopped();
    }

    @Override
    public boolean exists() {
        return tableSchemas != null && !tableSchemas.isEmpty();
    }

    @Override
    public boolean storageExists() {
        return true;
    }

    @Override
    public void initializeStorage() {
        // do nothing
    }

    @Override
    public boolean storeOnlyMonitoredTables() {
        return storeOnlyMonitoredTablesDdl;
    }

    @Override
    public boolean skipUnparseableDdlStatements() {
        return skipUnparseableDDL;
    }
}
