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
package org.xwiki.contrib.llm.mcp.internal;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.container.Container;
import org.xwiki.container.servlet.ServletRequest;
import org.xwiki.container.servlet.ServletResponse;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultMCPResource}.
 *
 * @version $Id$
 */
@OldcoreTest
class DefaultMCPResourceTest
{
    private static final String WIKI_NAME = "testwiki";

    private static final String INITIAL_WIKI = "xwiki";

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultMCPResource mcpResource;

    @MockComponent
    private XWikiMCPServerManager mcpServerManager;

    @MockComponent
    private Container container;

    @Mock
    private HttpServletRequest mockRequest;

    @Mock
    private HttpServletResponse mockResponse;

    @BeforeEach
    void setUp()
    {
        this.oldcore.getXWikiContext().setWikiId(INITIAL_WIKI);
        ServletRequest servletRequest = mock();
        when(servletRequest.getRequest()).thenReturn(this.mockRequest);
        ServletResponse servletResponse = mock();
        when(servletResponse.getResponse()).thenReturn(this.mockResponse);

        when(this.container.getRequest()).thenReturn(servletRequest);
        when(this.container.getResponse()).thenReturn(servletResponse);
    }

    @Test
    void delegateToMcpSetsWikiAndCallsService() throws Exception
    {
        doAnswer(invocation -> {
            // Assert the wiki was set on the XWiki context while service() is running
            assertEquals(WIKI_NAME, this.oldcore.getXWikiContext().getWikiId());
            return null;
        }).when(this.mcpServerManager).handleRequest(any(), any());

        this.mcpResource.delegateToMcp(WIKI_NAME);

        verify(this.mcpServerManager).handleRequest(this.mockRequest, this.mockResponse);
    }

    @Test
    void delegateToMcpRestoresWikiAfterSuccess() throws Exception
    {
        this.mcpResource.delegateToMcp(WIKI_NAME);

        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void delegateToMcpRestoresWikiOnServletException() throws Exception
    {
        doThrow(new ServletException("transport error"))
            .when(this.mcpServerManager).handleRequest(any(), any());

        assertThrows(XWikiRestException.class,
            () -> this.mcpResource.delegateToMcp(WIKI_NAME));

        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void delegateToMcpRestoresWikiOnIOException() throws Exception
    {
        doThrow(new IOException("network error"))
            .when(this.mcpServerManager).handleRequest(any(), any());

        assertThrows(XWikiRestException.class,
            () -> this.mcpResource.delegateToMcp(WIKI_NAME));

        assertEquals(INITIAL_WIKI, this.oldcore.getXWikiContext().getWikiId());
    }
}
