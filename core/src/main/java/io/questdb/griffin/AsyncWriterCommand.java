/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.cairo.TableStructureChangesException;
import io.questdb.cairo.TableWriter;
import io.questdb.tasks.TableWriterTask;

public interface AsyncWriterCommand {
    int getTableNamePosition();
    int getTableId();
    long getTableVersion();
    String getTableName();
    String getCommandName();
    void setCommandCorrelationId(long correlationId);

    void serialize(TableWriterTask task);
    AsyncWriterCommand deserialize(TableWriterTask task);

    long apply(TableWriter tableWriter, boolean acceptStructureChange) throws SqlException, TableStructureChangesException;

    default long apply(TableWriter tableWriter) throws SqlException, TableStructureChangesException {
        return apply(tableWriter, false);
    }
}