/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.llm.internal.xwikistore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.environment.Environment;

/**
 * A marker file in the permanent directory recording that the collections using the XWiki document store must be
 * re-queued for indexing. The Solr core initializer sets it after dropping the chunks of wiki pages, and
 * {@link XWikiDocumentStoreReindexer} clears it once every collection has been re-queued. Being a file, it survives
 * a restart between the two steps.
 *
 * @version $Id$
 * @since 0.10.1
 */
@Component(roles = XWikiDocumentStoreReindexMarker.class)
@Singleton
public class XWikiDocumentStoreReindexMarker
{
    /**
     * The path of the marker file, relative to the permanent directory.
     */
    static final Path RELATIVE_PATH = Path.of("ai-llm", "reindex-xwiki-store");

    @Inject
    private Environment environment;

    @Inject
    private Logger logger;

    /**
     * Set the marker. The write is idempotent, so a caller that is retried sets the marker again without error.
     * A failure is logged as an error with the manual recovery and rethrown, so that the caller does not record the
     * cleanup as done.
     *
     * @throws IOException when the marker file cannot be written
     */
    public void set() throws IOException
    {
        Path marker = getPath();
        try {
            Files.createDirectories(marker.getParent());
            Files.write(marker, new byte[0]);
        } catch (IOException e) {
            this.logger.error("Failed to write the marker file [{}]. The collections using the XWiki document store "
                + "are not re-queued automatically: reindex them manually from the collections administration.",
                marker, e);
            throw e;
        }
    }

    /**
     * @return {@code true} when the marker is set
     */
    public boolean isSet()
    {
        return Files.exists(getPath());
    }

    /**
     * Clear the marker. A failure to delete the file is logged as a warning: the re-queue is idempotent and simply
     * runs again at the next startup.
     */
    public void clear()
    {
        Path marker = getPath();
        try {
            Files.deleteIfExists(marker);
        } catch (IOException e) {
            this.logger.warn("Failed to delete the marker file [{}], the collections using the XWiki document store "
                + "are re-queued again at the next startup. Root cause: [{}]", marker,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("Failed to delete the marker file [{}]", marker, e);
        }
    }

    private Path getPath()
    {
        return this.environment.getPermanentDirectory().toPath().resolve(RELATIVE_PATH);
    }
}
