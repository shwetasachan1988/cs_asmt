package com.sa.agentic.shortener.service;

import com.sa.agentic.shortener.ShortenerProperties;
import com.sa.agentic.shortener.domain.LinkException;
import com.sa.agentic.shortener.domain.LinkException.Kind;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Rejects URLs that are malformed, use non-web schemes (javascript:, data:, file:), embed credentials,
 * or point at loopback/private hosts. The service only redirects (never fetches), so this is about
 * not becoming a phishing/abuse vector rather than server-side request forgery.
 */
@Component
public class UrlValidator {

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private final ShortenerProperties props;

    public UrlValidator(ShortenerProperties props) {
        this.props = props;
    }

    public String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("url must not be blank");
        }
        String url = raw.trim();
        if (url.length() > props.maxUrlLength()) {
            throw invalid("url exceeds " + props.maxUrlLength() + " characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid("url is not syntactically valid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalid("only http and https URLs are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalid("urls with embedded credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalid("url must contain a host");
        }
        if (!props.allowPrivateTargets() && isPrivateHost(host.toLowerCase(Locale.ROOT))) {
            throw invalid("private or loopback targets are not allowed");
        }
        return url;
    }

    static boolean isPrivateHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || host.equals("[::1]") || host.startsWith("[fc") || host.startsWith("[fd")) {
            return true;
        }
        var m = IPV4.matcher(host);
        if (!m.matches()) {
            return false;
        }
        int a = Integer.parseInt(m.group(1));
        int b = Integer.parseInt(m.group(2));
        return a == 10 || a == 127 || a == 0
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168)
                || (a == 169 && b == 254);
    }

    private static LinkException invalid(String msg) {
        return new LinkException(Kind.INVALID_URL, msg);
    }
}
