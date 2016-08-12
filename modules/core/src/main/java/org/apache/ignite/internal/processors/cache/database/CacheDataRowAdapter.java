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

package org.apache.ignite.internal.processors.cache.database;

import java.nio.ByteBuffer;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.internal.pagemem.Page;
import org.apache.ignite.internal.pagemem.PageIdUtils;
import org.apache.ignite.internal.processors.cache.CacheObject;
import org.apache.ignite.internal.processors.cache.CacheObjectContext;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.IncompleteCacheObject;
import org.apache.ignite.internal.processors.cache.IncompleteObject;
import org.apache.ignite.internal.processors.cache.KeyCacheObject;
import org.apache.ignite.internal.processors.cache.database.tree.io.DataPageIO;
import org.apache.ignite.internal.processors.cache.database.tree.io.CacheVersionIO;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.util.tostring.GridToStringInclude;
import org.apache.ignite.internal.util.typedef.internal.S;

import static org.apache.ignite.internal.pagemem.PageIdUtils.itemId;
import static org.apache.ignite.internal.pagemem.PageIdUtils.pageId;

/**
 * Assembles entry from data pages.
 */
public class CacheDataRowAdapter implements CacheDataRow {
    /** */
    @GridToStringInclude
    protected long link;

    /** */
    @GridToStringInclude
    protected KeyCacheObject key;

    /** */
    @GridToStringInclude
    protected CacheObject val;

    /** */
    @GridToStringInclude
    protected GridCacheVersion ver;

    /**
     * @param link Link.
     */
    public CacheDataRowAdapter(long link) {
        this.link = link;
    }

    /**
     * Assemble row from data pages.
     *
     * @param cctx Cache context.
     * @param keyOnly {@code True} if need read only key object.
     * @throws IgniteCheckedException If failed.
     */
    public final void initFromLink(GridCacheContext<?, ?> cctx, boolean keyOnly) throws IgniteCheckedException {
        assert cctx != null;
        assert link != 0;
        assert key == null;

        final CacheObjectContext coctx = cctx.cacheObjectContext();

        long nextLink;
        IncompleteObject<?> incomplete = null;

        try (Page page = page(pageId(link), cctx)) {
            ByteBuffer buf = page.getForRead();

            try {
                DataPageIO io = DataPageIO.VERSIONS.forPage(buf);

                nextLink = io.setPositionAndLimitOnPayload(buf, itemId(link));

                if (nextLink == 0) {
                    // Read the whole row at once.
                    key = coctx.processor().toKeyCacheObject(coctx, buf);

                    if (!keyOnly) {
                        val = coctx.processor().toCacheObject(coctx, buf);
                        ver = CacheVersionIO.read(buf);
                    }
                }
                else {
                    // Read the first chunk of multi-page entry.
                    incomplete = readIncompleteKey(coctx, buf, null);

                    if (key != null) {
                        if (keyOnly)
                            return;

                        incomplete = readIncompleteValue(coctx, buf, null);

                        if (val != null)
                            incomplete = readIncompleteVersion(buf, null);
                    }
                }
            }
            finally {
                page.releaseRead();
            }
        }

        // Read other chunks outside of the lock on first page.
        while (nextLink != 0) {
            assert !isReady();

            try (final Page p = page(pageId(nextLink), cctx)) {
                try {
                    final ByteBuffer b = p.getForRead();

                    DataPageIO io = DataPageIO.VERSIONS.forPage(b);

                    nextLink = io.setPositionAndLimitOnPayload(b, itemId(nextLink));

                    // Read key.
                    if (key == null) {
                        incomplete = readIncompleteKey(coctx, b, (IncompleteCacheObject)incomplete);

                        if (key == null)
                            continue;

                        if (keyOnly)
                            return;

                        incomplete = null;
                    }

                    // Read value.
                    if (val == null) {
                        incomplete = readIncompleteValue(coctx, b, (IncompleteCacheObject)incomplete);

                        if (val == null)
                            continue;

                        incomplete = null;
                    }

                    // Read version.
                    if (ver == null)
                        incomplete = readIncompleteVersion(b, incomplete);
                }
                finally {
                    p.releaseRead();
                }
            }
        }
    }

    /**
     * @param coctx Cache object context.
     * @param buf Buffer.
     * @param incomplete Incomplete object.
     * @return Incomplete object.
     * @throws IgniteCheckedException If failed.
     */
    private IncompleteCacheObject readIncompleteKey(
        CacheObjectContext coctx,
        ByteBuffer buf,
        IncompleteCacheObject incomplete
    ) throws IgniteCheckedException {
        incomplete = coctx.processor().toKeyCacheObject(coctx, buf, incomplete);

        if (incomplete.isReady()) {
            key = (KeyCacheObject)incomplete.object();

            assert key != null;
        }
        else
            assert !buf.hasRemaining();

        return incomplete;
    }

    /**
     * @param coctx Cache object context.
     * @param buf Buffer.
     * @param incomplete Incomplete object.
     * @return Incomplete object.
     * @throws IgniteCheckedException If failed.
     */
    private IncompleteCacheObject readIncompleteValue(
        CacheObjectContext coctx,
        ByteBuffer buf,
        IncompleteCacheObject incomplete
    ) throws IgniteCheckedException {
        incomplete = coctx.processor().toCacheObject(coctx, buf, incomplete);

        if (incomplete.isReady()) {
            val = incomplete.object();

            assert val != null;
        }
        else
            assert !buf.hasRemaining();

        return incomplete;
    }

    /**
     * @param buf Buffer.
     * @param incomplete Incomplete object.
     * @return Incomplete object.
     */
    private IncompleteObject<?> readIncompleteVersion(
        ByteBuffer buf,
        IncompleteObject<?> incomplete
    ) throws IgniteCheckedException {
        if (incomplete == null) {
            int remaining = buf.remaining();

            if (remaining == 0)
                return null;

            int size = CacheVersionIO.readSize(buf);

            if (remaining >= size) {
                // If the whole version is on a single page, just read it.
                ver = CacheVersionIO.read(buf);

                assert !buf.hasRemaining(): buf.remaining();
                assert ver != null;

                return null;
            }

            // We have to read multipart version.
            incomplete = new IncompleteObject<>(new byte[size]);
        }

        incomplete.readData(buf);

        if (incomplete.isReady()) {
            final ByteBuffer verBuf = ByteBuffer.wrap(incomplete.data());

            verBuf.order(buf.order());

            ver = CacheVersionIO.read(verBuf);

            assert ver != null;
        }

        assert !buf.hasRemaining();

        return incomplete;
    }

    /**
     * @return {@code True} if entry is ready.
     */
    public boolean isReady() {
        return ver != null && val != null && key != null;
    }

    /** {@inheritDoc} */
    @Override public KeyCacheObject key() {
        assert key != null : "Key is not ready: " + this;

        return key;
    }

    /** {@inheritDoc} */
    @Override public CacheObject value() {
        assert val != null : "Value is not ready: " + this;

        return val;
    }

    /** {@inheritDoc} */
    @Override public GridCacheVersion version() {
        assert ver != null : "Version is not ready: " + this;

        return ver;
    }

    /** {@inheritDoc} */
    @Override public int partition() {
        return PageIdUtils.partId(link);
    }

    /** {@inheritDoc} */
    @Override public long link() {
        return link;
    }

    /** {@inheritDoc} */
    @Override public void link(long link) {
        throw new UnsupportedOperationException();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(CacheDataRowAdapter.class, this);
    }

    /**
     * @param pageId Page ID.
     * @param cctx Cache context.
     * @return Page.
     * @throws IgniteCheckedException If failed.
     */
    private Page page(final long pageId, final GridCacheContext cctx) throws IgniteCheckedException {
        return cctx.shared().database().pageMemory().page(cctx.cacheId(), pageId);
    }
}
