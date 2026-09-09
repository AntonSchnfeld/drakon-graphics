package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.BindingLayout;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSet;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSetDescriptor;

final class OpenGLBindingSet extends OpenGLResource implements BindingSet {
    final BindingSetDescriptor descriptor;

    OpenGLBindingSet(OpenGLDevice device, BindingSetDescriptor descriptor) {
        super(device);
        this.descriptor = descriptor;
    }

    @Override public BindingLayout layout() { requireAlive(); return descriptor.layout(); }
    @Override void deleteNative() { /* Pure logical object; resources remain caller-owned. */ }
}
