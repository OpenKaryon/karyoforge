package org.openkaryon.karyoforge.branding;

import com.intellij.ide.gdpr.ConsentOptions;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.util.SVGLoader;

import javax.imageio.ImageIO;
import java.net.URL;

/** Fresh-JVM resource/classpath acceptance; does not open an IDE window. */
public final class BrandingProbe {
    private BrandingProbe() {}

    public static void main(String[] args) throws Exception {
        var names = ApplicationNamesInfo.getInstance();
        require("KaryoForge".equals(names.getFullProductName()), "Product name: " + names.getFullProductName());
        var info = ApplicationInfoImpl.getShadowInstance();
        require("OpenKaryon".equals(info.getCompanyName()), "Company: " + info.getCompanyName());
        require(("IC-" + args[0]).equals(info.getBuild().asString()), "Platform build: " + info.getBuild());
        require("/karyoforge.svg".equals(info.getApplicationSvgIconUrl()), "Application icon");
        require("/karyoforge_16.svg".equals(info.getSmallApplicationSvgIconUrl()), "Small icon");
        for (float scale : new float[]{1f, 2f}) {
            var icon = SVGLoader.load(resource("/karyoforge_16.svg"), scale);
            require(icon.getWidth(null) == (int) (16 * scale) && icon.getHeight(null) == (int) (16 * scale),
                    "Small icon must render at its logical size, including high DPI");
        }
        for (String name : new String[]{"/karyoforge_logo.png", "/karyoforge_logo@2x.png"}) {
            var splash = ImageIO.read(resource(name));
            int scale = name.contains("@2x") ? 2 : 1;
            require(splash.getWidth() == 760 * scale && splash.getHeight() == 440 * scale, "Splash size: " + name);
        }
        resource("/consents-OpenKaryon.json");
        var confirmed = ConsentOptions.getConfirmedConsentsFileForTests();
        require(confirmed.startsWith(java.nio.file.Path.of(System.getenv("APPDATA"))), "Isolated consent storage");
        require(!java.nio.file.Files.exists(confirmed), "Probe starts without accepted consents");
        var consents = ConsentOptions.getInstance();
        var consent = consents.getDefaultUsageStatsConsent();
        require(consent != null && !consent.isAccepted() && consent.isDeleted(), "Usage statistics must not be accepted by default");
        require(consents.isSendingUsageStatsAllowed() != ConsentOptions.Permission.YES, "No implicit usage consent");
        require(consents.getAiDataCollectionPermission() != ConsentOptions.Permission.YES, "No implicit AI consent");
        require(consents.isEAPFeedbackAllowed() != ConsentOptions.Permission.YES, "No implicit survey consent");
        require(!java.nio.file.Files.exists(confirmed), "Consent reads must not persist acceptance");
        System.out.println("KaryoForge branding and consent checks passed: native product metadata, build, SVG at 16/32 px and both splash images.");
    }

    private static URL resource(String name) {
        var url = BrandingProbe.class.getResource(name);
        require(url != null && url.toExternalForm().contains("karyoforge-branding"), "Resource overlay precedence: " + name);
        return url;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
