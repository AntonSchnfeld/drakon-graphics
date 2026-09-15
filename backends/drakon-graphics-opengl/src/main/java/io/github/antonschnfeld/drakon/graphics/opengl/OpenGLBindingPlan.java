package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.Binding;
import io.github.antonschnfeld.drakon.graphics.resource.BindingLayout;

import java.util.IdentityHashMap;
import java.util.List;

/** Maps logical binding keys to the flat namespaces OpenGL exposes. */
final class OpenGLBindingPlan {
    private final List<BindingLayout> layouts;
    private final IdentityHashMap<Binding<?>, Integer> slots;

    OpenGLBindingPlan(List<BindingLayout> layouts, IdentityHashMap<Binding<?>, Integer> slots) {
        this.layouts = List.copyOf(layouts);
        this.slots = slots;
    }

    BindingLayout layout(int group) {
        if (group < 0 || group >= layouts.size()) {
            throw new IllegalArgumentException("binding group out of range: " + group);
        }
        return layouts.get(group);
    }

    int layoutCount() {
        return layouts.size();
    }

    List<NativeBinding> nativeBindings(int group) {
        return layout(group).bindings().stream()
                .map(binding -> new NativeBinding(binding, slot(binding)))
                .toList();
    }

    int slot(Binding<?> binding) {
        Integer slot = slots.get(binding);
        if (slot == null) {
            throw new IllegalArgumentException("binding is not part of the active state");
        }
        return slot;
    }

    record NativeBinding(Binding<?> binding, int slot) {}
}
