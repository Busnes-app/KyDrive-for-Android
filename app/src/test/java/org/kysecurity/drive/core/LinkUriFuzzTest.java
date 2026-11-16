package org.kysecurity.drive.core;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * A scanned QR code is the app's first untrusted input: it decides where the device token is
 * requested. Plain test runs replay the inputs under LinkUriFuzzTestInputs; JAZZER_FUZZ=1 fuzzes
 * (CI's ci-fuzz job). Java, not Kotlin: Jazzer's tooling, and OpenSSF Scorecard, look for it there.
 */
class LinkUriFuzzTest {
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern PIN = Pattern.compile("sha256/[A-Za-z0-9+/]{43}=");

    @FuzzTest(maxDuration = "60s")
    void parse(FuzzedDataProvider data) throws Exception {
        LinkUri link = LinkUri.Companion.parse(data.consumeRemainingAsString());
        if (link == null) return;
        URI origin = new URI(link.getOrigin());
        check("https".equals(origin.getScheme()), "not HTTPS");
        check(origin.getRawUserInfo() == null && origin.getRawQuery() == null && origin.getRawFragment() == null, "origin carries more than a host");
        check(origin.getRawPath() == null || origin.getRawPath().isEmpty(), "origin has a path");
        check(origin.getHost() != null && origin.getHost().equals(origin.getHost().toLowerCase(java.util.Locale.ROOT)), "host not lowercase");
        check(CODE.matcher(link.getCode()).matches(), "malformed code");
        check(link.getPin() == null || PIN.matcher(link.getPin()).matches(), "malformed pin");
        String again = "kydrive://link?srv=" + URLEncoder.encode(link.getOrigin(), StandardCharsets.UTF_8) + "&code=" + link.getCode();
        if (link.getPin() != null) again += "&pin=" + URLEncoder.encode(link.getPin(), StandardCharsets.UTF_8);
        check(link.equals(LinkUri.Companion.parse(again)), "not stable when re-encoded");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
