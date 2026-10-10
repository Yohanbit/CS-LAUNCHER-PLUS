package net.kdt.pojavlaunch;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.NonNull;

/**
 * Support and project links used by the About page.
 * {@link #open} lets Android choose a native app or browser.
 */
public final class CsLinks {

    /** Same invite as {@code AboutFragment}. */
    public static final String DISCORD = "https://discord.gg/bpgYQMA59D";
    public static final String WEBSITE = "https://github.com/Yohanbit/CS-LAUNCHER-PLUS/releases";
    public static final String GITHUB = "https://github.com/Yohanbit/CS-LAUNCHER-PLUS";

    private CsLinks() {}

    public static void open(@NonNull Context ctx, @NonNull String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, "No app can open this link", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "Could not open link", Toast.LENGTH_SHORT).show();
        }
    }
}
