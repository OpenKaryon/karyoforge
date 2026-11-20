package org.openkaryon.karyoforge.branding;

import com.intellij.ide.ConsentOptionsProvider;
import com.intellij.ide.gdpr.ConsentOptions;
import com.intellij.internal.statistic.utils.StatisticsUploadAssistant;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.jetbrains.python.inspections.unresolvedReference.PyUnresolvedReferencesInspection;
import com.jetbrains.python.statistics.PyPackageDaemonListener;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Production-branded, isolated regression for the native consent and inspection paths. */
public final class ConsentIntegrationTest extends BasePlatformTestCase {
    private String originalVendor;

    @Override protected void setUp() throws Exception {
        super.setUp();
        // The platform test bootstrap resets this property to JetBrains.
        originalVendor = System.getProperty("idea.vendor.name");
        System.setProperty("idea.vendor.name", "OpenKaryon");
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (originalVendor == null) System.clearProperty("idea.vendor.name");
            else System.setProperty("idea.vendor.name", originalVendor);
        } finally {
            super.tearDown();
        }
    }

    public void testNativePermissionsDoNotCreateOrAcceptUserConsents() throws Exception {
        assertEquals("OpenKaryon", ApplicationInfo.getInstance().getCompanyName());
        var consents = ConsentOptions.getInstance();
        Path confirmed = ConsentOptions.getConfirmedConsentsFileForTests();
        assertTrue(confirmed.toString(), confirmed.startsWith(Path.of(System.getenv("APPDATA"))));
        assertFalse("The isolated profile must start without confirmations", Files.exists(confirmed));
        var defaultConsent = consents.getDefaultUsageStatsConsent();
        assertNotNull(defaultConsent);
        assertFalse(defaultConsent.isAccepted());
        assertTrue("Unavailable product services must not prompt for consent", defaultConsent.isDeleted());
        assertEquals(ConsentOptions.Permission.UNDEFINED, consents.isSendingUsageStatsAllowed());
        assertEquals(ConsentOptions.Permission.UNDEFINED, consents.isEAPFeedbackAllowed());
        assertEquals(ConsentOptions.Permission.UNDEFINED, consents.getAiDataCollectionPermission());
        assertTrue(consents.getConsents().getFirst().isEmpty());
        assertFalse(consents.getConsents().getSecond());
        assertFalse("Unavailable collection cannot be enabled by a native setting", consents.setSendingUsageStatsAllowed(true));
        assertFalse("Reading or rejecting permissions must not fabricate confirmation", Files.exists(confirmed));
        assertFalse(ApplicationManager.getApplication().getService(ConsentOptionsProvider.class).isSendingUsageStatsAllowed());
        assertTrue(StatisticsUploadAssistant.isDisableCollectStatistics());
        assertTrue(StatisticsUploadAssistant.isSuppressStatisticsReport());
        assertFalse(StatisticsUploadAssistant.isCollectAllowed());
        assertFalse(StatisticsUploadAssistant.isSendAllowed());
    }

    public void testPythonPackageListenerAndRealInspectionsSurviveConsentLookup() {
        // The installed build previously threw from this native listener constructor.
        boolean previous = PyPackageDaemonListener.Companion.isEnforceEnabledInTests();
        try {
            PyPackageDaemonListener.Companion.setEnforceEnabledInTests(true);
            new PyPackageDaemonListener(getProject()).daemonFinished(List.of());
        } finally {
            PyPackageDaemonListener.Companion.setEnforceEnabledInTests(previous);
        }
        myFixture.enableInspections(new PyUnresolvedReferencesInspection());
        myFixture.configureByText("consent_diagnostics.py", "def answer():\n    return missing_value\n");
        var highlights = myFixture.doHighlighting();
        assertTrue("A native Python inspection must finish and report its diagnostic", highlights.stream()
                .anyMatch(info -> info.getDescription() != null && info.getDescription().contains("missing_value")));
        assertFalse(StatisticsUploadAssistant.isAllowedByUserConsent());
    }
}
