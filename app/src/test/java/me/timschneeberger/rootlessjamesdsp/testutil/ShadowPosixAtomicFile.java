package me.timschneeberger.rootlessjamesdsp.testutil;

import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Models Android's POSIX rename semantics for AtomicFile tests on a Windows host.
 *
 * <p>The SDK 35 implementation calls File.renameTo, which replaces an existing file on Android,
 * but not on the host Windows JDK. Only that private platform operation is shadowed: actual
 * AtomicFile startWrite, fsync, finishWrite, failWrite and recovery still execute unmodified.
 * Production persistence and all partial-write/rollback assertions remain unchanged.
 */
@Implements(value = AtomicFile.class, minSdk = 30)
public class ShadowPosixAtomicFile {
    @Implementation
    protected static void rename(File source, File target) {
        try {
            // Matches the platform's handling of an accidentally created empty directory.
            if (target.isDirectory()) {
                Files.delete(target.toPath());
            }
            Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            // Android logs failures and leaves the pending file for failWrite/recovery.
            Log.e("AtomicFile", "Failed to rename " + source + " to " + target, failure);
        }
    }
}
