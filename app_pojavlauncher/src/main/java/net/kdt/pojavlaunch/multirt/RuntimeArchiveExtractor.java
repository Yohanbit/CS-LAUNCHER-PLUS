package net.kdt.pojavlaunch.multirt;

import java.io.*;
import java.util.*;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

/** Extraction into a private runtime root. Every destination and link target is revalidated
 * against canonical containment, including entries that traverse earlier archive symlinks.
 * Not a defence against a concurrent adversary rewriting private app directories. */
public final class RuntimeArchiveExtractor {
    public interface Links {
        void symlink(String target, String linkPath) throws IOException;
        void hardlink(String target, String linkPath) throws IOException;
        /** Actual link target, or null for non-link/missing paths (including dangling links). */
        String readlink(String path) throws IOException;
    }
    public interface Progress { void entry(String name); }
    private RuntimeArchiveExtractor() {}

    public static File runtimeDirectory(File root, String name) throws IOException {
        if (name == null || name.trim().isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || new File(name).isAbsolute())
            throw new IOException("Invalid runtime name: " + name);
        return contained(root, new File(root, name), false);
    }

    private static File contained(File root, File path, boolean allowRoot) throws IOException {
        String base = root.getCanonicalPath(), candidate = path.getCanonicalPath();
        if (!(candidate.startsWith(base + File.separator) || (allowRoot && candidate.equals(base))))
            throw new IOException("Runtime archive path escapes extraction root: " + path);
        return path; // preserve the link pathname rather than replacing it with its target
    }

    public static File checkedPath(File root, String name) throws IOException {
        return entry(root, name, false);
    }

    private static void inspectLinks(File root, File path, Links links) throws IOException {
        File stop = root.getAbsoluteFile();
        for (File part = path.getAbsoluteFile(); part != null && !part.equals(stop); part = part.getParentFile()) {
            String target = links.readlink(part.getPath());
            if (target != null) {
                File resolved = new File(target);
                if (!resolved.isAbsolute()) resolved = new File(part.getParentFile(), target);
                contained(root, resolved, true); // includes dangling links which canonical paths can miss
            }
        }
    }

    private static void relative(String name) throws IOException {
        if (name == null || name.isEmpty() || new File(name).isAbsolute()
                || name.indexOf('\\') >= 0 || name.matches("^[A-Za-z]:.*"))
            throw new IOException("Invalid absolute/empty archive path: " + name);
    }

    private static File entry(File root, String name, boolean directory) throws IOException {
        relative(name);
        return contained(root, new File(root, name), directory);
    }

    private static void parent(File path) throws IOException {
        File parent = path.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
    }

    private static void replaceLink(File path) throws IOException {
        // delete() also handles a dangling symlink, for which exists() is false.
        if (!path.delete() && path.exists()) throw new IOException("Cannot replace archive link " + path);
    }

    public static void extract(InputStream input, File root, Links links, Progress progress) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create runtime root " + root);
        Map<String, String> hardlinks = new LinkedHashMap<>();
        byte[] buffer = new byte[131072];
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new XZCompressorInputStream(input))) {
            TarArchiveEntry item;
            while ((item = tar.getNextTarEntry()) != null) {
                String name = item.getName();
                if (progress != null) progress.entry(name);
                if (item.isBlockDevice() || item.isCharacterDevice() || item.isFIFO())
                    throw new IOException("Unsupported special runtime archive entry: " + name);
                File path = entry(root, name, item.isDirectory());
                hardlinks.remove(name);
                inspectLinks(root, path, links);
                parent(path);
                // Recheck after directory creation and before filesystem side effects.
                contained(root, path, item.isDirectory());
                if (item.isSymbolicLink()) {
                    String target = item.getLinkName();
                    relative(target);
                    contained(root, new File(path.getParentFile(), target), true);
                    inspectLinks(root, new File(path.getParentFile(), target), links);
                    replaceLink(path);
                    links.symlink(target, path.getAbsolutePath()); // Os contract: target, linkpath
                } else if (item.isLink()) {
                    entry(root, item.getLinkName(), false); // tar hardlink names are root-relative
                    hardlinks.put(name, item.getLinkName());
                } else if (item.isDirectory()) {
                    if (!path.isDirectory() && !path.mkdirs()) throw new IOException("Cannot create " + path);
                } else if (item.isFile()) {
                    // Never trust a same-length preexisting file in an imported archive.
                    try (OutputStream out = new FileOutputStream(path)) {
                        int count;
                        while ((count = tar.read(buffer)) != -1) out.write(buffer, 0, count);
                    }
                } else throw new IOException("Unsupported runtime archive entry type: " + name);
            }
        }
        // Support forward hardlinks and chains; reject missing targets/cycles rather than fake files.
        while (!hardlinks.isEmpty()) {
            boolean advanced = false;
            Iterator<Map.Entry<String, String>> iterator = hardlinks.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, String> link = iterator.next();
                File target = entry(root, link.getValue(), false);
                inspectLinks(root, target, links);
                if (!target.isFile()) continue;
                File path = entry(root, link.getKey(), false);
                inspectLinks(root, path, links);
                parent(path);
                contained(root, path, false);
                replaceLink(path);
                links.hardlink(target.getAbsolutePath(), path.getAbsolutePath());
                iterator.remove(); advanced = true;
            }
            if (!advanced) throw new IOException("Missing/cyclic runtime hardlink targets: " + hardlinks);
        }
    }
}
