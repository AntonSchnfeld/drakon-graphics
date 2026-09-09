package io.github.antonschnfeld.drakon.graphics.examples;

import io.github.antonschnfeld.drakon.graphics.contract.ApiContractChecks;

public final class AllExamples {
    public static void main(String[] args) {
        for (String backend : new String[]{"opengl", "vulkan"}) {
            A_TexturedMesh.run(backend);
            B_Instancing.run(backend);
            C_ShadowAndMainPass.run(backend);
            D_PostProcess.run(backend);
            E_ComputePass.run(backend);
            F_CustomRenderPass.run(backend);
            G_CustomRenderPipeline.run(backend);
            H_RenderToTexture.run(backend);
            I_ResizeTarget.run(backend);
        }
        J_BackendParity.run();
        ApiContractChecks.run();
        System.out.println("Iteration 6 A-J probes and API contract checks completed successfully.");
    }
}
