/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.persistence.db.wal;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cluster.ClusterState;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.WALMode;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.processors.cache.persistence.GridCacheDatabaseSharedManager;
import org.apache.ignite.internal.processors.cache.persistence.checkpoint.CheckpointHistory;
import org.apache.ignite.internal.processors.cache.persistence.checkpoint.Checkpointer;
import org.apache.ignite.internal.processors.cache.persistence.wal.FileDescriptor;
import org.apache.ignite.internal.processors.cache.persistence.wal.FileWriteAheadLogManager;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.testframework.ListeningTestLogger;
import org.apache.ignite.testframework.junits.WithSystemProperty;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

import static org.apache.ignite.IgniteSystemProperties.IGNITE_CHECKPOINT_TRIGGER_ARCHIVE_SIZE_PERCENTAGE;
import static org.apache.ignite.IgniteSystemProperties.IGNITE_PDS_MAX_CHECKPOINT_MEMORY_HISTORY_SIZE;
import static org.apache.ignite.internal.util.IgniteUtils.GB;
import static org.apache.ignite.internal.util.IgniteUtils.KB;
import static org.apache.ignite.internal.util.IgniteUtils.MB;
import static org.apache.ignite.testframework.GridTestUtils.getFieldValueHierarchy;
import static org.apache.ignite.testframework.GridTestUtils.waitForCondition;

/**
 *
 */
public abstract class WalDeletionArchiveAbstractTest extends GridCommonAbstractTest {
    /**
     * Start grid with override default configuration via customConfigurator.
     */
    private Ignite startGrid(Consumer<DataStorageConfiguration> customConfigurator) throws Exception {
        IgniteConfiguration configuration = getConfiguration(getTestIgniteInstanceName());

        DataStorageConfiguration dbCfg = new DataStorageConfiguration();

        dbCfg.setWalMode(walMode());
        dbCfg.setWalSegmentSize(512 * 1024);
        dbCfg.setCheckpointFrequency(60 * 1000);//too high value for turn off frequency checkpoint.
        dbCfg.setDefaultDataRegionConfiguration(new DataRegionConfiguration()
            .setMaxSize(100 * 1024 * 1024)
            .setPersistenceEnabled(true));

        customConfigurator.accept(dbCfg);

        configuration.setDataStorageConfiguration(dbCfg);

        Ignite ignite = startGrid(configuration);

        ignite.cluster().state(ClusterState.ACTIVE);

        return ignite;
    }

    /** */
    private CacheConfiguration<Integer, Object> cacheConfiguration() {
        CacheConfiguration<Integer, Object> ccfg = new CacheConfiguration<>(DEFAULT_CACHE_NAME);

        return ccfg.setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL);
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        stopAllGrids();

        cleanPersistenceDir();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();

        cleanPersistenceDir();
    }

    /**
     * @return WAL mode used in test.
     */
    protected abstract WALMode walMode();

    /**
     * History size parameters consistency check. Should be set just one of wal history size or max wal archive size.
     */
    @Test
    public void testGridDoesNotStart_BecauseBothWalHistorySizeAndMaxWalArchiveSizeUsed() throws Exception {
        //given: wal history size and max wal archive size are both set.
        IgniteConfiguration configuration = getConfiguration(getTestIgniteInstanceName());

        DataStorageConfiguration dbCfg = new DataStorageConfiguration();
        dbCfg.setWalHistorySize(12);
        dbCfg.setMaxWalArchiveSize(9);
        configuration.setDataStorageConfiguration(dbCfg);

        try {
            //when: start grid.
            startGrid(getTestIgniteInstanceName(), configuration);
            fail("Should be fail because both wal history size and max wal archive size was used");
        }
        catch (IgniteException e) {
            //then: exception is occurrence because should be set just one parameters.
            assertTrue(findSourceMessage(e).startsWith("Should be used only one of wal history size or max wal archive size"));
        }
    }

    /**
     * find first cause's message
     */
    private String findSourceMessage(Throwable ex) {
        return ex.getCause() == null ? ex.getMessage() : findSourceMessage(ex.getCause());
    }

    /**
     * Correct delete archived wal files.
     */
    @Test
    public void testCorrectDeletedArchivedWalFiles() throws Exception {
        //given: configured grid with setted max wal archive size
        long maxWalArchiveSize = 2 * 1024 * 1024;
        Ignite ignite = startGrid(dbCfg -> dbCfg.setMaxWalArchiveSize(maxWalArchiveSize));

        GridCacheDatabaseSharedManager dbMgr = gridDatabase(ignite);

        CheckpointHistory hist = dbMgr.checkpointHistory();
        assertNotNull(hist);

        IgniteCache<Integer, Object> cache = ignite.getOrCreateCache(cacheConfiguration());

        //when: put to cache more than 2 MB
        for (int i = 0; i < 500; i++) {
            if (i % 100 == 0)
                forceCheckpoint();

            cache.put(i, i);
        }

        //then: total archive size less than of maxWalArchiveSize(by current logic)
        FileWriteAheadLogManager wal = wal(ignite);

        assertTrue(waitForCondition(() -> wal.lastTruncatedSegment() >= 0, 10_000));

        FileDescriptor[] files = wal.walArchiveFiles();

        long totalSize = wal.totalSize(files);

        assertTrue(files.length >= 1);
        assertTrue(totalSize < maxWalArchiveSize);
        assertFalse(Stream.of(files).anyMatch(desc -> desc.file().getName().endsWith("00001.wal")));

        assertTrue(!hist.checkpoints().isEmpty());
    }

    /**
     * Checkpoint triggered depends on wal size.
     */
    @Test
    public void testCheckpointStarted_WhenWalHasTooBigSizeWithoutCheckpoint() throws Exception {
        //given: configured grid with max wal archive size = 1MB, wal segment size = 512KB
        Ignite ignite = startGrid(dbCfg -> dbCfg.setMaxWalArchiveSize(1024 * 1024));

        GridCacheDatabaseSharedManager dbMgr = gridDatabase(ignite);

        IgniteCache<Integer, Object> cache = ignite.getOrCreateCache(cacheConfiguration());

        for (int i = 0; i < 500; i++)
            cache.put(i, i);

        //then: checkpoint triggered by size limit of wall without checkpoint
        Checkpointer checkpointer = dbMgr.getCheckpointer();

        String checkpointReason = U.field((Object)U.field(checkpointer, "curCpProgress"), "reason");

        assertEquals("too big size of WAL without checkpoint", checkpointReason);
    }

    /**
     * Test for check deprecated removing checkpoint by deprecated walHistorySize parameter
     *
     * @deprecated Test old removing process depends on WalHistorySize.
     */
    @Test
    public void testCheckpointHistoryRemovingByTruncate() throws Exception {
        Ignite ignite = startGrid(dbCfg -> dbCfg.setMaxWalArchiveSize(2 * 1024 * 1024));

        GridCacheDatabaseSharedManager dbMgr = gridDatabase(ignite);

        IgniteCache<Integer, Object> cache = ignite.getOrCreateCache(cacheConfiguration());

        CheckpointHistory hist = dbMgr.checkpointHistory();
        assertNotNull(hist);

        int startHistSize = hist.checkpoints().size();

        int checkpointCnt = 10;

        for (int i = 0; i < checkpointCnt; i++) {
            cache.put(i, i);
            //and: wait for checkpoint finished
            forceCheckpoint();
            // Check that the history is growing.
            assertEquals(startHistSize + (i + 1), hist.checkpoints().size());
        }

        // Ensure rollover and wal archive cleaning.
        for (int i = 0; i < 6; i++)
            cache.put(i, new byte[ignite.configuration().getDataStorageConfiguration().getWalSegmentSize() / 2]);

        FileWriteAheadLogManager wal = wal(ignite);
        assertTrue(waitForCondition(() -> wal.lastTruncatedSegment() >= 0, 10_000));

        assertTrue(hist.checkpoints().size() < checkpointCnt + startHistSize);

        File[] cpFiles = dbMgr.checkpointDirectory().listFiles();

        assertTrue(cpFiles.length <= (checkpointCnt * 2 + 1));// starts & ends + node_start
    }

    /**
     * Correct delete checkpoint history from memory depends on IGNITE_PDS_MAX_CHECKPOINT_MEMORY_HISTORY_SIZE.
     * WAL files doesn't delete because deleting was disabled.
     */
    @Test
    @WithSystemProperty(key = IGNITE_PDS_MAX_CHECKPOINT_MEMORY_HISTORY_SIZE, value = "2")
    public void testCorrectDeletedCheckpointHistoryButKeepWalFiles() throws Exception {
        //given: configured grid with disabled WAL removing.
        Ignite ignite = startGrid(dbCfg -> dbCfg.setMaxWalArchiveSize(DataStorageConfiguration.UNLIMITED_WAL_ARCHIVE));

        GridCacheDatabaseSharedManager dbMgr = gridDatabase(ignite);

        CheckpointHistory hist = dbMgr.checkpointHistory();
        assertNotNull(hist);

        IgniteCache<Integer, Object> cache = ignite.getOrCreateCache(cacheConfiguration());

        //when: put to cache
        for (int i = 0; i < 500; i++) {
            cache.put(i, i);

            if (i % 10 == 0)
                forceCheckpoint();
        }

        forceCheckpoint();

        //then: WAL files was not deleted but some of checkpoint history was deleted.
        FileWriteAheadLogManager wal = wal(ignite);
        assertNull(getFieldValueHierarchy(wal, "cleaner"));

        FileDescriptor[] files = wal.walArchiveFiles();

        assertTrue(Stream.of(files).anyMatch(desc -> desc.file().getName().endsWith("0001.wal")));

        assertTrue(hist.checkpoints().size() == 2);
    }

    /**
     * Checks that the deletion of WAL segments occurs with the maximum number of segments.
     *
     * @throws Exception If failed.
     */
    @Test
    @WithSystemProperty(key = IGNITE_CHECKPOINT_TRIGGER_ARCHIVE_SIZE_PERCENTAGE, value = "1000")
    public void testSingleCleanWalArchive() throws Exception {
        IgniteConfiguration cfg = getConfiguration(getTestIgniteInstanceName(0))
            .setCacheConfiguration(cacheConfiguration())
            .setDataStorageConfiguration(
                new DataStorageConfiguration()
                    .setCheckpointFrequency(Long.MAX_VALUE)
                    .setMaxWalArchiveSize(5 * MB)
                    .setWalSegmentSize((int)MB)
                    .setDefaultDataRegionConfiguration(
                        new DataRegionConfiguration()
                            .setPersistenceEnabled(true)
                            .setMaxSize(GB)
                            .setCheckpointPageBufferSize(GB)
                    )
            );

        ListeningTestLogger listeningLog = new ListeningTestLogger(cfg.getGridLogger());
        cfg.setGridLogger(listeningLog);

        IgniteEx n = startGrid(cfg);

        n.cluster().state(ClusterState.ACTIVE);
        awaitPartitionMapExchange();

        for (int i = 0; walArchiveSize(n) < 20L * cfg.getDataStorageConfiguration().getWalSegmentSize(); )
            n.cache(DEFAULT_CACHE_NAME).put(i++, new byte[(int)(512 * KB)]);

        assertEquals(-1, wal(n).lastTruncatedSegment());
        assertEquals(0, gridDatabase(n).lastCheckpointMarkWalPointer().index());

        Collection<String> logStrs = new ConcurrentLinkedQueue<>();
        listeningLog.registerListener(logStr -> {
            if (logStr.contains("Finish clean WAL archive"))
                logStrs.add(logStr);
        });

        forceCheckpoint();

        long maxWalArchiveSize = cfg.getDataStorageConfiguration().getMaxWalArchiveSize();
        assertTrue(waitForCondition(() -> walArchiveSize(n) < maxWalArchiveSize, getTestTimeout()));

        assertEquals(logStrs.toString(), 1, logStrs.size());
    }

    /**
     * Extract GridCacheDatabaseSharedManager.
     */
    private GridCacheDatabaseSharedManager gridDatabase(Ignite ignite) {
        return (GridCacheDatabaseSharedManager)((IgniteEx)ignite).context().cache().context().database();
    }

    /**
     * Extract IgniteWriteAheadLogManager.
     */
    private FileWriteAheadLogManager wal(Ignite ignite) {
        return (FileWriteAheadLogManager)((IgniteEx)ignite).context().cache().context().wal();
    }

    /**
     * Calculate current WAL archive size.
     *
     * @param n Node.
     * @return Total WAL archive size.
     */
    private long walArchiveSize(Ignite n) {
        return Arrays.stream(wal(n).walArchiveFiles()).mapToLong(fd -> fd.file().length()).sum();
    }
}
