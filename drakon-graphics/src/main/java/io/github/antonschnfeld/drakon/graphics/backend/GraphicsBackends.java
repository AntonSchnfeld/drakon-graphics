package io.github.antonschnfeld.drakon.graphics.backend;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Discovers installed {@link GraphicsBackend} service providers.
 *
 * <p>Discovery occurs once when this class is initialized. Backend modules are
 * therefore treated as process configuration rather than as dynamically loaded
 * plugins. This keeps the core module free of compile-time dependencies on
 * concrete OpenGL, Vulkan, or future backend implementations.</p>
 */
public final class GraphicsBackends {
    /*
     * Backend modules are part of process configuration. Discovering once
     * avoids rescanning ServiceLoader for every lookup while preserving a
     * clean provider boundary.
     */
    private static final List<GraphicsBackend> DISCOVERED = loadBackends();

    private GraphicsBackends() {}

    private static List<GraphicsBackend> loadBackends() {
        List<GraphicsBackend> backends = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        for (GraphicsBackend backend : ServiceLoader.load(GraphicsBackend.class)) {
            String id = Objects.requireNonNull(backend.id(), "backend.id()").trim();
            if (id.isEmpty()) {
                throw new IllegalStateException("graphics backend id must not be blank: " + backend.getClass().getName());
            }
            String normalized = id.toLowerCase(java.util.Locale.ROOT);
            if (!ids.add(normalized)) {
                throw new IllegalStateException("duplicate graphics backend id '" + id + "'");
            }
            backends.add(backend);
        }

        return List.copyOf(backends);
    }

    /**
     * Returns every installed backend provider, including providers that report
     * themselves as unsupported on the current machine.
     *
     * @return an immutable discovery-order list of installed backends
     */
    public static List<GraphicsBackend> discover() {
        return DISCOVERED;
    }

    /**
     * Finds a supported backend by identifier.
     *
     * @param id backend identifier, compared case-insensitively
     * @return the matching supported backend, or an empty optional when no
     *         matching supported provider is installed
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public static Optional<GraphicsBackend> find(String id) {
        Objects.requireNonNull(id, "id");
        return DISCOVERED.stream()
                .filter(GraphicsBackend::isSupported)
                .filter(backend -> backend.id().equalsIgnoreCase(id))
                .findFirst();
    }

    /**
     * Returns a supported backend by identifier or fails immediately.
     *
     * @param id backend identifier, compared case-insensitively
     * @return the matching supported backend
     * @throws NullPointerException if {@code id} is {@code null}
     * @throws IllegalStateException if no supported provider with that id is
     *         installed
     */
    public static GraphicsBackend require(String id) {
        return find(id).orElseThrow(() -> new IllegalStateException(
                "No supported graphics backend registered with id '" + id + "'. Available: " +
                        DISCOVERED.stream().filter(GraphicsBackend::isSupported).map(GraphicsBackend::id).toList()));
    }

    /**
     * Selects the first available backend from a caller-defined preference list.
     *
     * <p>This is the convenience path for applications that prefer, for
     * example, Vulkan but deliberately fall back to OpenGL.</p>
     *
     * @param preferredIds backend ids in descending preference order
     * @return the first supported matching backend
     * @throws NullPointerException if {@code preferredIds} or any contained id
     *         is {@code null}
     * @throws IllegalArgumentException if no preferences are supplied
     * @throws IllegalStateException if none of the preferred backends is
     *         currently available
     */
    public static GraphicsBackend firstAvailable(String... preferredIds) {
        Objects.requireNonNull(preferredIds, "preferredIds");
        if (preferredIds.length == 0) {
            throw new IllegalArgumentException("at least one preferred backend id is required");
        }
        for (String id : preferredIds) {
            Optional<GraphicsBackend> backend = find(Objects.requireNonNull(id, "preferredIds contains null"));
            if (backend.isPresent()) {
                return backend.get();
            }
        }
        throw new IllegalStateException("None of the preferred graphics backends are available: " + List.of(preferredIds));
    }
}
