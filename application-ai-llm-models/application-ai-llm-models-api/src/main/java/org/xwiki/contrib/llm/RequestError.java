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

import org.xwiki.stability.Unstable;

/**
 * An error that occurred during a request.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
public class RequestError extends Exception
{
    private static final String SEPARATOR = ": ";

    private final int code;

    private final String message;

    /**
     * Creates a new request error.
     *
     * @param code the error code
     * @param message the message
     */
    public RequestError(int code, String message)
    {
        super(code + SEPARATOR + message);

        this.code = code;
        this.message = message;
    }

    /**
     * Creates a new request error.
     *
     * @param code the error code
     * @param message the message
     * @param cause the exception that caused the error
     */
    public RequestError(int code, String message, Exception cause)
    {
        super(code + SEPARATOR + message, cause);

        this.code = code;
        this.message = message;
    }

    /**
     * @return the error code
     */
    public int getCode()
    {
        return code;
    }

    /**
     * @return the message without the error code
     */
    public String getPlainMessage()
    {
        return message;
    }
}
