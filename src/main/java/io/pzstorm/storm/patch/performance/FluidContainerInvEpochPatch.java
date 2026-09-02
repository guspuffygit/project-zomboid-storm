package io.pzstorm.storm.patch.performance;

/**
 * Inventory-weight epoch sources on {@code zombie.entity.components.fluids.FluidContainer}: every
 * method that changes the stored amount ({@code getContentsWeight()} reads {@code getAmount()}).
 * {@code invalidateColor} is colour-only and excluded. Server-only by registration gate.
 */
public class FluidContainerInvEpochPatch extends NamedMethodsAdvicePatch {

    public FluidContainerInvEpochPatch() {
        super(
                "zombie.entity.components.fluids.FluidContainer",
                "io.pzstorm.storm.advice.inventoryweight.FluidContainerInvEpochBumpAdvice",
                "setCapacity",
                "adjustAmount",
                "adjustSpecificFluidAmount",
                "addFluid",
                "removeFluid",
                "copyFluidsFrom",
                "Empty",
                "removeFluidInstanceIfEmpty",
                "load");
    }
}
