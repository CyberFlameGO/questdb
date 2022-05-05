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

package io.questdb.cutlass.http;

import io.questdb.cairo.*;
import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.cairo.sql.OperationFuture;
import io.questdb.griffin.QueryFutureUpdateListener;
import io.questdb.griffin.SqlCompiler;
import io.questdb.griffin.SqlExecutionContextImpl;
import io.questdb.griffin.engine.functions.bind.BindVariableServiceImpl;
import io.questdb.mp.SOCountDownLatch;
import io.questdb.network.Net;
import io.questdb.std.Chars;
import io.questdb.std.FilesFacadeImpl;
import io.questdb.std.Os;
import io.questdb.std.datetime.microtime.MicrosecondClock;
import io.questdb.std.str.LPSZ;
import io.questdb.std.str.StringSink;
import io.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

public class DispatcherWriterQueueTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();
    private SqlCompiler compiler;
    private SqlExecutionContextImpl sqlExecutionContext;
    private Error error = null;

    public void setupSql(CairoEngine engine) {
        compiler = new SqlCompiler(engine);
        BindVariableServiceImpl bindVariableService = new BindVariableServiceImpl(engine.getConfiguration());
        sqlExecutionContext = new SqlExecutionContextImpl(engine, 1);
        sqlExecutionContext.with(
                AllowAllCairoSecurityContext.INSTANCE,
                bindVariableService,
                null,
                -1,
                null);
        bindVariableService.clear();
    }

    @Test
    public void testAlterTableAddCacheAlterCache() throws Exception {
        runAlterOnBusyTable((writer, rdr) -> {
                    TableWriterMetadata metadata = writer.getMetadata();
                    int columnIndex = metadata.getColumnIndex("s");
                    Assert.assertTrue("Column s must exist", columnIndex >= 0);
                    Assert.assertTrue(rdr.getSymbolMapReader(columnIndex).isCached());
                },
                1,
                0,
                "alter+table+<x>+alter+column+s+cache");
    }

    @Test
    public void testAlterTableAddColumn() throws Exception {
        runAlterOnBusyTable((writer, rdr) -> {
                    TableWriterMetadata metadata = writer.getMetadata();
                    int columnIndex = metadata.getColumnIndex("y");
                    Assert.assertEquals(2, columnIndex);
                    Assert.assertEquals(ColumnType.INT, metadata.getColumnType(columnIndex));
                },
                1,
                0,
                "alter+table+<x>+add+column+y+int");
    }

    @Test
    public void testAlterTableAddDisconnect() throws Exception {
        SOCountDownLatch alterAckReceived = new SOCountDownLatch(1);
        SOCountDownLatch disconnectLatch = new SOCountDownLatch(1);

        HttpQueryTestBuilder queryTestBuilder = new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withWorkerCount(1)
                .withHttpServerConfigBuilder(
                        new HttpServerConfigurationBuilder().withReceiveBufferSize(50)
                )
                .withQueryFutureUpdateListener(waitUntilCommandStarted(alterAckReceived))
                .withAlterTableStartWaitTimeout(30_000_000)
                .withAlterTableMaxWaitTimeout(50_000_000)
                .withFilesFacade(new FilesFacadeImpl() {
                    @Override
                    public long openRW(LPSZ name, long opts) {
                        if (Chars.endsWith(name, "default/s.v") || Chars.endsWith(name, "default\\s.v")) {
                            alterAckReceived.await();
                            disconnectLatch.countDown();
                        }
                        return super.openRW(name, opts);
                    }
                });

        runAlterOnBusyTable((writer, rdr) -> {
                    // Wait command execution
                    TableWriterMetadata metadata = writer.getMetadata();
                    int columnIndex = metadata.getColumnIndex("s");
                    for (int i = 0; i < 100 && !writer.getMetadata().isColumnIndexed(columnIndex); i++) {
                        writer.tick(true);
                        Os.sleep(100);
                    }
                    Assert.assertTrue(metadata.isColumnIndexed(columnIndex));
                },
                0,
                queryTestBuilder,
                disconnectLatch,
                "alter+table+<x>+alter+column+s+add+index");
    }

    @Test
    public void testAlterTableAddIndex() throws Exception {
        runAlterOnBusyTable((writer, rdr) -> {
                    TableWriterMetadata metadata = writer.getMetadata();
                    int columnIndex = metadata.getColumnIndex("y");
                    Assert.assertEquals(2, columnIndex);
                    Assert.assertEquals(ColumnType.INT, metadata.getColumnType(columnIndex));
                },
                1,
                0,
                "alter+table+<x>+add+column+y+int");
    }

    @Test
    public void testAlterTableAddIndexContinuesAfterStartTimeoutExpired() throws Exception {
        SOCountDownLatch alterAckReceived = new SOCountDownLatch(1);
        HttpQueryTestBuilder queryTestBuilder = new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withWorkerCount(1)
                .withHttpServerConfigBuilder(
                        new HttpServerConfigurationBuilder().withReceiveBufferSize(50)
                )
                .withQueryFutureUpdateListener(waitUntilCommandStarted(alterAckReceived))
                .withAlterTableStartWaitTimeout(30_000_000)
                .withAlterTableMaxWaitTimeout(50_000_000)
                .withFilesFacade(new FilesFacadeImpl() {
                    @Override
                    public long openRW(LPSZ name, long opts) {
                        if (Chars.endsWith(name, "/default/s.v") || Chars.endsWith(name, "default\\s.v")) {
                            alterAckReceived.await();
                        }
                        return super.openRW(name, opts);
                    }
                });

        runAlterOnBusyTable((writer, rdr) -> {
                    TableWriterMetadata metadata = writer.getMetadata();
                    int columnIndex = metadata.getColumnIndex("s");
                    Assert.assertTrue(metadata.isColumnIndexed(columnIndex));
                },
                0,
                queryTestBuilder,
                null,
                "alter+table+<x>+alter+column+s+add+index");
    }

    @Test
    public void testAlterTableAddIndexContinuesAfterStartTimeoutExpiredAndTimeout() throws Exception {
        SOCountDownLatch alterAckReceived = new SOCountDownLatch(1);

        HttpQueryTestBuilder queryTestBuilder = new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withWorkerCount(1)
                .withHttpServerConfigBuilder(
                        new HttpServerConfigurationBuilder().withReceiveBufferSize(50)
                )
                .withAlterTableStartWaitTimeout(250_000)
                .withAlterTableMaxWaitTimeout(250_000)
                .withQueryFutureUpdateListener(waitUntilCommandStarted(alterAckReceived))
                .withFilesFacade(new FilesFacadeImpl() {
                    @Override
                    public long openRW(LPSZ name, long opts) {
                        if (Chars.endsWith(name, "/default/s.v") || Chars.endsWith(name, "\\default\\s.v")) {
                            alterAckReceived.await();
                            Os.sleep(600);
                        }
                        return super.openRW(name, opts);
                    }
                });

        runAlterOnBusyTable((writer, rdr) -> {
                    TableWriterMetadata metadata = writer.getMetadata();
                    int columnIndex = metadata.getColumnIndex("s");
                    Assert.assertTrue(metadata.isColumnIndexed(columnIndex));
                },
                1,
                queryTestBuilder,
                null,
                "alter+table+<x>+alter+column+s+add+index");
    }

    @Test
    public void testAlterTableAddNocacheAlterCache() throws Exception {
        runAlterOnBusyTable((writer, rdr) -> {
                    TableWriterMetadata metadata = writer.getMetadata();
                    int columnIndex = metadata.getColumnIndex("s");
                    Assert.assertTrue("Column s must exist", columnIndex >= 0);
                    Assert.assertFalse(rdr.getSymbolMapReader(columnIndex).isCached());
                },
                1,
                0,
                "alter+table+<x>+alter+column+s+nocache");
    }

    @Test
    public void testAlterTableAddRenameColumn() throws Exception {
        runAlterOnBusyTable((writer, rdr) -> {
                    TableWriterMetadata metadata = writer.getMetadata();
                    int columnIndex = metadata.getColumnIndex("y");
                    Assert.assertTrue("Column y must exist", columnIndex > 0);
                    int columnIndex2 = metadata.getColumnIndex("s2");
                    Assert.assertTrue("Column s2 must exist", columnIndex2 > 0);
                    Assert.assertTrue(metadata.isColumnIndexed(columnIndex2));
                    Assert.assertFalse(rdr.getSymbolMapReader(columnIndex2).isCached());
                },
                2,
                0,
                "alter+table+<x>+add+column+y+int",
                "alter+table+<x>+add+column+s2+symbol+capacity+512+nocache+index");
    }

    @Test
    public void testAlterTableFailsToUpgradeConcurrently() throws Exception {
        runAlterOnBusyTable((writer, rdr) -> {
                    TableWriterMetadata metadata = writer.getMetadata();
                    int columnIndex = metadata.getColumnIndexQuiet("y");
                    int columnIndex2 = metadata.getColumnIndexQuiet("x");

                    Assert.assertTrue(columnIndex > -1 || columnIndex2 > -1);
                    Assert.assertTrue(columnIndex == -1 || columnIndex2 == -1);
                },
                2,
                1,
                "alter+table+<x>+rename+column+s+to+y",
                "alter+table+<x>+rename+column+s+to+x");
    }

    @Test
    public void testCanReuseSameJsonContextForMultipleAlterRuns() throws Exception {
        runAlterOnBusyTable((writer, rdr) -> {
                    TableWriterMetadata metadata = writer.getMetadata();
                    int columnIndex = metadata.getColumnIndexQuiet("y");
                    int columnIndex2 = metadata.getColumnIndexQuiet("x");

                    Assert.assertTrue(columnIndex > -1 && columnIndex2 > -1);
                    Assert.assertEquals(-1, metadata.getColumnIndexQuiet("s"));
                },
                1,
                0,
                "alter+table+<x>+add+y+long256,x+timestamp",
                "alter+table+<x>+drop+column+s");
    }

    private void runAlterOnBusyTable(
            final AlterVerifyAction alterVerifyAction,
            int httpWorkers,
            int errorsExpected,
            final String... httpAlterQueries
    ) throws Exception {
        HttpQueryTestBuilder queryTestBuilder = new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withWorkerCount(httpWorkers)
                .withHttpServerConfigBuilder(
                        new HttpServerConfigurationBuilder().withReceiveBufferSize(50)
                )
                .withAlterTableStartWaitTimeout(30_000_000);

        runAlterOnBusyTable(alterVerifyAction, errorsExpected, queryTestBuilder, null, httpAlterQueries);
    }

    @Test
    public void testUpdateBusyTable() throws Exception {
        HttpQueryTestBuilder queryTestBuilder = new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withWorkerCount(1)
                .withHttpServerConfigBuilder(
                        new HttpServerConfigurationBuilder().withReceiveBufferSize(50)
                )
                .withAlterTableStartWaitTimeout(30_000_000);

        runUpdateOnBusyTable((writer, rdr) -> TestUtils.assertReader(
                        "s\tx\tts\n" +
                                "b\t10\t1970-01-01T00:00:00.000001Z\n" +
                                "c\t2\t1970-01-01T00:00:00.000002Z\n" +
                                "a\t1\t1970-01-01T00:00:00.000003Z\n" +
                                "b\t10\t1970-01-01T00:00:00.000004Z\n" +
                                "c\t5\t1970-01-01T00:00:00.000005Z\n" +
                                "a\t1\t1970-01-01T00:00:00.000006Z\n" +
                                "b\t10\t1970-01-01T00:00:00.000007Z\n" +
                                "c\t8\t1970-01-01T00:00:00.000008Z\n" +
                                "a\t1\t1970-01-01T00:00:00.000009Z\n",
                        rdr,
                        new StringSink()
                ),
                0,
                queryTestBuilder,
                null,
                -1L,
                3,
                URLEncoder.encode("update x set x=1 where s = 'a'", StandardCharsets.UTF_8),
                URLEncoder.encode("update x set x=10 where s = 'b'", StandardCharsets.UTF_8)
        );
    }

    @Test
    public void testUpdateTimeout() throws Exception {
        HttpQueryTestBuilder queryTestBuilder = new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withWorkerCount(1)
                .withHttpServerConfigBuilder(
                        new HttpServerConfigurationBuilder().withReceiveBufferSize(50)
                )
                .withAlterTableStartWaitTimeout(30_000_000);

        runUpdateOnBusyTable((writer, rdr) -> {
                    // Test no resources leak, update can go through or not, it is not deterministic
                },
                1,
                queryTestBuilder,
                null,
                1,
                3,
                URLEncoder.encode("update x set x=1 from tables() where s = 'a'", StandardCharsets.UTF_8)
        );
    }

    @Test
    public void testUpdateConnectionDropOnColumnRewrite() throws Exception {
        SOCountDownLatch disconnectLatch = new SOCountDownLatch(1);

        HttpQueryTestBuilder queryTestBuilder = new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withWorkerCount(1)
                .withHttpServerConfigBuilder(
                        new HttpServerConfigurationBuilder().withReceiveBufferSize(50)
                )
                .withAlterTableStartWaitTimeout(30_000_000)
                .withFilesFacade(new FilesFacadeImpl() {
                    @Override
                    public long openRW(LPSZ name, long opts) {
                        if (Chars.endsWith(name, "x.d.1")) {
                            disconnectLatch.countDown();
                        }
                        return super.openRW(name, opts);
                    }
                });

        runUpdateOnBusyTable((wrt, rdr) -> TestUtils.assertReader(
                        "s\tx\tts\n" +
                                "b\t1\t1970-01-01T00:00:00.000001Z\n" +
                                "c\t2\t1970-01-01T00:00:00.000002Z\n" +
                                "a\t3\t1970-01-01T00:00:00.000003Z\n" +
                                "b\t4\t1970-01-01T00:00:00.000004Z\n" +
                                "c\t5\t1970-01-01T00:00:00.000005Z\n" +
                                "a\t6\t1970-01-01T00:00:00.000006Z\n" +
                                "b\t7\t1970-01-01T00:00:00.000007Z\n" +
                                "c\t8\t1970-01-01T00:00:00.000008Z\n" +
                                "a\t9\t1970-01-01T00:00:00.000009Z\n",
                        rdr,
                        new StringSink()
                ),
                0,
                queryTestBuilder,
                disconnectLatch,
                1000,
                0,
                URLEncoder.encode("update x set x=1 from tables()", StandardCharsets.UTF_8)
        );
    }

    private void runAlterOnBusyTable(
            AlterVerifyAction alterVerifyAction,
            int errorsExpected,
            HttpQueryTestBuilder queryTestBuilder,
            SOCountDownLatch waitToDisconnect,
            final String... httpAlterQueries
    ) throws Exception {
        queryTestBuilder.run((engine) -> {
            setupSql(engine);
            TableWriter writer = null;
            try {
                String tableName = "x";
                compiler.compile("create table IF NOT EXISTS " + tableName + " as (" +
                        " select rnd_symbol('a', 'b', 'c') as s," +
                        " cast(x as timestamp) ts" +
                        " from long_sequence(10)" +
                        " )", sqlExecutionContext);
                writer = engine.getWriter(AllowAllCairoSecurityContext.INSTANCE, tableName, "test lock");
                SOCountDownLatch finished = new SOCountDownLatch(httpAlterQueries.length);
                AtomicInteger errors = new AtomicInteger();
                CyclicBarrier barrier = new CyclicBarrier(httpAlterQueries.length);

                for (int i = 0; i < httpAlterQueries.length; i++) {
                    String httpAlterQuery = httpAlterQueries[i].replace("<x>", tableName);
                    Thread thread = new Thread(() -> {
                        try {
                            barrier.await();
                            if (waitToDisconnect != null) {
                                long fd = new SendAndReceiveRequestBuilder()
                                        .connectAndSendRequest(
                                                "GET /query?query=" + httpAlterQuery + " HTTP/1.1\r\n"
                                                        + SendAndReceiveRequestBuilder.RequestHeaders
                                        );
                                waitToDisconnect.await();
                                Net.close(fd);
                            } else {
                                new SendAndReceiveRequestBuilder().executeWithStandardHeaders(
                                        "GET /query?query=" + httpAlterQuery + " HTTP/1.1\r\n",
                                        "0d\r\n" +
                                                "{\"ddl\":\"OK\"}\n\r\n" +
                                                "00\r\n" +
                                                "\r\n"
                                );
                            }
                        } catch (Error e) {
                            if (errorsExpected == 0) {
                                error = e;
                            }
                            errors.getAndIncrement();
                        } catch (Throwable e) {
                            errors.getAndIncrement();
                        } finally {
                            finished.countDown();
                        }
                    });
                    thread.start();
                }

                MicrosecondClock microsecondClock = engine.getConfiguration().getMicrosecondClock();
                long startTimeMicro = microsecondClock.getTicks();
                // Wait 1 min max for completion
                while (microsecondClock.getTicks() - startTimeMicro < 60_000_000 && finished.getCount() > 0 && errors.get() <= errorsExpected) {
                    writer.tick(true);
                    finished.await(1_000_000);
                }

                if (error != null) {
                    throw error;
                }
                Assert.assertEquals(errorsExpected, errors.get());
                Assert.assertEquals(0, finished.getCount());
                engine.releaseAllReaders();
                try (TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), tableName)) {
                    alterVerifyAction.run(writer, rdr);
                }
            } finally {
                if (writer != null) {
                    writer.close();
                }
                compiler.close();
            }
        });
    }

    private void runUpdateOnBusyTable(
            AlterVerifyAction alterVerifyAction,
            int errorsExpected,
            HttpQueryTestBuilder queryTestBuilder,
            SOCountDownLatch waitToDisconnect,
            long statementTimeout,
            int updatedCount,
            final String... httpUpdateQueries
    ) throws Exception {
        queryTestBuilder.run((engine) -> {
            setupSql(engine);
            TableWriter writer = null;
            try {
                String tableName = "x";
                compiler.compile("create table IF NOT EXISTS " + tableName + " as (" +
                        " select case when x%3 = 0 then 'a' when x%3 = 1 then 'b' else 'c' end as s," +
                        " x," +
                        " cast(x as timestamp) ts" +
                        " from long_sequence(9)" +
                        " )", sqlExecutionContext);
                writer = engine.getWriter(AllowAllCairoSecurityContext.INSTANCE, tableName, "test lock");
                SOCountDownLatch finished = new SOCountDownLatch(httpUpdateQueries.length);
                AtomicInteger errors = new AtomicInteger();
                CyclicBarrier barrier = new CyclicBarrier(httpUpdateQueries.length);

                for (int i = 0; i < httpUpdateQueries.length; i++) {
                    String httpAlterQuery = httpUpdateQueries[i];
                    Thread thread = new Thread(() -> {
                        try {
                            barrier.await();
                            if (waitToDisconnect != null) {
                                long fd = new SendAndReceiveRequestBuilder()
                                        .withStatementTimeout(statementTimeout)
                                        .connectAndSendRequestWithHeaders(
                                                "GET /query?query=" + httpAlterQuery + " HTTP/1.1\r\n"
                                        );
                                waitToDisconnect.await();
                                Net.close(fd);
                            } else {
                                new SendAndReceiveRequestBuilder()
                                        .withStatementTimeout(statementTimeout)
                                        .executeWithStandardHeaders(
                                                "GET /query?query=" + httpAlterQuery + " HTTP/1.1\r\n",
                                                "0e\r\n" +
                                                        "{\"updated\":" + updatedCount + "}\n" +
                                                        "\r\n" +
                                                        "00\r\n" +
                                                        "\r\n"
                                        );
                            }
                        } catch (Error e) {
                            if (errorsExpected == 0) {
                                error = e;
                            }
                            errors.getAndIncrement();
                        } catch (Throwable e) {
                            errors.getAndIncrement();
                        } finally {
                            finished.countDown();
                        }
                    });
                    thread.start();
                }

                MicrosecondClock microsecondClock = engine.getConfiguration().getMicrosecondClock();
                long startTimeMicro = microsecondClock.getTicks();
                // Wait 1 min max for completion
                while (microsecondClock.getTicks() - startTimeMicro < 60_000_000 && finished.getCount() > 0 && errors.get() <= errorsExpected) {
                    writer.tick(true);
                    finished.await(1_000_000);
                }

                if (error != null) {
                    throw error;
                }
                Assert.assertEquals(errorsExpected, errors.get());
                Assert.assertEquals(0, finished.getCount());
                engine.releaseAllReaders();
                try (TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), tableName)) {
                    alterVerifyAction.run(writer, rdr);
                }
            } finally {
                if (writer != null) {
                    writer.close();
                }
                compiler.close();
            }
        });
    }

    private QueryFutureUpdateListener waitUntilCommandStarted(SOCountDownLatch alterAckReceived) {
        return new QueryFutureUpdateListener() {
            @Override
            public void reportProgress(long commandId, int status) {
                if (status == OperationFuture.QUERY_STARTED) {
                    alterAckReceived.countDown();
                }
            }

            @Override
            public void reportStart(CharSequence tableName, long commandId) {
            }
        };
    }

    @FunctionalInterface
    interface AlterVerifyAction {
        void run(TableWriter writer, TableReader rdr) throws InterruptedException;
    }
}
