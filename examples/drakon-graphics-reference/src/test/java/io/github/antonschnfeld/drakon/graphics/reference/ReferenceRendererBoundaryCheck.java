package io.github.antonschnfeld.drakon.graphics.reference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Source-level guard for the example's portable-renderer boundary. */
public final class ReferenceRendererBoundaryCheck {
    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "import io.github.antonschnfeld.drakon.graphics.opengl.",
            "import io.github.antonschnfeld.drakon.graphics.vulkan.",
            "import org.lwjgl.");
    private static final Pattern NATIVE_CALL = Pattern.compile("\\b(?:gl|vk)[A-Z][A-Za-z0-9_]*\\s*\\(");

    private ReferenceRendererBoundaryCheck() {}

    /** Runs the boundary check against the source path supplied by Maven. */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("expected ReferenceRenderer source path");
        Path sourcePath = Path.of(args[0]);
        String source = Files.readString(sourcePath);
        for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
            if (source.contains(forbidden)) {
                throw new AssertionError("portable renderer contains forbidden import: " + forbidden);
            }
        }
        if (NATIVE_CALL.matcher(source).find()) {
            throw new AssertionError("portable renderer contains a native graphics call");
        }
        System.out.println("ReferenceRenderer boundary check passed.");
    }
}
