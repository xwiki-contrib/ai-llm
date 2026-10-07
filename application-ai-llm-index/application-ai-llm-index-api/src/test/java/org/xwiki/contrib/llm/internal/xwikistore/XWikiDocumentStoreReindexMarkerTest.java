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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.xwiki.environment.Environment;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link XWikiDocumentStoreReindexMarker}.
 *
 * @version $Id$
 */
@ComponentTest
class XWikiDocumentStoreReindexMarkerTest
{
    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private XWikiDocumentStoreReindexMarker marker;

    @MockComponent
    private Environment environment;

    @TempDir
    File permanentDirectory;

    @BeforeEach
    void setUp()
    {
        when(this.environment.getPermanentDirectory()).thenReturn(this.permanentDirectory);
    }

    @Test
    void setCreatesTheFileAndClearDeletesIt() throws Exception
    {
        Path expected = this.permanentDirectory.toPath().resolve("ai-llm").resolve("reindex-xwiki-store");
        assertFalse(this.marker.isSet());

        this.marker.set();

        assertTrue(Files.isRegularFile(expected));
        assertTrue(this.marker.isSet());

        // Setting an already set marker is idempotent.
        this.marker.set();
        assertTrue(this.marker.isSet());

        this.marker.clear();

        assertFalse(Files.exists(expected));
        assertFalse(this.marker.isSet());

        // Clearing an unset marker is a no-op.
        this.marker.clear();
        assertFalse(this.marker.isSet());
    }

    @Test
    void setFailureIsLoggedWithTheManualRecoveryAndRethrown() throws Exception
    {
        // A regular file where the parent directory should be makes the directory creation fail.
        Files.writeString(this.permanentDirectory.toPath().resolve("ai-llm"), "not a directory");

        IOException exception = assertThrows(IOException.class, () -> this.marker.set());

        assertFalse(this.marker.isSet());
        assertEquals(1, this.logCapture.size());
        assertTrue(this.logCapture.getMessage(0).contains("reindex them manually"));
        assertSame(exception, ((ThrowableProxy) this.logCapture.getLogEvent(0).getThrowableProxy()).getThrowable());
    }
}
