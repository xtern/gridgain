/*
 * Copyright 2020 GridGain Systems, Inc. and Contributors.
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

package org.apache.ignite.spark.impl

import org.apache.ignite.cache.query.Query
import org.apache.ignite.configuration.CacheConfiguration
import org.apache.ignite.spark.IgniteContext
import org.apache.spark.{Partition, TaskContext}

import scala.reflect.ClassTag

class IgniteSqlRDD[R: ClassTag, T, K, V](
    ic: IgniteContext,
    cacheName: String,
    cacheCfg: CacheConfiguration[K, V],
    var qry: Query[T],
    conv: (T) ⇒ R,
    keepBinary: Boolean,
    partitions: Array[Partition] = Array(IgnitePartition(0))
) extends IgniteAbstractRDD[R, K, V](ic, cacheName, cacheCfg, keepBinary) {
    override def compute(split: Partition, context: TaskContext): Iterator[R] = {
        val cur = ensureCache().query(qry)

        TaskContext.get().addTaskCompletionListener[Unit]((_) ⇒ cur.close())

        new IgniteQueryIterator[T, R](cur.iterator(), conv)
    }

    override protected def getPartitions: Array[Partition] = partitions
}

object IgniteSqlRDD {
    def apply[R: ClassTag, T, K, V](ic: IgniteContext, cacheName: String, cacheCfg: CacheConfiguration[K, V],
        qry: Query[T], conv: (T) ⇒ R, keepBinary: Boolean,
        partitions: Array[Partition] = Array(IgnitePartition(0))): IgniteSqlRDD[R, T, K, V] =
        new IgniteSqlRDD(ic, cacheName, cacheCfg, qry, conv, keepBinary, partitions)
}
