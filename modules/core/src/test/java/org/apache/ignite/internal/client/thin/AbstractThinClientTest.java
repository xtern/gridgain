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

package org.apache.ignite.internal.client.thin;

import java.util.Arrays;
import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.ClientConfiguration;
import org.apache.ignite.internal.processors.odbc.ClientListenerProcessor;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.G;
import org.apache.ignite.mxbean.ClientProcessorMXBean;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;

/**
 * Abstract thin client test.
 */
public abstract class AbstractThinClientTest extends GridCommonAbstractTest {
    /**
     * Gets default client configuration.
     */
    protected ClientConfiguration getClientConfiguration() {
        return new ClientConfiguration().setAffinityAwarenessEnabled(false);
    }

    /**
     * Gets default client configuration with addresses set to the specified nodes.
     *
     * @param nodes Server nodes.
     */
    protected ClientConfiguration getClientConfiguration(ClusterNode... nodes) {
        String[] addrs = new String[nodes.length];

        for (int i = 0; i < nodes.length; i++) {
            ClusterNode node = nodes[i];

            addrs[i] = clientHost(node) + ":" + clientPort(node);
        }

        return getClientConfiguration().setAddresses(addrs);
    }

    /**
     * Gets default client configuration with addresses set to the specified nodes.
     *
     * @param ignites Server nodes.
     */
    protected ClientConfiguration getClientConfiguration(Ignite... ignites) {
        ClusterNode[] nodes = Arrays.stream(ignites).map(ignite -> ignite.cluster().localNode()).toArray(ClusterNode[]::new);

        return getClientConfiguration(nodes);
    }

    /**
     * Return thin client port for given node.
     *
     * @param node Node.
     */
    protected int clientPort(ClusterNode node) {
        return node.attribute(ClientListenerProcessor.CLIENT_LISTENER_PORT);
    }

    /**
     * Return host for given node.
     *
     * @param node Node.
     */
    protected String clientHost(ClusterNode node) {
        return F.first(node.addresses());
    }

    /**
     * Start thin client with configured endpoints to specified nodes.
     *
     * @param nodes Nodes to connect.
     * @return Thin client.
     */
    protected IgniteClient startClient(ClusterNode... nodes) {
        ClientConfiguration cfg = getClientConfiguration(nodes);

        return Ignition.startClient(cfg);
    }

    /**
     * Start thin client with configured endpoints to specified ignite instances.
     *
     * @param ignites Ignite instances to connect.
     * @return Thin client.
     */
    protected IgniteClient startClient(Ignite... ignites) {
        return startClient(Arrays.stream(ignites).map(ignite -> ignite.cluster().localNode()).toArray(ClusterNode[]::new));
    }

    /**
     * Start thin client with configured endpoints to specified ignite instance indexes.
     *
     * @param igniteIdxs Ignite instance indexes to connect.
     * @return Thin client.
     */
    protected IgniteClient startClient(int... igniteIdxs) {
        return startClient(Arrays.stream(igniteIdxs).mapToObj(igniteIdx -> grid(igniteIdx).cluster().localNode())
            .toArray(ClusterNode[]::new));
    }

    /**
     * Drop all thin client connections on given Ignite instance.
     *
     * @param ignite Ignite.
     */
    protected void dropAllThinClientConnections(Ignite ignite) {
        ClientProcessorMXBean mxBean = getMxBean(ignite.name(), "Clients",
                ClientProcessorMXBean.class, ClientListenerProcessor.class);

        mxBean.dropAllConnections();
    }

    /**
     * Drop all thin client connections on all Ignite instances.
     */
    protected void dropAllThinClientConnections() {
        for (Ignite ignite : G.allGrids())
            dropAllThinClientConnections(ignite);
    }
}
