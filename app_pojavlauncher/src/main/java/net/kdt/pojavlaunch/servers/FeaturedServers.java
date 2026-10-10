package net.kdt.pojavlaunch.servers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Compatibility API for the former featured-server cards.
 * The launcher ships no server entries and reserves no addresses. Existing user lists
 * and in-game multiplayer files are intentionally not cleaned by address: an old default
 * cannot reliably be distinguished from a server the player chose to save.
 */
public final class FeaturedServers {

    /** One built-in server: identity, artwork and the optional community button. */
    public static final class Item {
        public final String address;
        public final String host;
        public final int port;
        public final String name;
        public final String tagline;
        /** null when this server has no community link — the button is then not shown at all. */
        public final String discordUrl;
        public final int logoRes;
        public final int bannerRes;

        Item(String host, int port, String name, String tagline, String discordUrl,
             int logoRes, int bannerRes) {
            this.host = host;
            this.port = port;
            this.address = host + ':' + port;
            this.name = name;
            this.tagline = tagline;
            this.discordUrl = discordUrl;
            this.logoRes = logoRes;
            this.bannerRes = bannerRes;
        }

        /** A fresh, unpinged copy — what the hub shows before the first reply. */
        public ServerEntry newEntry() {
            ServerEntry e = new ServerEntry(address);
            e.name = name;
            e.pinned = true;
            e.motd = tagline;
            return e;
        }
    }

    // No launcher-supplied servers. Keep the lookup API for existing hub callers;
    // user-created entries (including former featured addresses) remain ordinary servers.
    private static final List<Item> ALL = Collections.emptyList();

    private FeaturedServers() {}

    public static List<Item> all() { return ALL; }

    /** Built-in entries in display order, with their last known ping / MOTD / icon cache. */
    public static List<ServerEntry> entries(android.content.Context ctx) {
        List<ServerEntry> out = new ArrayList<>();
        for (Item it : ALL) out.add(ServerStore.loadBuiltIn(ctx, it));
        return out;
    }

    /** The built-in a given address belongs to, or {@code null}. */
    public static Item find(String address) {
        if (address == null) return null;
        String a = normalize(address);
        for (Item it : ALL) {
            String full = normalize(it.address);
            if (a.equals(full) || a.equals(normalize(it.host)) || a.equals(normalize(it.host + ":25565")))
                return it;
        }
        return null;
    }

    public static boolean is(String address) { return find(address) != null; }

    /** Lower-case, no scheme, no trailing slash, no default port — so any spelling matches. */
    static String normalize(String address) {
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.startsWith("http://")) a = a.substring(7);
        if (a.startsWith("https://")) a = a.substring(8);
        if (a.startsWith("minecraft://")) a = a.substring("minecraft://".length());
        int slash = a.indexOf('/');
        if (slash >= 0) a = a.substring(0, slash);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - ":25565".length());
        return a;
    }
}
