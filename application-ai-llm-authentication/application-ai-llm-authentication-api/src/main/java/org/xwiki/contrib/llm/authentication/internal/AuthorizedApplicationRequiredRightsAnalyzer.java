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
package org.xwiki.contrib.llm.authentication.internal;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.reflect.MethodUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.component.util.DefaultParameterizedType;
import org.xwiki.model.EntityType;
import org.xwiki.platform.security.requiredrights.RequiredRight;
import org.xwiki.platform.security.requiredrights.RequiredRightAnalysisResult;
import org.xwiki.platform.security.requiredrights.RequiredRightAnalyzer;
import org.xwiki.platform.security.requiredrights.RequiredRightsException;
import org.xwiki.rendering.block.Block;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.objects.BaseObject;

/**
 * Required rights analyzer for the authorized applications class.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Singleton
@Named(AuthorizedApplicationManager.AUTHORIZED_APPLICATION_CLASS)
public class AuthorizedApplicationRequiredRightsAnalyzer implements RequiredRightAnalyzer<BaseObject>, Initializable
{
    private static final String GET_METHOD = "get";

    @Inject
    private ComponentManager componentManager;

    private Object xObjectBlockSupplierProvider;

    private Object translationBlockSupplierProvider;

    @Override
    public List<RequiredRightAnalysisResult> analyze(BaseObject object) throws RequiredRightsException
    {
        return List.of(new RequiredRightAnalysisResult(object.getReference(),
            getTranslationBlockSupplier("aiLLM.authentication.api.applicationRequiredAdminRight"),
            getBlockSupplier(object),
            List.of(new RequiredRight(Right.ADMIN, EntityType.WIKI, false))));
    }

    @Override
    public void initialize() throws InitializationException
    {
        Class<?> xObjectBlockSupplierProviderClass;
        // Check if there is a org.xwiki.platform.security.requiredrights.internal.provider.BlockSupplierProvider
        // (XWiki 16.2.0) or there is a org.xwiki.platform.security.requiredrights.display.BlockSupplierProvider
        // (XWiki >= 16.3.0RC1) component available.
        try {
            xObjectBlockSupplierProviderClass = Class.forName(
                "org.xwiki.platform.security.requiredrights.internal.provider.BlockSupplierProvider");
        } catch (ClassNotFoundException e) {
            try {
                xObjectBlockSupplierProviderClass = Class.forName(
                    "org.xwiki.platform.security.requiredrights.display.BlockSupplierProvider");
            } catch (ClassNotFoundException e1) {
                throw new InitializationException(
                    "No BlockSupplierProvider component found.");
            }
        }

        try {
            this.xObjectBlockSupplierProvider =
                this.componentManager.getInstance(new DefaultParameterizedType(null,
                    xObjectBlockSupplierProviderClass, BaseObject.class));
            this.translationBlockSupplierProvider =
                this.componentManager.getInstance(new DefaultParameterizedType(null,
                    xObjectBlockSupplierProviderClass, String.class), "translation");
        } catch (ComponentLookupException e) {
            throw new InitializationException(
                "Failed to lookup the BlockSupplierProvider component.", e);
        }
    }

    private Supplier<Block> getBlockSupplier(BaseObject object, Object... parameters)
    {
        // Use reflection to call the "get" method as the class may be moved to a different package in future versions.
        try {
            //noinspection unchecked
            return (Supplier<Block>) MethodUtils.invokeMethod(this.xObjectBlockSupplierProvider, GET_METHOD,
                new Object[]{object, parameters});
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            // This shouldn't happen.
            throw new RuntimeException(e);
        }
    }

    private Supplier<Block> getTranslationBlockSupplier(String key, Object... parameters)
    {
        // Use reflection to call the "get" method as the class may be moved to a different package in future versions.
        try {
            //noinspection unchecked
            return (Supplier<Block>) MethodUtils.invokeMethod(this.translationBlockSupplierProvider, GET_METHOD,
                new Object[]{key, parameters});
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            // This shouldn't happen.
            throw new RuntimeException(e);
        }
    }
}
