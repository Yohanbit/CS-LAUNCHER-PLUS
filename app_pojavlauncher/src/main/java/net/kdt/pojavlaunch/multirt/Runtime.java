package net.kdt.pojavlaunch.multirt;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Runtime {
    private static final Pattern VERSION = Pattern.compile(
            "^(?:1\\.)?([1-9][0-9]*)(?:[._][0-9]+)*(?:[-+][0-9A-Za-z._+\\-]+)?$");
    public final String name;
    public final String versionString;
    public final String arch;
    public final int javaVersion;
    public Runtime(String name) {
        this.name = name;
        this.versionString = null;
        this.arch = null;
        this.javaVersion = 0;
    }
    Runtime(String name, String versionString, String arch, int javaVersion) {
        this.name = name;
        this.versionString = versionString;
        this.arch = arch;
        this.javaVersion = javaVersion;
    }

    /** Pure release-file parser; malformed metadata is an invalid runtime, never a launch crash. */
    public static Runtime fromRelease(String name, String content) {
        String version = releaseValue(content, "JAVA_VERSION");
        String arch = releaseValue(content, "OS_ARCH");
        int major = majorVersion(version);
        if (major == 0 || arch == null || arch.trim().isEmpty()) return new Runtime(name);
        return new Runtime(name, version, arch, major);
    }

    public static int majorVersion(String version) {
        if (version == null) return 0;
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.matches()) return 0;
        try {
            int major = Integer.parseInt(matcher.group(1));
            return major >= 2 ? major : 0;
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private static String releaseValue(String content, String key) {
        if (content == null) return null;
        String prefix = key + "=\"";
        for (String line : content.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith(prefix) && line.endsWith("\"") && line.length() > prefix.length())
                return line.substring(prefix.length(), line.length() - 1);
        }
        return null;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Runtime runtime = (Runtime) o;
        return Objects.equals(name, runtime.name);
    }
    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}