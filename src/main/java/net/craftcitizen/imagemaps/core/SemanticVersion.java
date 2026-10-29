package net.craftcitizen.imagemaps.core;

/**
 * A major.minor.revision version number. Missing or non-numeric parts read as 0.
 */
public final class SemanticVersion {
    private final int major;
    private final int minor;
    private final int revision;

    public SemanticVersion(int major, int minor, int revision) {
        this.major = major;
        this.minor = minor;
        this.revision = revision;
    }

    public static SemanticVersion of(String version) {
        String[] parts = version.split("\\.");
        return new SemanticVersion(part(parts, 0), part(parts, 1), part(parts, 2));
    }

    private static int part(String[] parts, int index) {
        return index < parts.length ? Utils.parseIntegerOrDefault(parts[index], 0) : 0;
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    public int getRevision() {
        return revision;
    }
}
