package org.openkaryon.karyoforge;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class PlatformIntegrationTest extends BasePlatformTestCase {
    public void testPluginLoadsWithItsPublicIdentityOnThePinnedPlatform() {
        var plugin = PluginManagerCore.getPlugin(PluginId.getId("org.openkaryon.karyoforge"));
        assertNotNull("KaryoForge must load in the isolated test IDE", plugin);
        assertTrue(plugin.isEnabled());
        assertEquals("KaryoForge", plugin.getName());
        assertEquals("252.28539.97", ApplicationInfo.getInstance().getBuild().asStringWithoutProductCode());
    }
}
