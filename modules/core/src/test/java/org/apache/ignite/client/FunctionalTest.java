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

package org.apache.ignite.client;

import java.lang.management.ManagementFactory;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.cache.expiry.AccessedExpiryPolicy;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ModifiedExpiryPolicy;
import javax.management.MBeanServerInvocationHandler;
import javax.management.ObjectName;
import com.google.common.collect.ImmutableSet;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.Ignition;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheKeyConfiguration;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.CachePeekMode;
import org.apache.ignite.cache.CacheRebalanceMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.cache.PartitionLossPolicy;
import org.apache.ignite.cache.QueryEntity;
import org.apache.ignite.cache.QueryIndex;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.ClientConfiguration;
import org.apache.ignite.configuration.ClientConnectorConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.client.thin.ClientServerError;
import org.apache.ignite.internal.processors.odbc.ClientListenerProcessor;
import org.apache.ignite.internal.processors.platform.cache.expiry.PlatformExpiryPolicy;
import org.apache.ignite.internal.processors.platform.client.ClientStatus;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.mxbean.ClientProcessorMXBean;
import org.apache.ignite.spi.systemview.view.SystemView;
import org.apache.ignite.spi.systemview.view.TransactionView;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.apache.ignite.transactions.Transaction;
import org.apache.ignite.transactions.TransactionIsolation;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import static org.apache.ignite.internal.processors.cache.transactions.IgniteTxManager.TXS_MON_LIST;
import static org.apache.ignite.testframework.GridTestUtils.assertThrowsAnyCause;
import static org.apache.ignite.testframework.GridTestUtils.runAsync;
import static org.apache.ignite.transactions.TransactionConcurrency.OPTIMISTIC;
import static org.apache.ignite.transactions.TransactionConcurrency.PESSIMISTIC;
import static org.apache.ignite.transactions.TransactionIsolation.READ_COMMITTED;
import static org.apache.ignite.transactions.TransactionIsolation.REPEATABLE_READ;
import static org.apache.ignite.transactions.TransactionIsolation.SERIALIZABLE;
import static org.junit.Assert.assertArrayEquals;

/**
 * Thin client functional tests.
 */
public class FunctionalTest extends GridCommonAbstractTest {
    /** Per test timeout */
    @SuppressWarnings("deprecation")
    @Rule
    public Timeout globalTimeout = new Timeout((int) GridTestUtils.DFLT_TEST_TIMEOUT);

    /**
     * Ctor.
     */
    public FunctionalTest() {
        super(false);
    }

    /**
     * Tested API:
     * <ul>
     * <li>{@link IgniteClient#cache(String)}</li>
     * <li>{@link IgniteClient#getOrCreateCache(ClientCacheConfiguration)}</li>
     * <li>{@link IgniteClient#cacheNames()}</li>
     * <li>{@link IgniteClient#createCache(String)}</li>
     * <li>{@link IgniteClient#createCache(ClientCacheConfiguration)}</li>
     * <li>{@link IgniteCache#size(CachePeekMode...)}</li>
     * </ul>
     */
    @Test
    public void testCacheManagement() throws Exception {
        try (LocalIgniteCluster ignored = LocalIgniteCluster.start(2);
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            final String CACHE_NAME = "testCacheManagement";

            ClientCacheConfiguration cacheCfg = new ClientCacheConfiguration().setName(CACHE_NAME)
                .setCacheMode(CacheMode.REPLICATED)
                .setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

            int key = 1;
            Person val = new Person(key, Integer.toString(key));

            ClientCache<Integer, Person> cache = client.getOrCreateCache(cacheCfg);

            cache.put(key, val);

            assertEquals(1, cache.size());
            assertEquals(2, cache.size(CachePeekMode.ALL));

            cache = client.cache(CACHE_NAME);

            Person cachedVal = cache.get(key);

            assertEquals(val, cachedVal);

            Object[] cacheNames = new TreeSet<>(client.cacheNames()).toArray();

            assertArrayEquals(new TreeSet<>(Arrays.asList(Config.DEFAULT_CACHE_NAME, CACHE_NAME)).toArray(), cacheNames);

            client.destroyCache(CACHE_NAME);

            cacheNames = client.cacheNames().toArray();

            assertArrayEquals(new Object[] {Config.DEFAULT_CACHE_NAME}, cacheNames);

            cache = client.createCache(CACHE_NAME);

            assertFalse(cache.containsKey(key));

            cacheNames = client.cacheNames().toArray();

            assertArrayEquals(new TreeSet<>(Arrays.asList(Config.DEFAULT_CACHE_NAME, CACHE_NAME)).toArray(), cacheNames);

            client.destroyCache(CACHE_NAME);

            cache = client.createCache(cacheCfg);

            assertFalse(cache.containsKey(key));

            assertArrayEquals(new TreeSet<>(Arrays.asList(Config.DEFAULT_CACHE_NAME, CACHE_NAME)).toArray(), cacheNames);
        }
    }

    /**
     * Tested API:
     * <ul>
     * <li>{@link ClientCache#getName()}</li>
     * <li>{@link ClientCache#getConfiguration()}</li>
     * </ul>
     */
    @Test
    public void testCacheConfiguration() throws Exception {
        try (Ignite ignored = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            final String CACHE_NAME = "testCacheConfiguration";

            ClientCacheConfiguration cacheCfg = new ClientCacheConfiguration().setName(CACHE_NAME)
                .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
                .setBackups(3)
                .setCacheMode(CacheMode.PARTITIONED)
                .setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC)
                .setEagerTtl(false)
                .setGroupName("FunctionalTest")
                .setDefaultLockTimeout(12345)
                .setPartitionLossPolicy(PartitionLossPolicy.READ_WRITE_SAFE)
                .setReadFromBackup(true)
                .setRebalanceBatchSize(67890)
                .setRebalanceBatchesPrefetchCount(102938)
                .setRebalanceDelay(54321)
                .setRebalanceMode(CacheRebalanceMode.SYNC)
                .setRebalanceOrder(2)
                .setRebalanceThrottle(564738)
                .setRebalanceTimeout(142536)
                .setKeyConfiguration(new CacheKeyConfiguration("Employee", "orgId"))
                .setQueryEntities(new QueryEntity(int.class.getName(), "Employee")
                    .setTableName("EMPLOYEE")
                    .setFields(
                        Stream.of(
                            new SimpleEntry<>("id", Integer.class.getName()),
                            new SimpleEntry<>("orgId", Integer.class.getName())
                        ).collect(Collectors.toMap(
                            SimpleEntry::getKey, SimpleEntry::getValue, (a, b) -> a, LinkedHashMap::new
                        ))
                    )
                        // During query normalization null keyFields become empty set.
                        // Set empty collection for comparator.
                        .setKeyFields(Collections.emptySet())
                        .setKeyFieldName("id")
                        .setNotNullFields(Collections.singleton("id"))
                        .setDefaultFieldValues(Collections.singletonMap("id", 0))
                        .setIndexes(Collections.singletonList(new QueryIndex("id", true, "IDX_EMPLOYEE_ID")))
                        .setAliases(Stream.of("id", "orgId").collect(Collectors.toMap(f -> f, String::toUpperCase)))
                )
                    .setExpiryPolicy(new PlatformExpiryPolicy(10, 20, 30));

            ClientCache<Object, Object> cache = client.createCache(cacheCfg);

            assertEquals(CACHE_NAME, cache.getName());

            assertTrue(Comparers.equal(cacheCfg, cache.getConfiguration()));
        }
    }

    /**
     * Tested API:
     * <ul>
     * <li>{@link Ignition#startClient(ClientConfiguration)}</li>
     * <li>{@link IgniteClient#getOrCreateCache(String)}</li>
     * <li>{@link ClientCache#put(Object, Object)}</li>
     * <li>{@link ClientCache#get(Object)}</li>
     * <li>{@link ClientCache#containsKey(Object)}</li>
     * <li>{@link ClientCache#clear(Object)}</li>
     * </ul>
     */
    @Test
    public void testPutGet() throws Exception {
        // Existing cache, primitive key and object value
        try (Ignite ignored = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ClientCache<Integer, Person> cache = client.getOrCreateCache(Config.DEFAULT_CACHE_NAME);

            Integer key = 1;
            Person val = new Person(key, "Joe");

            cache.put(key, val);

            assertTrue(cache.containsKey(key));

            Person cachedVal = cache.get(key);

            assertEquals(val, cachedVal);

            cache.clear(key);

            assertFalse(cache.containsKey(key));

            assertNull(cache.get(key));
        }

        // Non-existing cache, object key and primitive value
        try (Ignite ignored = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ClientCache<Person, Integer> cache = client.getOrCreateCache("testPutGet");

            Integer val = 1;

            Person key = new Person(val, "Joe");

            cache.put(key, val);

            Integer cachedVal = cache.get(key);

            assertEquals(val, cachedVal);

            cache.clear(key);

            assertFalse(cache.containsKey(key));

            assertNull(cache.get(key));
        }

        // Object key and Object value
        try (Ignite ignored = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ClientCache<Person, Person> cache = client.getOrCreateCache("testPutGet");

            Person key = new Person(1, "Joe Key");

            Person val = new Person(1, "Joe Value");

            cache.put(key, val);

            Person cachedVal = cache.get(key);

            assertEquals(val, cachedVal);

            cache.clear(key);

            assertFalse(cache.containsKey(key));

            assertNull(cache.get(key));
        }
    }

    /**
     * Test cache operations with different data types.
     */
    @Test
    public void testDataTypes() throws Exception {
        try (Ignite ignite = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ignite.getOrCreateCache(Config.DEFAULT_CACHE_NAME);

            Person person = new Person(1, "name");

            // Primitive and built-in types.
            checkDataType(client, ignite, (byte)1);
            checkDataType(client, ignite, (short)1);
            checkDataType(client, ignite, 1);
            checkDataType(client, ignite, 1L);
            checkDataType(client, ignite, 1.0f);
            checkDataType(client, ignite, 1.0d);
            checkDataType(client, ignite, 'c');
            checkDataType(client, ignite, true);
            checkDataType(client, ignite, "string");
            checkDataType(client, ignite, UUID.randomUUID());
            checkDataType(client, ignite, new Date());

            // Enum.
            checkDataType(client, ignite, CacheAtomicityMode.ATOMIC);

            // Binary object.
            checkDataType(client, ignite, person);

            // Arrays.
            checkDataType(client, ignite, new byte[] {(byte)1});
            checkDataType(client, ignite, new short[] {(short)1});
            checkDataType(client, ignite, new int[] {1});
            checkDataType(client, ignite, new long[] {1L});
            checkDataType(client, ignite, new float[] {1.0f});
            checkDataType(client, ignite, new double[] {1.0d});
            checkDataType(client, ignite, new char[] {'c'});
            checkDataType(client, ignite, new boolean[] {true});
            checkDataType(client, ignite, new String[] {"string"});
            checkDataType(client, ignite, new UUID[] {UUID.randomUUID()});
            checkDataType(client, ignite, new Date[] {new Date()});
            checkDataType(client, ignite, new int[][] {new int[] {1}});

            checkDataType(client, ignite, new CacheAtomicityMode[] {CacheAtomicityMode.ATOMIC});

            checkDataType(client, ignite, new Person[] {person});
            checkDataType(client, ignite, new Person[][] {new Person[] {person}});
            checkDataType(client, ignite, new Object[] {1, "string", person, new Person[] {person}});

            // Lists.
            checkDataType(client, ignite, Collections.emptyList());
            checkDataType(client, ignite, Collections.singletonList(person));
            checkDataType(client, ignite, Arrays.asList(person, person));
            checkDataType(client, ignite, new ArrayList<>(Arrays.asList(person, person)));
            checkDataType(client, ignite, new LinkedList<>(Arrays.asList(person, person)));
            checkDataType(client, ignite, Arrays.asList(Arrays.asList(person, person), person));

            // Sets.
            checkDataType(client, ignite, Collections.emptySet());
            checkDataType(client, ignite, Collections.singleton(person));
            checkDataType(client, ignite, new HashSet<>(Arrays.asList(1, 2)));
            checkDataType(client, ignite, new HashSet<>(Arrays.asList(Arrays.asList(person, person), person)));
            checkDataType(client, ignite, new HashSet<>(new ArrayList<>(Arrays.asList(Arrays.asList(person,
                person), person))));

            // Maps.
            checkDataType(client, ignite, Collections.emptyMap());
            checkDataType(client, ignite, Collections.singletonMap(1, person));
            checkDataType(client, ignite, F.asMap(1, person));
            checkDataType(client, ignite, new HashMap<>(F.asMap(1, person)));
            checkDataType(client, ignite, new HashMap<>(F.asMap(new HashSet<>(Arrays.asList(1, 2)),
                Arrays.asList(person, person))));
        }
    }

    /**
     * Check that we get the same value from the cache as we put before.
     *
     * @param client Thin client.
     * @param ignite Ignite node.
     * @param obj Value of data type to check.
     */
    private void checkDataType(IgniteClient client, Ignite ignite, Object obj) {
        IgniteCache<Object, Object> thickCache = ignite.cache(Config.DEFAULT_CACHE_NAME);
        ClientCache<Object, Object> thinCache = client.cache(Config.DEFAULT_CACHE_NAME);

        Integer key = 1;

        thinCache.put(key, obj);

        assertTrue(thinCache.containsKey(key));

        Object cachedObj = thinCache.get(key);

        assertEqualsArraysAware(obj, cachedObj);

        assertEqualsArraysAware(obj, thickCache.get(key));

        assertEquals(client.binary().typeId(obj.getClass().getName()), ignite.binary().typeId(obj.getClass().getName()));

        if (!obj.getClass().isArray()) { // TODO IGNITE-12578
            // Server-side comparison with the original object.
            assertTrue(thinCache.replace(key, obj, obj));

            // Server-side comparison with the restored object.
            assertTrue(thinCache.remove(key, cachedObj));
        }
    }

    /**
     * Assert values equals (deep equals for arrays).
     *
     * @param exp Expected value.
     * @param actual Actual value.
     */
    private void assertEqualsArraysAware(Object exp, Object actual) {
        if (exp instanceof Object[])
            assertArrayEquals((Object[])exp, (Object[])actual);
        else if (U.isPrimitiveArray(exp))
            assertArrayEquals(new Object[] {exp}, new Object[] {actual}); // Hack to compare primitive arrays.
        else
            assertEquals(exp, actual);
    }

    /**
     * Test that thin client generates valid typeId for system types.
     */
    @Test
    public void testSystemDataType() throws Exception {
        try (Ignite thickClient = Ignition.start(Config.getServerConfiguration());
             IgniteClient thinClient = Ignition.startClient(getClientConfiguration())
        ) {
            Object val = Collections.emptyList();

            thickClient.cache(DEFAULT_CACHE_NAME).put(3, val);
            thinClient.cache(DEFAULT_CACHE_NAME).put(2, val);

            Object outVal = thickClient.cache(DEFAULT_CACHE_NAME).get(2);

            assertEquals(val, outVal);
        }
    }

    /**
     * Tested API:
     * <ul>
     * <li>{@link ClientCache#putAll(Map)}</li>
     * <li>{@link ClientCache#getAll(Set)}</li>
     * <li>{@link ClientCache#containsKeys(Set)} (Set)}</li>
     * <li>{@link ClientCache#clear()}</li>
     * <li>{@link ClientCache#clearAll(Set)} ()}</li>
     * </ul>
     */
    @Test
    public void testBatchPutGet() throws Exception {
        // Existing cache, primitive key and object value
        try (Ignite ignored = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ClientCache<Integer, Person> cache = client.cache(Config.DEFAULT_CACHE_NAME);

            Map<Integer, Person> data = IntStream
                .rangeClosed(1, 1000).boxed()
                .collect(Collectors.toMap(i -> i, i -> new Person(i, String.format("Person %s", i))));

            assertFalse(cache.containsKeys(data.keySet()));

            cache.putAll(data);

            assertTrue(cache.containsKeys(data.keySet()));

            Map<Integer, Person> cachedData = cache.getAll(data.keySet());

            assertEquals(data, cachedData);
        }

        // Non-existing cache, object key and primitive value
        try (Ignite ignored = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ClientCache<Person, Integer> cache = client.createCache("testBatchPutGet");

            Map<Person, Integer> data = IntStream
                .rangeClosed(1, 1000).boxed()
                .collect(Collectors.toMap(i -> new Person(i, String.format("Person %s", i)), i -> i));

            assertFalse(cache.containsKeys(data.keySet()));

            cache.putAll(data);

            assertTrue(cache.containsKeys(data.keySet()));

            Map<Person, Integer> cachedData = cache.getAll(data.keySet());

            assertEquals(data, cachedData);

            Set<Person> clearKeys = new HashSet<>();

            Iterator<Person> keyIter = data.keySet().iterator();

            for (int i = 0; i < 100; i++)
                clearKeys.add(keyIter.next());

            cache.clearAll(clearKeys);

            assertFalse(cache.containsKeys(clearKeys));
            assertTrue(cache.containsKeys(
                data.keySet().stream().filter(key -> !data.containsKey(key)).collect(Collectors.toSet())));
            assertEquals(data.size() - clearKeys.size(), cache.size(CachePeekMode.ALL));

            cache.clear();

            assertFalse(cache.containsKeys(data.keySet()));
            assertEquals(0, cache.size(CachePeekMode.ALL));
        }
    }

    /**
     * Tested API:
     * <ul>
     * <li>{@link ClientCache#getAndPut(Object, Object)}</li>
     * <li>{@link ClientCache#getAndRemove(Object)}</li>
     * <li>{@link ClientCache#getAndReplace(Object, Object)}</li>
     * <li>{@link ClientCache#putIfAbsent(Object, Object)}</li>
     * <li>{@link ClientCache#getAndPutIfAbsent(Object, Object)}</li>
     * </ul>
     */
    @Test
    public void testAtomicPutGet() throws Exception {
        try (Ignite ignored = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ClientCache<Integer, String> cache = client.createCache("testRemoveReplace");

            assertNull(cache.getAndPut(1, "1"));
            assertEquals("1", cache.getAndPut(1, "1.1"));

            assertEquals("1.1", cache.getAndRemove(1));
            assertNull(cache.getAndRemove(1));

            assertTrue(cache.putIfAbsent(1, "1"));
            assertFalse(cache.putIfAbsent(1, "1.1"));

            assertEquals("1", cache.getAndReplace(1, "1.1"));
            assertEquals("1.1", cache.getAndReplace(1, "1"));
            assertNull(cache.getAndReplace(2, "2"));

            assertEquals("1", cache.getAndPutIfAbsent(1, "1.1"));
            assertEquals("1", cache.get(1));
            assertNull(cache.getAndPutIfAbsent(3, "3"));
            assertEquals("3", cache.get(3));
        }
    }

    /**
     * Tested API:
     * <ul>
     * <li>{@link ClientCache#replace(Object, Object)}</li>
     * <li>{@link ClientCache#replace(Object, Object, Object)}</li>
     * <li>{@link ClientCache#remove(Object)}</li>
     * <li>{@link ClientCache#remove(Object, Object)}</li>
     * <li>{@link ClientCache#removeAll()}</li>
     * <li>{@link ClientCache#removeAll(Set)}</li>
     * </ul>
     */
    @Test
    public void testRemoveReplace() throws Exception {
        try (Ignite ignored = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ClientCache<Integer, String> cache = client.createCache("testRemoveReplace");

            Map<Integer, String> data = IntStream.rangeClosed(1, 100).boxed()
                .collect(Collectors.toMap(i -> i, Object::toString));

            cache.putAll(data);

            assertFalse(cache.replace(1, "2", "3"));
            assertEquals("1", cache.get(1));
            assertTrue(cache.replace(1, "1", "3"));
            assertEquals("3", cache.get(1));

            assertFalse(cache.replace(101, "101"));
            assertNull(cache.get(101));
            assertTrue(cache.replace(100, "101"));
            assertEquals("101", cache.get(100));

            assertFalse(cache.remove(101));
            assertTrue(cache.remove(100));
            assertNull(cache.get(100));

            assertFalse(cache.remove(99, "100"));
            assertEquals("99", cache.get(99));
            assertTrue(cache.remove(99, "99"));
            assertNull(cache.get(99));

            cache.put(101, "101");

            cache.removeAll(data.keySet());
            assertEquals(1, cache.size());
            assertEquals("101", cache.get(101));

            cache.removeAll();
            assertEquals(0, cache.size());
        }
    }

    /**
     * Test client fails on start if server is unavailable
     */
    @Test
    public void testClientFailsOnStart() {
        ClientConnectionException expEx = null;

        //noinspection EmptyTryBlock
        try (IgniteClient ignored = Ignition.startClient(getClientConfiguration())) {
            // No-op.
        }
        catch (ClientConnectionException connEx) {
            expEx = connEx;
        }
        catch (Exception ex) {
            fail(String.format(
                "%s expected but %s was received: %s",
                ClientConnectionException.class.getName(),
                ex.getClass().getName(),
                ex
            ));
        }

        assertNotNull(
            String.format("%s expected but no exception was received", ClientConnectionException.class.getName()),
            expEx
        );
    }

    /**
     * Test PESSIMISTIC REPEATABLE_READ tx holds lock and other tx should be timed out.
     */
    @Test
    public void testPessimisticRepeatableReadsTransactionHoldsLock() throws Exception {
        testPessimisticTxLocking(REPEATABLE_READ);
    }

    /**
     * Test PESSIMISTIC SERIALIZABLE tx holds lock and other tx should be timed out.
     */
    @Test
    public void testPessimisticSerializableTransactionHoldsLock() throws Exception {
        testPessimisticTxLocking(SERIALIZABLE);
    }

    /**
     * Test pessimistic tx holds the lock.
     */
    private void testPessimisticTxLocking(TransactionIsolation isolation) throws Exception {
        try (Ignite ignite = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ClientCache<Integer, String> cache = client.createCache(new ClientCacheConfiguration()
                    .setName("cache")
                    .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
            );
            cache.put(0, "value0");

            IgniteInternalFuture<?> fut;

            try (ClientTransaction tx = client.transactions().txStart(PESSIMISTIC, isolation)) {
                assertEquals("value0", cache.get(0));

                CyclicBarrier barrier = new CyclicBarrier(2);

                fut = GridTestUtils.runAsync(() -> {
                    try (ClientTransaction tx2 = client.transactions().txStart(OPTIMISTIC, REPEATABLE_READ, 500)) {
                        cache.put(0, "value2");
                        tx2.commit();
                    } finally {
                        try {
                            barrier.await(2000, TimeUnit.MILLISECONDS);
                        } catch (Throwable ignore) {
                            // No-op.
                        }
                    }
                });

                barrier.await(2000, TimeUnit.MILLISECONDS);

                tx.commit();
            }

            assertEquals("value0", cache.get(0));

            assertThrowsAnyCause(null, fut::get, ClientException.class,
                    "Failed to acquire lock within provided timeout");
        }
    }

    /**
     * Test OPTIMISTIC SERIALIZABLE tx rolls backs if another TX commits.
     */
    @Test
    public void testOptimitsticSerializableTransactionHoldsLock() throws Exception {
        try (Ignite ignite = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ClientCache<Integer, String> cache = client.createCache(new ClientCacheConfiguration()
                    .setName("cache")
                    .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
            );
            cache.put(0, "value0");

            final CountDownLatch latch = new CountDownLatch(1);

            try (ClientTransaction tx = client.transactions().txStart(OPTIMISTIC, SERIALIZABLE)) {
                assertEquals("value0", cache.get(0));

                IgniteInternalFuture<?> fut = GridTestUtils.runAsync(() -> {
                    try (ClientTransaction tx2 = client.transactions().txStart(OPTIMISTIC, REPEATABLE_READ)) {
                        cache.put(0, "value2");
                        tx2.commit();
                    }
                    finally {
                        latch.countDown();
                    }
                });

                latch.await();

                cache.put(0, "value1");

                fut.get();

                assertThrowsAnyCause(null, () -> {
                    tx.commit();
                    return null;
                }, ClientException.class, "read/write conflict");
            }

            assertEquals("value2", cache.get(0));
        }
    }

    /**
     * Test OPTIMISTIC REPEATABLE_READ tx doesn't conflict with a regular cache put.
     */
    @Test
    public void testOptimitsticRepeatableReadUpdatesValue() throws Exception {
        try (Ignite ignored = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ClientCache<Integer, String> cache = client.createCache(new ClientCacheConfiguration()
                    .setName("cache")
                    .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
            );
            cache.put(0, "value0");

            try (ClientTransaction tx = client.transactions().txStart(OPTIMISTIC, REPEATABLE_READ)) {
                assertEquals("value0", cache.get(0));

                cache.put(0, "value1");

                GridTestUtils.runAsync(() -> {
                    assertEquals("value0", cache.get(0));

                    cache.put(0, "value2");

                    assertEquals("value2", cache.get(0));
                }).get();

                tx.commit();
            }

            assertEquals("value1", cache.get(0));
        }
    }

    /**
     * Test that client-connector worker can process further transactional requests (resume transactions) after
     * external termination of previous transaction.
     */
    @Test
    public void testTxResumeAfterTxTimeout() throws Exception {
        IgniteConfiguration cfg = Config.getServerConfiguration().setClientConnectorConfiguration(
            new ClientConnectorConfiguration().setThreadPoolSize(1));

        try (Ignite ignite = Ignition.start(cfg); IgniteClient client = Ignition.startClient(getClientConfiguration())) {
            String cacheName = "cache";

            IgniteCache<Object, Object> igniteCache = ignite.createCache(new CacheConfiguration<>(cacheName)
                .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL));

            try (ClientTransaction clientTx = client.transactions().txStart()) {
                runAsync(() -> {
                    try (Transaction tx = ignite.transactions().txStart(PESSIMISTIC, READ_COMMITTED)) {
                        igniteCache.put(0, 0); // Lock key by ignite node.

                        try {
                            // Start, but don't close the transaction (to keep it in the threadMap after timeout).
                            client.transactions().txStart(PESSIMISTIC, READ_COMMITTED, 200L);

                            // Wait until transaction interrupted externally by timeout.
                            client.cache(cacheName).put(0, 0);

                            fail();
                        }
                        catch (ClientException ignored) {
                            // Expected.
                        }
                    }
                }).get();

                // Resume tx in the worker with interrupted transaction.
                assertFalse(client.cache(cacheName).containsKey(0));
            }
        }
    }

    /**
     * Test transactions.
     */
    @Test
    public void testTransactions() throws Exception {
        try (Ignite ignite = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ClientCache<Integer, String> cache = client.createCache(new ClientCacheConfiguration()
                    .setName("cache")
                    .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
            );

            cache.put(0, "value0");
            cache.put(1, "value1");

            // Test nested transactions is not possible.
            try (ClientTransaction tx = client.transactions().txStart()) {
                try (ClientTransaction tx1 = client.transactions().txStart()) {
                    fail();
                }
                catch (ClientException expected) {
                    // No-op.
                }
            }

            // Test implicit rollback when transaction closed.
            try (ClientTransaction tx = client.transactions().txStart()) {
                cache.put(1, "value2");
            }

            assertEquals("value1", cache.get(1));

            // Test explicit rollback.
            try (ClientTransaction tx = client.transactions().txStart()) {
                cache.put(1, "value2");

                tx.rollback();
            }

            assertEquals("value1", cache.get(1));

            // Test commit.
            try (ClientTransaction tx = client.transactions().txStart()) {
                cache.put(1, "value2");

                tx.commit();
            }

            assertEquals("value2", cache.get(1));

            // Test end of already completed transaction.
            ClientTransaction tx0 = client.transactions().txStart();

            tx0.close();

            try {
                tx0.commit();

                fail();
            }
            catch (ClientException expected) {
                // No-op.
            }

            // Test end of outdated transaction.
            try (ClientTransaction tx = client.transactions().txStart()) {
                try {
                    tx0.commit();

                    fail();
                }
                catch (ClientException expected) {
                    // No-op.
                }

                tx.commit();
            }

            // Test transaction with a timeout.
            long TX_TIMEOUT = 200L;

            try (ClientTransaction tx = client.transactions().txStart(PESSIMISTIC, READ_COMMITTED, TX_TIMEOUT)) {
                long txStartedTime = U.currentTimeMillis();

                cache.put(1, "value3");

                while (txStartedTime + TX_TIMEOUT >= U.currentTimeMillis())
                    U.sleep(100L);

                try {
                    cache.put(1, "value4");

                    fail();
                }
                catch (ClientException expected) {
                    // No-op.
                }

                try {
                    tx.commit();

                    fail();
                }
                catch (ClientException expected) {
                    // No-op.
                }
            }

            assertEquals("value2", cache.get(1));

            cache.put(1, "value5");

            // Test failover.
            ObjectName mbeanName = U.makeMBeanName(ignite.name(), "Clients", ClientListenerProcessor.class.getSimpleName());

            ClientProcessorMXBean mxBean = MBeanServerInvocationHandler.newProxyInstance(
                    ManagementFactory.getPlatformMBeanServer(), mbeanName, ClientProcessorMXBean.class, true);

            try (ClientTransaction tx = client.transactions().txStart()) {
                cache.put(1, "value6");

                mxBean.dropAllConnections();

                try {
                    cache.put(1, "value7");

                    fail();
                }
                catch (ClientException expected) {
                    // No-op.
                }

                // Start new transaction doesn't recover cache operations on failed channel.
                try (ClientTransaction tx1 = client.transactions().txStart()) {
                    fail();
                }
                catch (ClientException expected) {
                    // No-op.
                }

                try {
                    cache.get(1);

                    fail();
                }
                catch (ClientException expected) {
                    // No-op.
                }

                // Close outdated transaction doesn't recover cache operations on failed channel.
                tx0.close();

                try {
                    cache.get(1);

                    fail();
                }
                catch (ClientException expected) {
                    // No-op.
                }
            }

            assertEquals("value5", cache.get(1));

            // Test concurrent transactions in different connections.
            try (IgniteClient client1 = Ignition.startClient(getClientConfiguration())) {
                ClientCache<Integer, String> cache1 = client1.cache("cache");

                try (ClientTransaction tx = client.transactions().txStart(OPTIMISTIC, READ_COMMITTED)) {
                    cache.put(0, "value8");

                    try (ClientTransaction tx1 = client1.transactions().txStart(OPTIMISTIC, READ_COMMITTED)) {
                        assertEquals("value8", cache.get(0));
                        assertEquals("value0", cache1.get(0));

                        cache1.put(1, "value9");

                        assertEquals("value5", cache.get(1));
                        assertEquals("value9", cache1.get(1));

                        tx1.commit();

                        assertEquals("value9", cache.get(1));
                    }

                    assertEquals("value0", cache1.get(0));

                    tx.commit();

                    assertEquals("value8", cache1.get(0));
                }
            }

            // Check different types of cache operations.
            try (ClientTransaction tx = client.transactions().txStart()) {
                // Operations: put, putAll, putIfAbsent.
                cache.put(2, "value10");
                cache.putAll(F.asMap(1, "value11", 3, "value12"));
                cache.putIfAbsent(4, "value13");

                // Operations: get, getAll, getAndPut, getAndRemove, getAndReplace, getAndPutIfAbsent.
                assertEquals("value10", cache.get(2));
                assertEquals(F.asMap(1, "value11", 2, "value10"),
                        cache.getAll(new HashSet<>(Arrays.asList(1, 2))));
                assertEquals("value13", cache.getAndPut(4, "value14"));
                assertEquals("value14", cache.getAndPutIfAbsent(4, "valueDiscarded"));
                assertEquals("value14", cache.get(4));
                assertEquals("value14", cache.getAndReplace(4, "value15"));
                assertEquals("value15", cache.getAndRemove(4));
                assertNull(cache.getAndPutIfAbsent(10, "valuePutIfAbsent"));
                assertEquals("valuePutIfAbsent", cache.get(10));

                // Operations: contains.
                assertTrue(cache.containsKey(2));
                assertFalse(cache.containsKey(4));
                assertTrue(cache.containsKeys(ImmutableSet.of(2, 10)));
                assertFalse(cache.containsKeys(ImmutableSet.of(2, 4)));

                // Operations: replace.
                cache.put(4, "");
                assertTrue(cache.replace(4, "value16"));
                assertTrue(cache.replace(4, "value16", "value17"));

                // Operations: remove, removeAll
                cache.putAll(F.asMap(5, "", 6, ""));
                assertTrue(cache.remove(5));
                assertTrue(cache.remove(4, "value17"));
                cache.removeAll(new HashSet<>(Arrays.asList(3, 6)));
                assertFalse(cache.containsKey(3));
                assertFalse(cache.containsKey(6));

                tx.rollback();
            }

            assertEquals(F.asMap(0, "value8", 1, "value9"),
                    cache.getAll(new HashSet<>(Arrays.asList(0, 1))));
            assertFalse(cache.containsKey(2));

            // Test concurrent transactions started by different threads.
            try (ClientTransaction tx = client.transactions().txStart(PESSIMISTIC, READ_COMMITTED)) {
                CyclicBarrier barrier = new CyclicBarrier(2);

                cache.put(0, "value18");

                IgniteInternalFuture<?> fut = GridTestUtils.runAsync(() -> {
                    try (ClientTransaction tx1 = client.transactions().txStart(PESSIMISTIC, READ_COMMITTED)) {
                        cache.put(1, "value19");

                        barrier.await();

                        assertEquals("value8", cache.get(0));

                        barrier.await();

                        tx1.commit();

                        barrier.await();

                        assertEquals("value18", cache.get(0));
                    }
                    catch (InterruptedException | BrokenBarrierException ignore) {
                        // No-op.
                    }
                });

                barrier.await();

                assertEquals("value9", cache.get(1));

                barrier.await();

                tx.commit();

                barrier.await();

                assertEquals("value19", cache.get(1));

                fut.get();
            }

            // Test transaction usage by different threads.
            try (ClientTransaction tx = client.transactions().txStart(PESSIMISTIC, READ_COMMITTED)) {
                cache.put(0, "value20");

                GridTestUtils.runAsync(() -> {
                    // Implicit transaction started here.
                    cache.put(1, "value21");

                    assertEquals("value18", cache.get(0));

                    try {
                        // Transaction can't be commited by another thread.
                        tx.commit();

                        fail();
                    }
                    catch (ClientException expected) {
                        // No-op.
                    }

                    // Transaction can be closed by another thread.
                    tx.close();

                    assertEquals("value18", cache.get(0));
                }).get();

                assertEquals("value21", cache.get(1));

                try {
                    // Transaction can't be commited after another thread close this transaction.
                    tx.commit();

                    fail();
                }
                catch (ClientException expected) {
                    // No-op.
                }

                assertEquals("value18", cache.get(0));

                // Start implicit transaction after explicit transaction has been closed by another thread.
                cache.put(0, "value22");

                GridTestUtils.runAsync(() -> assertEquals("value22", cache.get(0))).get();

                // New explicit transaction can be started after current transaction has been closed by another thread.
                try (ClientTransaction tx1 = client.transactions().txStart(PESSIMISTIC, READ_COMMITTED)) {
                    cache.put(0, "value23");

                    tx1.commit();
                }

                assertEquals("value23", cache.get(0));
            }

            // Test active transactions limit.
            int txLimit = ignite.configuration().getClientConnectorConfiguration().getThinClientConfiguration()
                    .getMaxActiveTxPerConnection();

            List<ClientTransaction> txs = new ArrayList<>(txLimit);

            for (int i = 0; i < txLimit; i++) {
                Thread t = new Thread(() -> txs.add(client.transactions().txStart()));

                t.start();

                t.join();
            }

            try (ClientTransaction ignored = client.transactions().txStart()) {
                fail();
            }
            catch (ClientException e) {
                ClientServerError cause = (ClientServerError) e.getCause();
                assertEquals(ClientStatus.TX_LIMIT_EXCEEDED, cause.getCode());
            }

            for (ClientTransaction tx : txs)
                tx.close();

            // Test that new transaction can be started after commit of the previous one without closing.
            ClientTransaction tx = client.transactions().txStart();
            tx.commit();

            tx = client.transactions().txStart();
            tx.rollback();

            // Test that new transaction can be started after rollback of the previous one without closing.
            tx = client.transactions().txStart();
            tx.commit();

            // Test that implicit transaction started after commit of previous one without closing.
            cache.put(0, "value24");

            GridTestUtils.runAsync(() -> assertEquals("value24", cache.get(0))).get();
        }
    }

    /**
     * Test transactions.
     */
    @Test
    public void testTransactionsAsync() throws Exception {
        try (Ignite ignored = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ClientCache<Integer, String> cache = client.createCache(new ClientCacheConfiguration()
                    .setName("cache")
                    .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
            );

            cache.put(0, "value0");
            cache.put(1, "value1");

            // Test implicit rollback when transaction closed.
            try (ClientTransaction tx = client.transactions().txStart()) {
                cache.putAsync(1, "value2").get();
            }

            assertEquals("value1", cache.get(1));

            // Test explicit rollback.
            try (ClientTransaction tx = client.transactions().txStart()) {
                cache.putAsync(1, "value2").get();

                tx.rollback();
            }

            assertEquals("value1", cache.get(1));

            // Test commit.
            try (ClientTransaction tx = client.transactions().txStart()) {
                cache.putAsync(1, "value2").get();

                tx.commit();
            }

            assertEquals("value2", cache.get(1));
        }
    }

    /**
     * Test transactions with label.
     */
    @Test
    public void testTransactionsWithLabel() throws Exception {
        try (IgniteEx ignite = (IgniteEx)Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ClientCache<Integer, String> cache = client.createCache(new ClientCacheConfiguration()
                .setName("cache")
                .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
            );

            SystemView<TransactionView> txsView = ignite.context().systemView().view(TXS_MON_LIST);

            cache.put(0, "value1");

            try (ClientTransaction tx = client.transactions().withLabel("label").txStart()) {
                cache.put(0, "value2");

                assertEquals(1, F.size(txsView.iterator()));

                TransactionView txv = txsView.iterator().next();

                assertEquals("label", txv.label());

                assertEquals("value2", cache.get(0));
            }

            assertEquals("value1", cache.get(0));

            try (ClientTransaction tx = client.transactions().withLabel("label1").withLabel("label2").txStart()) {
                cache.put(0, "value2");

                assertEquals(1, F.size(txsView.iterator()));

                TransactionView txv = txsView.iterator().next();

                assertEquals("label2", txv.label());

                tx.commit();
            }

            assertEquals("value2", cache.get(0));

            // Test concurrent with label and without label transactions.
            try (ClientTransaction tx = client.transactions().withLabel("label").txStart(PESSIMISTIC, READ_COMMITTED)) {
                CyclicBarrier barrier = new CyclicBarrier(2);

                cache.put(0, "value3");

                IgniteInternalFuture<?> fut = GridTestUtils.runAsync(() -> {
                    try (ClientTransaction tx1 = client.transactions().txStart(PESSIMISTIC, READ_COMMITTED)) {
                        cache.put(1, "value3");

                        barrier.await();

                        assertEquals("value2", cache.get(0));

                        barrier.await();
                    }
                    catch (InterruptedException | BrokenBarrierException ignore) {
                        // No-op.
                    }
                });

                barrier.await();

                assertNull(cache.get(1));

                assertEquals(1, F.size(txsView.iterator(), txv -> txv.label() == null));
                assertEquals(1, F.size(txsView.iterator(), txv -> "label".equals(txv.label())));

                barrier.await();

                fut.get();
            }

            // Test nested transactions is not possible.
            try (ClientTransaction tx = client.transactions().withLabel("label1").txStart()) {
                try (ClientTransaction tx1 = client.transactions().txStart()) {
                    fail();
                }
                catch (ClientException expected) {
                    // No-op.
                }

                try (ClientTransaction tx1 = client.transactions().withLabel("label2").txStart()) {
                    fail();
                }
                catch (ClientException expected) {
                    // No-op.
                }
            }
        }
    }

    /**
     * Test cache with expiry policy.
     */
    @Test
    public void testExpiryPolicy() throws Exception {
        long ttl = 600L;
        int MAX_RETRIES = 5;

        try (Ignite ignite = Ignition.start(Config.getServerConfiguration());
             IgniteClient client = Ignition.startClient(getClientConfiguration())
        ) {
            ClientCache<Integer, Object> cache = client.createCache(new ClientCacheConfiguration()
                    .setName("cache")
                    .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
            );

            Duration dur = new Duration(TimeUnit.MILLISECONDS, ttl);

            ClientCache<Integer, Object> cachePlcCreated = cache.withExpiryPolicy(new CreatedExpiryPolicy(dur));
            ClientCache<Integer, Object> cachePlcUpdated = cache.withExpiryPolicy(new ModifiedExpiryPolicy(dur));
            ClientCache<Integer, Object> cachePlcAccessed = cache.withExpiryPolicy(new AccessedExpiryPolicy(dur));

            for (int i = 0; i < MAX_RETRIES; i++) {
                cache.clear();

                long ts = U.currentTimeMillis();

                cache.put(0, 0);
                cachePlcCreated.put(1, 1);
                cachePlcUpdated.put(2, 2);
                cachePlcAccessed.put(3, 3);

                U.sleep(ttl / 3 * 2);

                boolean containsKey0 = cache.containsKey(0);
                boolean containsKey1 = cache.containsKey(1);
                boolean containsKey2 = cache.containsKey(2);
                boolean containsKey3 = cache.containsKey(3);

                if (U.currentTimeMillis() - ts >= ttl) // Retry if this block execution takes too long.
                    continue;

                assertTrue(containsKey0);
                assertTrue(containsKey1);
                assertTrue(containsKey2);
                assertTrue(containsKey3);

                ts = U.currentTimeMillis();

                cachePlcCreated.put(1, 2);
                cachePlcCreated.get(1); // Update and access key with created expire policy.
                cachePlcUpdated.put(2, 3); // Update key with modified expire policy.
                cachePlcAccessed.get(3); // Access key with accessed expire policy.

                U.sleep(ttl / 3 * 2);

                containsKey0 = cache.containsKey(0);
                containsKey1 = cache.containsKey(1);
                containsKey2 = cache.containsKey(2);
                containsKey3 = cache.containsKey(3);

                if (U.currentTimeMillis() - ts >= ttl) // Retry if this block execution takes too long.
                    continue;

                assertTrue(containsKey0);
                assertFalse(containsKey1);
                assertTrue(containsKey2);
                assertTrue(containsKey3);

                U.sleep(ttl / 3 * 2);

                cachePlcUpdated.get(2); // Access key with updated expire policy.

                U.sleep(ttl / 3 * 2);

                assertTrue(cache.containsKey(0));
                assertFalse(cache.containsKey(1));
                assertFalse(cache.containsKey(2));
                assertFalse(cache.containsKey(3));

                // Expire policy, keep binary and transactional flags together.
                ClientCache<Integer, Object> binCache = cachePlcCreated.withKeepBinary();

                try (ClientTransaction tx = client.transactions().txStart()) {
                    binCache.put(4, new T2<>("test", "test"));

                    tx.commit();
                }

                assertTrue(binCache.get(4) instanceof BinaryObject);
                assertFalse(cache.get(4) instanceof BinaryObject);

                U.sleep(ttl / 3 * 4);

                assertFalse(cache.containsKey(4));

                return;
            }

            fail("Failed to check expire policy within " + MAX_RETRIES + " retries (block execution takes too long)");
        }
    }

    /**
     *
     */
    private static ClientConfiguration getClientConfiguration() {
        return new ClientConfiguration()
                .setAddresses(Config.SERVER)
                .setSendBufferSize(0)
                .setReceiveBufferSize(0);
    }
}
