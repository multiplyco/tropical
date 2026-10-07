package co.multiply.tropical.sse;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Loads a native library tropical ships, from
 * {@code co/multiply/tropical/native} on the classpath, or the one a system
 * property names in its place.
 */
@SuppressWarnings("restricted")
final class NativeLibrary {

    private NativeLibrary() {}

    /**
     * The library the system property {@code property} names, or tropical's
     * {@code name} for the platform, such as {@code "libzstd"}, copied out of
     * the jar to be loaded. {@code wanted} says what the property may name,
     * for the errors: {@code "a libzstd, 1.4 or later"}.
     */
    static SymbolLookup load(String name, String property, String wanted) {
        // size_t is a long: tropical's libraries are for 64-bit platforms.
        if (Linker.nativeLinker().canonicalLayouts().get("size_t").byteSize() != 8) {
            throw new UnsupportedOperationException(name + " is supported on 64-bit platforms only.");
        }
        String path = System.getProperty(property);
        if (path != null) {
            return open(Path.of(path), "the " + name + " " + property + " names, " + path, "");
        }
        String resource = resource(name, property, wanted);
        try (InputStream in = NativeLibrary.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("tropical's " + name + ", " + resource + ", isn't on the classpath.");
            }
            Path file = Files.createTempFile("tropical-" + name + "-", resource.substring(resource.lastIndexOf('.')));
            try {
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                return open(file, "tropical's " + name + ", " + resource,
                    ". Where it doesn't load, set the system property " + property + " to the path of " + wanted + ".");
            } finally {
                // Loaded, the library no longer needs its file.
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't copy tropical's " + name + " to a temporary file.", e);
        }
    }

    private static SymbolLookup open(Path file, String what, String hint) {
        try {
            return SymbolLookup.libraryLookup(file, Arena.global());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Couldn't load " + what + ": " + e.getMessage() + hint, e);
        }
    }

    private static String resource(String name, String property, String wanted) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String dir = "/co/multiply/tropical/native/";
        if (os.startsWith("mac")) {
            return dir + "darwin/" + name + ".dylib";
        }
        if (os.startsWith("linux")) {
            switch (arch) {
                case "amd64", "x86_64" -> {
                    return dir + "linux-x86_64/" + name + ".so";
                }
                case "aarch64", "arm64" -> {
                    return dir + "linux-aarch64/" + name + ".so";
                }
                default -> {
                    // None shipped.
                }
            }
        }
        throw new UnsupportedOperationException("tropical ships no " + name + " for " + os + " on " + arch
            + ". Set the system property " + property + " to the path of " + wanted + ".");
    }
}
