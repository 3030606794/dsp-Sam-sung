package me.timschneeberger.rootlessjamesdsp.testutil;

import java.io.File;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Adapts the host's canonical separator to the POSIX paths used by Android FileProvider.
 *
 * <p>AndroidX checks a canonical child against {@code root + "/"}; Windows canonical paths
 * contain backslashes. Only that private comparison is shadowed. Provider manifest/XML parsing,
 * canonicalization, longest-root matching, URI encoding, actual file opening and grants remain real.
 */
@Implements(className = "androidx.core.content.FileProvider$SimplePathStrategy", isInAndroidSdk = false)
public class ShadowPosixFileProviderPathStrategy {
    @Implementation
    protected boolean belongsToRoot(String filePath, String rootPath) {
        String child = withoutTrailingSlash(filePath.replace(File.separatorChar, '/'));
        String root = withoutTrailingSlash(rootPath.replace(File.separatorChar, '/'));
        return child.equals(root) || child.startsWith(root + "/");
    }

    private static String withoutTrailingSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
