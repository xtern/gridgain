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

package org.apache.ignite.internal.marshaller.optimized;

import org.apache.ignite.marshaller.Marshaller;
import org.apache.ignite.testframework.junits.common.GridCommonTest;

/**
 * Optimized marshaller self test.
 */
@GridCommonTest(group = "Marshaller")
public class OptimizedMarshallerPooledSelfTest extends OptimizedMarshallerSelfTest {
    /** {@inheritDoc} */
    @Override protected Marshaller marshaller() {
        OptimizedMarshaller m = new OptimizedMarshaller(false);

        m.setPoolSize(8);

        return m;
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        // Reset static registry.
        new OptimizedMarshaller().setPoolSize(0);
    }
}
