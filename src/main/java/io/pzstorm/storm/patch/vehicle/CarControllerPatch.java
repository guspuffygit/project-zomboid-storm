package io.pzstorm.storm.patch.vehicle;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.vehicle.MouseSteeringCalculator;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.physics.CarController;
import zombie.vehicles.BaseVehicle;

/**
 * Patches {@link zombie.core.physics.CarController} to replace keyboard steering input with
 * mouse-cursor-following steering.
 *
 * <p>After the original {@code updateControls()} reads keyboard input, this advice overwrites
 * {@code clientControls.steering} with an analog value computed from the angle between the vehicle
 * and the mouse cursor.
 */
public class CarControllerPatch extends StormClassTransformer {

    public CarControllerPatch() {
        super("zombie.core.physics.CarController");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {

        return builder.visit(
                Advice.to(UpdateControlsAdvice.class).on(ElementMatchers.named("updateControls")));
    }

    public static class UpdateControlsAdvice {

        @Advice.OnMethodExit
        public static void afterUpdateControls(@Advice.This Object self) {
            CarController controller = (CarController) self;
            BaseVehicle vehicle = controller.vehicleObject;
            if (vehicle.isKeyboardControlled()) {
                controller.clientControls.steering =
                        MouseSteeringCalculator.calculateMouseSteering(vehicle);
            }
        }
    }
}
