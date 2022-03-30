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

import io.questdb.cairo.*;
import io.questdb.griffin.model.IntervalUtils;
import io.questdb.griffin.update.UpdateExecution;
import io.questdb.mp.Sequence;
import io.questdb.std.*;
import io.questdb.std.str.LPSZ;
import io.questdb.std.str.Path;
import io.questdb.tasks.ColumnVersionPurgeTask;
import org.jetbrains.annotations.NotNull;
import org.junit.*;

import java.io.IOException;

public class UpdatePurgeColumnVersionTest extends AbstractGriffinTest {
    private UpdateExecution updateExecution;

    @Before
    public void setUpUpdates() {
        updateExecution = new UpdateExecution(configuration, engine.getMessageBus());
    }

    @After
    public void tearDownUpdate() {
        updateExecution = Misc.free(updateExecution);
    }

    @Test
    public void testManyUpdatesInserts() throws Exception {
        assertMemoryLeak(() -> {
            currentMicros = 0;
            columnVersionPurgeWaitExponent = 0.001;
            try (ColumnVersionPurgeJob purgeJob = createPurgeJob()) {
                compiler.compile("create table up_part_o3_many as" +
                        " (select timestamp_sequence('1970-01-01T02', 24 * 60 * 60 * 1000000L) ts," +
                        " x," +
                        " rnd_str('a', 'b', 'c', 'd') str," +
                        " rnd_symbol('A', 'B', 'C', 'D') sym1," +
                        " rnd_symbol('1', '2', '3', '4') sym2" +
                        " from long_sequence(5)), index(sym2)" +
                        " timestamp(ts) PARTITION BY DAY", sqlExecutionContext);

                try (TableReader rdr1 = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "up_part_o3_many")) {
                    compiler.compile("insert into up_part_o3_many " +
                            " select timestamp_sequence('1970-01-02T01', 24 * 60 * 60 * 1000000L) ts," +
                            " x," +
                            " rnd_str('a', 'b', 'c', 'd') str," +
                            " rnd_symbol('A', 'B', 'C', 'D') sym1," +
                            " rnd_symbol('1', '2', '3', '4') sym2" +
                            " from long_sequence(3)", sqlExecutionContext).execute(null).await();

                    try (TableReader rdr2 = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "up_part_o3_many")) {
                        // TODO: updates symbol columns
                        executeUpdate("UPDATE up_part_o3_many SET x = 100, str='u1' WHERE ts >= '1970-01-03'");
                        runPurgeJob(purgeJob);

                        try (TableReader rdr3 = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "up_part_o3_many")) {
                            executeUpdate("UPDATE up_part_o3_many SET x = 200, str='u2' WHERE x = 100");
                            runPurgeJob(purgeJob);
                            rdr3.openPartition(0);
                        }

                        rdr2.openPartition(0);
                    }
                    rdr1.openPartition(0);
                }

                try (Path path = new Path()) {
                    String[] partitions = new String[]{"1970-01-03.1", "1970-01-04.1", "1970-01-05"};
                    assertFilesExist(partitions, path, "up_part_o3_many", "", true);
                    assertFilesExist(partitions, path, "up_part_o3_many", ".2", true);
                    assertFilesExist(partitions, path, "up_part_o3_many", ".3", true);

                    runPurgeJob(purgeJob);

                    assertFilesExist(partitions, path, "up_part_o3_many", "", false);
                    assertFilesExist(partitions, path, "up_part_o3_many", ".2", false);
                    assertFilesExist(partitions, path, "up_part_o3_many", ".3", true);
                }

                assertSql(
                        "up_part_o3_many",
                        "ts\tx\tstr\tsym1\tsym2\n" +
                                "1970-01-01T02:00:00.000000Z\t1\ta\tC\t2\n" +
                                "1970-01-02T01:00:00.000000Z\t1\ta\tA\t2\n" +
                                "1970-01-02T02:00:00.000000Z\t2\td\tB\t4\n" +
                                "1970-01-03T01:00:00.000000Z\t200\tu2\tC\t4\n" +
                                "1970-01-03T02:00:00.000000Z\t200\tu2\tD\t3\n" +
                                "1970-01-04T01:00:00.000000Z\t200\tu2\tA\t2\n" +
                                "1970-01-04T02:00:00.000000Z\t200\tu2\tA\t1\n" +
                                "1970-01-05T02:00:00.000000Z\t200\tu2\tD\t2\n"
                );
            }
        });
    }

    @Test
    public void testPurgeIOFailureRetried() throws Exception {
        assertMemoryLeak(() -> {
            currentMicros = 0;
            ff = new FilesFacadeImpl() {
                int count = 0;

                @Override
                public boolean remove(LPSZ name) {
                    if (Chars.endsWith(name, "str.i")) {
                        if (count++ == 0) {
                            return false;
                        }
                    }
                    return Files.remove(name);
                }
            };

            columnVersionPurgeWaitExponent = 0.001;
            try (ColumnVersionPurgeJob purgeJob = createPurgeJob()) {
                compiler.compile("create table up_part as" +
                        " (select timestamp_sequence('1970-01-01', 24 * 60 * 60 * 1000000L) ts," +
                        " x," +
                        " rnd_str('a', 'b', 'c', 'd') str," +
                        " rnd_symbol('A', 'B', 'C', 'D') sym1," +
                        " rnd_symbol('1', '2', '3', '4') sym2" +
                        " from long_sequence(5)), index(sym2)" +
                        " timestamp(ts) PARTITION BY DAY", sqlExecutionContext);

                try (TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "up_part")) {
                    // TODO: updates symbol columns
                    executeUpdate("UPDATE up_part SET x = 100, str='abcd' WHERE ts >= '1970-01-02'");

                    runPurgeJob(purgeJob);
                    rdr.openPartition(0);
                }

                try (Path path = new Path()) {
                    String[] partitions = new String[]{"1970-01-02", "1970-01-03", "1970-01-04", "1970-01-05"};
                    assertFilesExist(partitions, path, "up_part", "", true);

                    runPurgeJob(purgeJob);
                    // Delete failure
                    path.of(configuration.getRoot()).concat("up_part").concat("1970-01-02").concat("str.i").$();
                    Assert.assertTrue(Chars.toString(path), FilesFacadeImpl.INSTANCE.exists(path));

                    // Should retry
                    runPurgeJob(purgeJob);
                    assertFilesExist(partitions, path, "up_part", "", false);
                }

                assertSql(
                        "up_part",
                        "ts\tx\tstr\tsym1\tsym2\n" +
                                "1970-01-01T00:00:00.000000Z\t1\ta\tC\t2\n" +
                                "1970-01-02T00:00:00.000000Z\t100\tabcd\tB\t4\n" +
                                "1970-01-03T00:00:00.000000Z\t100\tabcd\tD\t3\n" +
                                "1970-01-04T00:00:00.000000Z\t100\tabcd\tA\t1\n" +
                                "1970-01-05T00:00:00.000000Z\t100\tabcd\tD\t2\n"
                );
            }
        });
    }

    @Test
    public void testPurgeRespectsOpenReaderDailyPartitioned() throws Exception {
        assertMemoryLeak(() -> {
            currentMicros = 0;
            columnVersionPurgeWaitExponent = 0.001;
            try (ColumnVersionPurgeJob purgeJob = createPurgeJob()) {
                compiler.compile("create table up_part as" +
                        " (select timestamp_sequence('1970-01-01', 24 * 60 * 60 * 1000000L) ts," +
                        " x," +
                        " rnd_str('a', 'b', 'c', 'd') str," +
                        " rnd_symbol('A', 'B', 'C', 'D') sym1," +
                        " rnd_symbol('1', '2', '3', '4') sym2" +
                        " from long_sequence(5)), index(sym2)" +
                        " timestamp(ts) PARTITION BY DAY", sqlExecutionContext);

                try (TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "up_part")) {
                    // TODO: updates symbol columns
                    executeUpdate("UPDATE up_part SET x = 100, str='abcd' WHERE ts >= '1970-01-02'");

                    runPurgeJob(purgeJob);
                    rdr.openPartition(0);
                }

                try (Path path = new Path()) {
                    String[] partitions = new String[]{"1970-01-02", "1970-01-03", "1970-01-04", "1970-01-05"};
                    assertFilesExist(partitions, path, "up_part", "", true);

                    runPurgeJob(purgeJob);
                    assertFilesExist(partitions, path, "up_part", "", false);
                }

                assertSql(
                        "up_part",
                        "ts\tx\tstr\tsym1\tsym2\n" +
                                "1970-01-01T00:00:00.000000Z\t1\ta\tC\t2\n" +
                                "1970-01-02T00:00:00.000000Z\t100\tabcd\tB\t4\n" +
                                "1970-01-03T00:00:00.000000Z\t100\tabcd\tD\t3\n" +
                                "1970-01-04T00:00:00.000000Z\t100\tabcd\tA\t1\n" +
                                "1970-01-05T00:00:00.000000Z\t100\tabcd\tD\t2\n"
                );
            }
        });
    }

    @Test
    public void testPurgeRespectsOpenReaderNonPartitioned() throws Exception {
        assertMemoryLeak(() -> {
            currentMicros = 0;
            columnVersionPurgeWaitExponent = 0.001;
            try (ColumnVersionPurgeJob purgeJob = createPurgeJob()) {
                compiler.compile("create table up as" +
                        " (select timestamp_sequence(0, 1000000) ts," +
                        " x," +
                        " rnd_str('a', 'b', 'c', 'd') str," +
                        " rnd_symbol('A', 'B', 'C', 'D') sym1," +
                        " rnd_symbol('1', '2', '3', '4') sym2" +
                        " from long_sequence(5)), index(sym2)" +
                        " timestamp(ts)", sqlExecutionContext);

                try (TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "up")) {
                    // TODO: updates symbol columns
                    executeUpdate("UPDATE up SET x = 100, str='abcd'");

                    currentMicros = 20;
                    runPurgeJob(purgeJob);
                    rdr.openPartition(0);
                }

                try (Path path = new Path()) {
                    assertFilesExist(path, "up", "default", "", true);
                    currentMicros = 40;
                    runPurgeJob(purgeJob);
                    assertFilesExist(path, "up", "default", "", false);
                }

                assertSql(
                        "up",
                        "ts\tx\tstr\tsym1\tsym2\n" +
                                "1970-01-01T00:00:00.000000Z\t100\tabcd\tC\t2\n" +
                                "1970-01-01T00:00:01.000000Z\t100\tabcd\tB\t4\n" +
                                "1970-01-01T00:00:02.000000Z\t100\tabcd\tD\t3\n" +
                                "1970-01-01T00:00:03.000000Z\t100\tabcd\tA\t1\n" +
                                "1970-01-01T00:00:04.000000Z\t100\tabcd\tD\t2\n"
                );
            }
        });
    }

    @Test
    public void testPurgeWithOutOfOrderUpdate() throws Exception {
        assertMemoryLeak(() -> {
            currentMicros = 0;
            columnVersionPurgeWaitExponent = 0.001;
            try (ColumnVersionPurgeJob purgeJob = createPurgeJob()) {
                compiler.compile("create table up_part_o3 as" +
                        " (select timestamp_sequence('1970-01-01T02', 24 * 60 * 60 * 1000000L) ts," +
                        " x," +
                        " rnd_str('a', 'b', 'c', 'd') str," +
                        " rnd_symbol('A', 'B', 'C', 'D') sym1," +
                        " rnd_symbol('1', '2', '3', '4') sym2" +
                        " from long_sequence(5)), index(sym2)" +
                        " timestamp(ts) PARTITION BY DAY", sqlExecutionContext);

                compiler.compile("insert into up_part_o3 " +
                        " select timestamp_sequence('1970-01-02T01', 24 * 60 * 60 * 1000000L) ts," +
                        " x," +
                        " rnd_str('a', 'b', 'c', 'd') str," +
                        " rnd_symbol('A', 'B', 'C', 'D') sym1," +
                        " rnd_symbol('1', '2', '3', '4') sym2" +
                        " from long_sequence(3)", sqlExecutionContext).execute(null).await();

                try (TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "up_part_o3")) {
                    // TODO: updates symbol columns
                    executeUpdate("UPDATE up_part_o3 SET x = 100, str='abcd' WHERE ts >= '1970-01-03'");

                    currentMicros = 20;
                    runPurgeJob(purgeJob);
                    rdr.openPartition(0);
                }

                try (Path path = new Path()) {
                    String[] partitions = new String[]{"1970-01-03.1", "1970-01-04.1", "1970-01-05"};
                    assertFilesExist(partitions, path, "up_part_o3", "", true);

                    currentMicros = 40;
                    runPurgeJob(purgeJob);

                    assertFilesExist(partitions, path, "up_part_o3", "", false);
                }

                assertSql(
                        "up_part_o3",
                        "ts\tx\tstr\tsym1\tsym2\n" +
                                "1970-01-01T02:00:00.000000Z\t1\ta\tC\t2\n" +
                                "1970-01-02T01:00:00.000000Z\t1\ta\tA\t2\n" +
                                "1970-01-02T02:00:00.000000Z\t2\td\tB\t4\n" +
                                "1970-01-03T01:00:00.000000Z\t100\tabcd\tC\t4\n" +
                                "1970-01-03T02:00:00.000000Z\t100\tabcd\tD\t3\n" +
                                "1970-01-04T01:00:00.000000Z\t100\tabcd\tA\t2\n" +
                                "1970-01-04T02:00:00.000000Z\t100\tabcd\tA\t1\n" +
                                "1970-01-05T02:00:00.000000Z\t100\tabcd\tD\t2\n"
                );
            }
        });
    }

    @Test
    public void testSavesDataToPurgeLogTable() throws Exception {
        assertMemoryLeak(() -> {
            currentMicros = IntervalUtils.parseFloorPartialDate("2022-02-24T05:00");
            try (ColumnVersionPurgeJob purgeJob = createColumnVersionPurgeJob()) {
                ColumnVersionPurgeTask task = createTask("tbl_name", "col", 1, ColumnType.INT, 43, 11, "2022-03-29", -1);
                task.appendColumnVersion(-1, IntervalUtils.parseFloorPartialDate("2022-04-05"), 2);
                appendTaskToQueue(task);

                ColumnVersionPurgeTask task2 = createTask("tbl_name2", "col2", 2, ColumnType.SYMBOL, 33, -1, "2022-02-13", 3);
                appendTaskToQueue(task2);

                purgeJob.run(0);
                assertSql(purgeJob.getLogTableName(), "ts\ttable_name\tcolumn_name\ttable_id\tcolumnType\ttable_partition_by\tupdated_txn\tcolumn_version\tpartition_timestamp\tpartition_name_txn\n" +
                        "2022-02-24T05:00:00.000000Z\ttbl_name\tcol\t1\t5\t3\t43\t11\t2022-03-29T00:00:00.000000Z\t-1\n" +
                        "2022-02-24T05:00:00.000000Z\ttbl_name\tcol\t1\t5\t3\t43\t-1\t2022-04-05T00:00:00.000000Z\t2\n" +
                        "2022-02-24T05:00:00.000001Z\ttbl_name2\tcol2\t2\t12\t3\t33\t-1\t2022-02-13T00:00:00.000000Z\t3\n");
            }
        });
    }

    private void appendTaskToQueue(ColumnVersionPurgeTask task) {
        long cursor = -1L;
        Sequence pubSeq = engine.getMessageBus().getColumnVersionPurgePubSeq();
        while (cursor < 0) {
            cursor = pubSeq.next();
            if (cursor > -1L) {
                ColumnVersionPurgeTask queueTask = engine.getMessageBus().getColumnVersionPurgeQueue().get(cursor);
                queueTask.copyFrom(task);
                pubSeq.done(cursor);
            }
        }
    }

    private void assertFilesExist(String[] partitions, Path path, String up_part, String colSuffix, boolean exist) {
        for (int i = 0; i < partitions.length; i++) {
            String partition = partitions[i];
            assertFilesExist(path, up_part, partition, colSuffix, exist);
        }
    }

    private void assertFilesExist(Path path, String up_part, String partition, String colSuffix, boolean exist) {
        path.of(configuration.getRoot()).concat(up_part).concat(partition).concat("x.d").put(colSuffix).$();
        Assert.assertEquals(Chars.toString(path), exist, FilesFacadeImpl.INSTANCE.exists(path));

        path.of(configuration.getRoot()).concat(up_part).concat(partition).concat("str.d").put(colSuffix).$();
        Assert.assertEquals(Chars.toString(path), exist, FilesFacadeImpl.INSTANCE.exists(path));

        path.of(configuration.getRoot()).concat(up_part).concat(partition).concat("str.i").put(colSuffix).$();
        Assert.assertEquals(Chars.toString(path), exist, FilesFacadeImpl.INSTANCE.exists(path));
    }

    private ColumnVersionPurgeExecution createColumnVersionCleanExecution() {
        return new ColumnVersionPurgeExecution(configuration);
    }

    private ColumnVersionPurgeJob createColumnVersionPurgeJob() throws SqlException {
        return new ColumnVersionPurgeJob(engine, null);
    }

    @NotNull
    private ColumnVersionPurgeJob createPurgeJob() throws SqlException {
        return new ColumnVersionPurgeJob(engine, null);
    }

    private ColumnVersionPurgeTask createTask(
            String tblName,
            String colName,
            int tableId,
            int columnType,
            long updateTxn,
            long columnVersion,
            String partitionTs,
            long partitionNameTxn
    ) throws NumericException {
        ColumnVersionPurgeTask tsk = new ColumnVersionPurgeTask();
        tsk.of(tblName, colName, tableId, columnType, PartitionBy.NONE, updateTxn, new LongList());
        tsk.appendColumnVersion(columnVersion, IntervalUtils.parseFloorPartialDate(partitionTs), partitionNameTxn);
        return tsk;
    }

    private void executeUpdate(String query) throws SqlException {
        CompiledQuery cc = compiler.compile(query, sqlExecutionContext);
        Assert.assertEquals(CompiledQuery.UPDATE, cc.getType());
        cc.execute(null);
    }

    private void runPurgeJob(ColumnVersionPurgeJob purgeJob) {
        currentMicros += currentMicros + 20;
        engine.releaseInactive();
        purgeJob.run(0);
    }
}
