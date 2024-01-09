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
package org.xwiki.contrib.llm;

import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Converts index entities to XWiki entities and vice versa.
 *
 * @version $Id$
 */
public interface Converter
{
    /**
     * Sets the properteis of a collection based on the properties of the specified XWiki document's object.
     * 
     * @param xwikiDocument
     * @return The updated collection.
     */
    Collection toCollection(XWikiDocument xwikiDocument);

    /**
     * Sets the properties of a XWiki document's object based on the properties of the collection.
     * 
     * @param xwikiDocument The XWiki document to update.
     * @return The updated XWiki document.
     */
    XWikiDocument toXWikiDocument(XWikiDocument xwikiDocument);

}
