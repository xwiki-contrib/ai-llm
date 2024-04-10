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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.configuration.ConfigurationSource;

/**
 * Configuration for the JWT token authenticator.
 *
 * @version $Id$
 * @since 0.3
 */
@Component(roles = JWTTokenAuthenticatorConfiguration.class)
@Singleton
public class JWTTokenAuthenticatorConfiguration
{
    private static final String PROP_AUTHENTICATOR = "llm.authentication.jwt.authenticator";

    private static final String INITIAL_GROUPS_CONFIGURATION_PROPERTY = "xwiki.users.initialGroups";

    @Inject
    private ConfigurationSource configurationSource;

    @Inject
    @Named("xwikicfg")
    private ConfigurationSource xwikicfg;

    private Set<String> mandatoryXWikiGroups;

    /**
     * @return the fallback authenticator to use if there is no token in the request
     */
    public String getAuthenticator()
    {
        return this.configurationSource.getProperty(PROP_AUTHENTICATOR, null);
    }

    private boolean isAllGroupImplicit()
    {
        return "1".equals(this.xwikicfg.getProperty("xwiki.authentication.group.allgroupimplicit"));
    }

    /**
     * @return the initial XWiki groups to add to the user to
     */
    public Set<String> getInitialXWikiGroups()
    {
        if (this.mandatoryXWikiGroups == null) {
            String groupsPreference = isAllGroupImplicit() ? this.xwikicfg.getProperty(
                INITIAL_GROUPS_CONFIGURATION_PROPERTY)
                : this.xwikicfg.getProperty(INITIAL_GROUPS_CONFIGURATION_PROPERTY, "XWiki.XWikiAllGroup");

            if (groupsPreference != null) {
                String[] groups = groupsPreference.split(",");

                this.mandatoryXWikiGroups = new HashSet<>(Arrays.asList(groups));
            } else {
                this.mandatoryXWikiGroups = Collections.emptySet();
            }
        }

        return this.mandatoryXWikiGroups;
    }

}
