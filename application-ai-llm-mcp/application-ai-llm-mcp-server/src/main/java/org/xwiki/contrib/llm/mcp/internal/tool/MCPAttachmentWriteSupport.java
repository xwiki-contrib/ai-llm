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
package org.xwiki.contrib.llm.mcp.internal.tool;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.inject.Provider;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.attachment.validation.AttachmentValidationException;
import org.xwiki.attachment.validation.AttachmentValidator;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.internal.attachment.XWikiAttachmentAccessWrapper;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Shared attachment-write plumbing of {@link MCPWriteAttachmentTool}, following the per-domain split of
 * {@link MCPObjectWriteSupport}: the content staging on the tool's document clone and the wiki
 * attachment-policy validation, so the stream, wrapper and validation-exception types stay out of the
 * tool's own type surface. Not a component: a plain holder of static helpers.
 *
 * @version $Id$
 * @since 0.10
 */
final class MCPAttachmentWriteSupport
{
    /**
     * Shared tail of the attachment-write refusal messages, making explicit that a refused call
     * persisted nothing. Homed here so the tool's refusals and the validation refusals below share one
     * sentence.
     */
    static final String NOTHING_SAVED = " Nothing was saved.";

    /**
     * The fail-closed refusal returned when the wiki has no attachment validator component: skipping
     * validation would bypass the admin-set upload policy, so uploads are refused instead.
     */
    private static final String VALIDATOR_UNAVAILABLE_MESSAGE = "Attachment validation is not available on "
        + "this wiki, so attachment uploads are refused. An administrator can install the attachment "
        + "validation extension (xwiki-platform-attachment-validation-default)." + NOTHING_SAVED;

    /**
     * The fallback reason of a policy refusal whose exception carries no message at all.
     */
    private static final String GENERIC_POLICY_REASON =
        "the attachment violates this wiki's attachment policy";

    private MCPAttachmentWriteSupport()
    {
    }

    /**
     * Stages the attachment content on the tool's own clone of the target document: creates the
     * attachment or replaces the content of the existing one with that filename (the platform keeps
     * the prior content in the attachment's own history when the document is saved), stamping the
     * context user as the attachment author. The in-memory stream cannot fail in practice; the
     * declared {@link IOException} of the platform call is wrapped as a storage-level
     * {@link XWikiException} the way the platform's REST attachment resource wraps it, so the calling
     * tool's ordinary failure handling applies.
     *
     * @param editable the tool's own clone of the target document
     * @param filename the validated attachment filename
     * @param bytes the attachment content
     * @param xcontext the XWiki context, switched to the target wiki
     * @return the created or updated attachment, owned by {@code editable}
     * @throws XWikiException when the content cannot be staged
     */
    static XWikiAttachment setAttachmentContent(XWikiDocument editable, String filename, byte[] bytes,
        XWikiContext xcontext) throws XWikiException
    {
        try {
            return editable.setAttachment(filename, new ByteArrayInputStream(bytes), xcontext);
        } catch (IOException e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_MISC,
                "Failed to stage the attachment content", e);
        }
    }

    /**
     * Validates the staged attachment against the wiki's attachment policy (the admin-set maximum
     * upload size and mimetype allow/deny lists), before anything is saved. The validator is resolved
     * lazily through its provider: the default implementation ships as a flavor-installed extension,
     * and a wiki without it FAILS CLOSED - uploads are refused with a teaching message rather than
     * saved unvalidated.
     *
     * @param validatorProvider the attachment validator provider
     * @param attachment the staged attachment
     * @param xcontext the XWiki context
     * @return the agent-facing refusal carrying the policy's own message (or the fail-closed teaching
     *     refusal when no validator is available), or {@code null} when the attachment passes
     */
    static McpSchema.CallToolResult validateOrRefuse(Provider<AttachmentValidator> validatorProvider,
        XWikiAttachment attachment, XWikiContext xcontext)
    {
        AttachmentValidator validator;
        try {
            validator = validatorProvider.get();
        } catch (RuntimeException e) {
            return MCPToolSupport.errorResult(VALIDATOR_UNAVAILABLE_MESSAGE);
        }
        try {
            validator.validateAttachment(new XWikiAttachmentAccessWrapper(attachment, xcontext));
            return null;
        } catch (AttachmentValidationException e) {
            return MCPToolSupport.errorResult("Attachment refused by this wiki's attachment policy: "
                + MCPTextGuards.fragment(policyReason(e)) + NOTHING_SAVED);
        }
    }

    /**
     * Extracts the agent-facing reason of a policy refusal: the exception message plus its context
     * message when present, or a generic phrase when the exception carries neither. The policy messages
     * can echo admin-configured values (limits, mimetype lists), so the caller passes the reason through
     * the fragment guard like any other wiki-authored text.
     *
     * @param e the validation failure
     * @return the refusal reason, never blank
     */
    private static String policyReason(AttachmentValidationException e)
    {
        StringBuilder reason = new StringBuilder();
        if (StringUtils.isNotBlank(e.getMessage())) {
            reason.append(e.getMessage());
        }
        if (StringUtils.isNotBlank(e.getContextMessage())) {
            if (reason.length() > 0) {
                reason.append(' ');
            }
            reason.append(e.getContextMessage());
        }
        return reason.length() > 0 ? reason.toString() : GENERIC_POLICY_REASON;
    }
}
