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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.Chunk;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.localization.ContextualLocalizationManager;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.rendering.syntax.SyntaxType;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.PropertyClass;

/**
 * A {@link Document} that exposes an arbitrary {@link XWikiDocument} for indexing in the LLM application.
 *
 * @version $Id$
 * @since 0.4
 */
@Component(roles = XWikiDocumentDocument.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class XWikiDocumentDocument implements Document
{
    private static final String CHANGING_DOCUMENTS_MESSAGE =
        "This XWiki document store doesn't support changing documents.";

    private static final String DELIMITER = "\n\n";

    private String collection;
    private XWikiDocument xWikiDocument;

    @Inject
    private Provider<XWikiContext> xWikiContextProvider;

    @Inject
    @Named("withparameters")
    private EntityReferenceSerializer<String> parametersEntityReferenceSerializer;

    @Inject
    @Named("compactwiki")
    private EntityReferenceSerializer<String> compactEntityReferenceSerializer;

    @Inject
    private Logger logger;

    @Inject
    private ContextualLocalizationManager localizationManager;

    private final Tika tika = new Tika();

    /**
     * Initialize this document.
     *
     * @param collection the collection to which the document belongs
     * @param document the document that shall be wrapped
     */
    public void initialize(String collection, XWikiDocument document)
    {
        this.collection = collection;
        this.xWikiDocument = document;
    }

    @Override
    public XWikiDocument getXWikiDocument() throws IndexException
    {
        throw new UnsupportedOperationException("The internal document is protected");
    }

    @Override
    public String getID()
    {
        return this.parametersEntityReferenceSerializer.serialize(this.xWikiDocument.getDocumentReferenceWithLocale());
    }

    @Override
    public String getTitle()
    {
        return this.xWikiDocument.getRenderedTitle(Syntax.PLAIN_1_0, this.xWikiContextProvider.get());
    }

    @Override
    public String getLanguage()
    {
        Locale locale = this.xWikiDocument.getLocale();
        if (locale != null && !locale.equals(Locale.ROOT)) {
            return this.xWikiDocument.getLocale().toString();
        } else {
            return this.xWikiDocument.getDefaultLocale().toString();
        }
    }

    @Override
    public String getURL()
    {
        return this.xWikiDocument.getExternalURL("view", this.xWikiContextProvider.get());
    }

    @Override
    public String getCollection()
    {
        return this.collection;
    }

    @Override
    public String getMimetype()
    {
        return "text/" + this.xWikiDocument.getSyntax().getType().getId();
    }

    @Override
    public String getContent()
    {
        // FIXME: refactor this to return an input stream that iteratively returns all of these values instead of a
        //  string.
        XWikiContext context = this.xWikiContextProvider.get();
        return Stream.of(
            formatHeading(1, this.xWikiDocument.getRenderedTitle(this.xWikiDocument.getSyntax(), context))
                + this.xWikiDocument.getContent(),
                getFormattedXObjects(),
                getFormattedAttachments()
            )
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.joining(DELIMITER));
    }

    private String getFormattedAttachments()
    {
        XWikiContext context = this.xWikiContextProvider.get();
        return this.xWikiDocument.getAttachmentList().stream().flatMap(attachment -> {
            try {
                String content = this.tika.parseToString(attachment.getContentInputStream(context));
                if (StringUtils.isNotBlank(content)) {
                    return Stream.of(formatHeading(2, attachment.getFilename()) + content);
                }
            } catch (Exception e) {
                this.logger.warn("Failed to parse attachment content: {}", ExceptionUtils.getRootCauseMessage(e));
            }
            return Stream.empty();
        }).collect(Collectors.joining(DELIMITER));
    }

    private String getFormattedXObjects()
    {
        XWikiContext context = this.xWikiContextProvider.get();
        return this.xWikiDocument.getXObjects().values().stream().flatMap(List::stream)
            .filter(Objects::nonNull)
            .map(xObject -> {
                BaseClass xClass = xObject.getXClass(context);
                String objectProperties;
                if (xClass != null) {
                    Collection<?> fieldList = xClass.getFieldList();
                    objectProperties = fieldList.stream()
                        .filter(PropertyClass.class::isInstance)
                        .map(PropertyClass.class::cast)
                        .filter(xProperty -> xObject.safeget(xProperty.getName()) != null)
                        .flatMap(xProperty -> {
                            String value = xObject.getStringValue(xProperty.getName());
                            if (StringUtils.isNotBlank(value)) {
                                return Stream.of(formatHeading(2, xProperty.getTranslatedPrettyName(context))
                                    + value);
                            } else {
                                return Stream.empty();
                            }
                        })
                        .collect(Collectors.joining(DELIMITER));
                } else {
                    objectProperties = Arrays.stream(xObject.getPropertyNames())
                        .filter(fieldName -> StringUtils.isNotBlank(xObject.getStringValue(fieldName)))
                        .map(fieldName -> formatHeading(2, fieldName) + xObject.getStringValue(fieldName))
                        .collect(Collectors.joining(DELIMITER));
                }
                String heading = this.localizationManager.getTranslationPlain("aiLLM.index.objectHeading",
                    this.compactEntityReferenceSerializer.serialize(xObject.getXClassReference(),
                        xObject.getDocumentReference()));
                return formatHeading(1, heading) + objectProperties;
            })
            .collect(Collectors.joining(DELIMITER));
    }

    private String formatHeading(int level, String content)
    {
        // This currently only supports XWiki and Markdown.
        if (this.xWikiDocument.getSyntax().getType().equals(SyntaxType.XWIKI)) {
            String delimiter = StringUtils.repeat('=', level);
            return "%s %s %s\n\n".formatted(delimiter, content, delimiter);
        } else {
            String start = StringUtils.repeat('#', level);
            return start + " " + content + DELIMITER;
        }
    }

    @Override
    public void setID(String id) throws IndexException
    {
        throw new UnsupportedOperationException(CHANGING_DOCUMENTS_MESSAGE);
    }

    @Override
    public void setTitle(String title) throws IndexException
    {
        throw new UnsupportedOperationException(CHANGING_DOCUMENTS_MESSAGE);
    }

    @Override
    public void setLanguage(String lang) throws IndexException
    {
        throw new UnsupportedOperationException(CHANGING_DOCUMENTS_MESSAGE);
    }

    @Override
    public void setURL(String url) throws IndexException
    {
        throw new UnsupportedOperationException(CHANGING_DOCUMENTS_MESSAGE);
    }

    @Override
    public void setCollection(String collection) throws IndexException
    {
        throw new UnsupportedOperationException(CHANGING_DOCUMENTS_MESSAGE);
    }

    @Override
    public void setMimetype(String mimetype) throws IndexException
    {
        throw new UnsupportedOperationException(CHANGING_DOCUMENTS_MESSAGE);
    }

    @Override
    public void setContent(String content) throws IndexException
    {
        throw new UnsupportedOperationException(CHANGING_DOCUMENTS_MESSAGE);
    }

    @Override
    public List<Chunk> chunkDocument()
    {
        throw new UnsupportedOperationException("Use ChunkingUtils to chunk documents");
    }
}
