package org.telegram.messenger;

import android.net.Uri;
import android.text.TextUtils;

// Public links of the telesrv server (TELESRV_PUBLIC_BASE_URL on the server, me_url_prefix in
// help.config), such as https://darkmoon.cc/username. The client only knows t.me, telegram.me and
// telegram.dog, so links on the server's domain are rewritten to the equivalent t.me link before
// they are handled. The build-time host (TELESRV_LINK_HOST in gradle.properties) also matches, so
// links work before the first help.config and from other apps through the manifest filter.
public class TelesrvLinks {

    // The server's own pages on the link domain (e.g. /_updates), never a username.
    private static final String SITE_PATH_PREFIX = "/_";

    public static String getBuildLinkHost() {
        return normalizeHost(BuildConfig.TELESRV_LINK_HOST);
    }

    public static boolean isLinkHost(String host) {
        host = normalizeHost(host);
        if (TextUtils.isEmpty(host) || "t.me".equals(host) || "telegram.me".equals(host) || "telegram.dog".equals(host)) {
            return false;
        }
        if (host.equals(getBuildLinkHost())) {
            return true;
        }
        String prefix = MessagesController.getInstance(UserConfig.selectedAccount).linkPrefix;
        return host.equals(normalizeHost(prefix));
    }

    // The same link on t.me, or null when the uri is not a public link of the server.
    public static Uri toTelegramUri(Uri uri) {
        if (uri == null || uri.getScheme() == null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme) || !isLinkHost(uri.getHost())) {
            return null;
        }
        String path = uri.getEncodedPath();
        if (path != null && path.startsWith(SITE_PATH_PREFIX)) {
            return null;
        }
        Uri.Builder builder = new Uri.Builder()
                .scheme("https")
                .authority("t.me")
                .encodedPath(TextUtils.isEmpty(path) ? "/" : path);
        if (uri.getEncodedQuery() != null) {
            builder.encodedQuery(uri.getEncodedQuery());
        }
        if (uri.getEncodedFragment() != null) {
            builder.encodedFragment(uri.getEncodedFragment());
        }
        return builder.build();
    }

    // "https://www.Example.com/" or "example.com/path" -> "example.com".
    private static String normalizeHost(String value) {
        if (value == null) {
            return "";
        }
        String host = value.trim().toLowerCase();
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int port = host.indexOf(':');
        if (port >= 0) {
            host = host.substring(0, port);
        }
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return host;
    }
}
