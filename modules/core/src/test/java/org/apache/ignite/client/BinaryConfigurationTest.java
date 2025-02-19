/*
 * Copyright 2022 GridGain Systems, Inc. and Contributors.
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

import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.binary.BinaryBasicNameMapper;
import org.apache.ignite.binary.BinaryNameMapper;
import org.apache.ignite.configuration.BinaryConfiguration;
import org.apache.ignite.configuration.ClientConfiguration;
import org.apache.ignite.internal.binary.BinaryObjectImpl;
import org.apache.ignite.internal.client.thin.AbstractThinClientTest;
import org.apache.ignite.internal.processors.platform.client.IgniteClientException;
import org.apache.ignite.testframework.GridTestUtils;
import org.junit.Test;

import static org.apache.ignite.internal.binary.BinaryUtils.FLAG_COMPACT_FOOTER;

/**
 * Tests binary configuration behavior.
 */
public class BinaryConfigurationTest extends AbstractThinClientTest {
    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();
        super.afterTest();
    }

    /**
     * Tests that client retrieves binary configuration from the server by default.
     */
    @Test
    public void testAutoBinaryConfigurationEnabledRetrievesValuesFromServer() throws Exception {
        Ignite server = startGrid(0);

        try (IgniteClient client = startClient(0)) {
            BinaryObjectImpl res = getClientBinaryObjectFromServer(server, client);

            // Server-side defaults are compact footers and full name mapper.
            assertTrue(res.isFlagSet(FLAG_COMPACT_FOOTER));
            assertEquals("org.apache.ignite.client.Person", res.type().typeName());
        }
    }

    /**
     * Tests that client retrieves binary configuration from the server and overrides client configuration settings.
     */
    @Test
    public void testAutoBinaryConfigurationEnabledOverridesExplicitClientSettings() throws Exception {
        Ignite server = startGrid(0);

        BinaryConfiguration binaryCfg = new BinaryConfiguration()
                .setCompactFooter(false)
                .setNameMapper(new BinaryBasicNameMapper().setSimpleName(true));

        ClientConfiguration clientCfg = getClientConfiguration(server)
                .setBinaryConfiguration(binaryCfg);

        try (IgniteClient client = Ignition.startClient(clientCfg)) {
            BinaryObjectImpl res = getClientBinaryObjectFromServer(server, client);

            // Server-side defaults are compact footers and full name mapper.
            assertTrue(res.isFlagSet(FLAG_COMPACT_FOOTER));
            assertEquals("org.apache.ignite.client.Person", res.type().typeName());
        }
    }

    /**
     * Tests that client does not retrieve binary configuration from the server when this behavior is disabled.
     */
    @Test
    public void testAutoBinaryConfigurationDisabledKeepsClientSettingsAsIs() throws Exception {
        Ignite server = startGrid(0);

        BinaryConfiguration binaryCfg = new BinaryConfiguration()
                .setCompactFooter(false)
                .setNameMapper(new BinaryBasicNameMapper().setSimpleName(true));

        ClientConfiguration clientCfg = getClientConfiguration(server)
                .setAutoBinaryConfigurationEnabled(false)
                .setBinaryConfiguration(binaryCfg);

        try (IgniteClient client = Ignition.startClient(clientCfg)) {
            BinaryObjectImpl res = getClientBinaryObjectFromServer(server, client);

            assertFalse(res.isFlagSet(FLAG_COMPACT_FOOTER));
            assertEquals("Person", res.type().typeName());
        }
    }

    /**
     * Tests that client throws an exception on start when server has a custom mapper configured, but client has not.
     */
    @Test
    public void testCustomMapperOnServerDefaultMapperOnClientThrows() throws Exception {
        BinaryConfiguration serverBinaryCfg = new BinaryConfiguration()
                .setNameMapper(new CustomBinaryNameMapper());

        Ignite server = startGrid("0", cfg -> cfg.setBinaryConfiguration(serverBinaryCfg));

        BinaryConfiguration binaryCfg = new BinaryConfiguration()
                .setNameMapper(new BinaryBasicNameMapper());

        ClientConfiguration clientCfg = getClientConfiguration(server)
                .setBinaryConfiguration(binaryCfg);

        GridTestUtils.assertThrowsAnyCause(null, () -> Ignition.startClient(clientCfg), IgniteClientException.class,
                "Custom binary name mapper is configured on the server, but not on the client."
                        + " Update client BinaryConfigration to match the server.");
    }

    /**
     * Tests that client works as expected when custom mapper is configured on both sides.
     */
    @Test
    public void testCustomMapperOnServerCustomMapperOnClientDoesNotThrow() throws Exception {
        BinaryConfiguration binaryCfg = new BinaryConfiguration()
                .setNameMapper(new CustomBinaryNameMapper());

        Ignite server = startGrid("0", cfg -> cfg.setBinaryConfiguration(binaryCfg));

        ClientConfiguration clientCfg = getClientConfiguration(server)
                .setBinaryConfiguration(binaryCfg);

        try (IgniteClient client = Ignition.startClient(clientCfg)) {
            BinaryObjectImpl res = getClientBinaryObjectFromServer(server, client);

            assertTrue(res.isFlagSet(FLAG_COMPACT_FOOTER));
            assertEquals("org.apache.ignite.client.Person_", res.type().typeName());
        }
    }

    /**
     * Inserts an object from the client and retrieves it as a binary object from the server.
     *
     * @param server Server.
     * @param client Client.
     * @return Binary object.
     */
    private BinaryObjectImpl getClientBinaryObjectFromServer(Ignite server, IgniteClient client) {
        client.getOrCreateCache("c").put(1, new Person(1, "1"));

        return server.cache("c").<Integer, BinaryObjectImpl>withKeepBinary().get(1);
    }

    /**
     * Custom mapper.
     */
    private static class CustomBinaryNameMapper implements BinaryNameMapper {
        /** {@inheritDoc} */
        @Override public String typeName(String clsName) {
            return clsName + "_";
        }

        /** {@inheritDoc} */
        @Override public String fieldName(String fieldName) {
            return fieldName + "!";
        }
    }
}
