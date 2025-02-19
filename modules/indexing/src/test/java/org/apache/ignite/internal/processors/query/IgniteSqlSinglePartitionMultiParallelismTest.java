/*
 * Copyright 2019 GridGain Systems, Inc. and Contributors.
 *
 * Licensed under the GridGain Community Edition License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.gridgain.com/products/software/community-edition/gridgain-community-edition-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ignite.internal.processors.query;

import java.util.LinkedHashMap;
import java.util.List;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cache.QueryEntity;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.IgniteCacheProxyImpl;
import org.apache.ignite.internal.processors.cache.index.AbstractIndexingCommonTest;
import org.junit.Test;

import static java.util.Collections.singletonList;
import static org.apache.ignite.internal.processors.query.h2.opt.GridH2IndexBase.calculateSegment;

/**
 * Test for correct results in case of query with single partition and cache with parallelism > 1.
 */
public class IgniteSqlSinglePartitionMultiParallelismTest extends AbstractIndexingCommonTest {
    /** */
    private static final String CACHE_NAME = "SC_NULL_TEST";

    /** */
    private static final int CACHE_PARALLELISM = 8;

    /** */
    private static final int KEY_CNT = 1024;

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        startGrids(3);
        ignite(0).createCache(cacheConfig());
        fillTable();
    }

    /**
     * @return Cache configuration.
     */
    protected CacheConfiguration<Integer, Integer> cacheConfig() {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("id", Integer.class.getName());
        fields.put("val", Integer.class.getName());

        return new CacheConfiguration<Integer, Integer>()
            .setName(CACHE_NAME)
            .setQueryParallelism(CACHE_PARALLELISM)
            .setQueryEntities(
                singletonList(
                    new QueryEntity(Integer.class.getName(), "newKeyType")
                        .setTableName(CACHE_NAME)
                        .setFields(fields)
                        .setKeyFieldName("id")
                )
            );
    }

    /**
     * Check common case without partitions. Should be single result.
     */
    @Test
    public void testSimpleCountQuery() throws Exception {
        List<List<?>> results = runQuery("select count(*) from " + CACHE_NAME);

        Long res = (Long) results.get(0).get(0);

        assertEquals(1, results.size());
        assertEquals(Long.valueOf(KEY_CNT), res);
    }

    /**
     * Check case with every single partition. Partition segment must be calculated correctly.
     */
    @Test
    public void testWhereCountPartitionQuery() throws Exception {
        for (int segment = 0; segment < CACHE_PARALLELISM; segment++) {
            Integer keyForSegment = segmenKey(segment);

            List<List<?>> results = runQuery("select count(*) from " + CACHE_NAME + " where ID=" + keyForSegment);

            Long res = (Long) results.get(0).get(0);

            assertEquals(1, results.size());
            assertEquals(Long.valueOf(1), res);
        }
    }

    /**
     * Check case with 2 partitions. Multiple partitions should not be affected.
     */
    @Test
    public void testWhereCountMultiPartitionsQuery() {
        Integer keyFromFirstSegment = segmenKey(0);
        Integer keyFromLastSegment = segmenKey(CACHE_PARALLELISM - 1);

        List<List<?>> results = runQuery("select count(*) from " + CACHE_NAME + " where ID="
            + keyFromFirstSegment + " or ID=" + keyFromLastSegment);

        Long res = (Long) results.get(0).get(0);

        assertEquals(1, results.size());
        assertEquals(Long.valueOf(2), res);
    }

    /**
     * Test ensures that multi-partitioned query from reducer's point of view is awaiting a proper amount of replies from map nodes
     * even in case for the mapper it is mono-partitioned query.
     *
     * <p>To verify this, we need to find a pair of keys such that each key belongs to a different node.
     */
    @Test
    public void testMultiPartitionedRdcMonoPartitionedMap() {
        Integer keyFromFirstSegment = segmenKey(0);

        for (int i = 1; i < CACHE_PARALLELISM; i++) {
            Integer keyFromAnotherSegment = segmenKey(i);

            List<List<?>> results = runQuery("select * from " + CACHE_NAME + " where ID="
                    + keyFromFirstSegment + " or ID=" + keyFromAnotherSegment);

            assertEquals(2, results.size());
        }
    }

    /**
     * @param segment Target index segment.
     * @return Cache key for target segment.
     */
    protected Integer segmenKey(int segment) {
        IgniteCache<Object, Object> cache = ignite(0).cache(CACHE_NAME);
        IgniteCacheProxyImpl proxy = cache.unwrap(IgniteCacheProxyImpl.class);

        GridCacheContext<?, ?> cctx = proxy.context();

        for (int k = 1; k <= KEY_CNT; k++) {
            int keySegment = calculateSegment(CACHE_PARALLELISM, cctx.affinity().partition(k));
            if (keySegment == segment)
                return k;
        }

        throw new IgniteException("Key is not found. Please, check range of keys and segmentsCnt. " +
            "Requested segmentId is " + segment);
    }

    /** */
    public void fillTable() {
        for (int i = 1; i <= KEY_CNT; i++)
            runQuery(String.format("insert into " + CACHE_NAME + "(id, val) VALUES(%d, %d)", i, i));
    }

    /** */
    public List<List<?>> runQuery(String qry) {
        IgniteCache<Integer, Integer> cache = ignite(0).cache(CACHE_NAME);

        return cache.query(
            new SqlFieldsQuery(qry)
        ).getAll();
    }
}
