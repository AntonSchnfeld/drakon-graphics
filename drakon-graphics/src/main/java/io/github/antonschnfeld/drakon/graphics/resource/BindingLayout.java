package io.github.antonschnfeld.drakon.graphics.resource;

import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable schema for one resource-binding group.
 *
 * <p>Binding numbers must be unique within a layout. Layout compatibility is
 * identity-based: a binding set is compatible with the exact layout instance
 * declared by a graphics/compute state, not merely a structurally equal copy.
 * The index of a layout in a state descriptor determines its group number when
 * calling {@link CommandEncoder#bindSet(int, BindingSet)}.</p>
 */
public final class BindingLayout {
    private final List<Binding<?>> bindings;

    private BindingLayout(List<Binding<?>> bindings) {
        this.bindings = List.copyOf(bindings);
        Set<Integer> ids = new HashSet<>();
        for (Binding<?> binding : bindings) {
            if (!ids.add(binding.binding())) {
                throw new IllegalArgumentException("duplicate resource binding " + binding.binding());
            }
        }
    }

    /**
     * Creates an immutable layout from the supplied binding declarations.
     *
     * @param bindings binding declarations; may be empty, but not null and may
     *        not contain null elements or duplicate binding numbers
     * @return immutable binding layout
     * @throws NullPointerException if the array or an element is null
     * @throws IllegalArgumentException if two bindings use the same number
     */
    public static BindingLayout of(Binding<?>... bindings) {
        return new BindingLayout(List.of(bindings));
    }

    /**
     * Returns bindings in declaration order.
     *
     * @return immutable binding list
     */
    public List<Binding<?>> bindings() {
        return bindings;
    }
}
