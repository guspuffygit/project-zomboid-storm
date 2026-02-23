package io.pzstorm.storm.logging;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class DebugLogStreamCompatibilityTest {

    private final Class<?> targetClass = DebugLogStream.class;
    private final Class<?> customClass = CustomDebugLogStream.class;

    @Test
    public void testConstructorsMatch() {
        for (Constructor<?> targetCons : targetClass.getDeclaredConstructors()) {
            // We only care about public/protected constructors that other classes might instantiate
            if (!Modifier.isPublic(targetCons.getModifiers())
                    && !Modifier.isProtected(targetCons.getModifiers())) {
                continue;
            }

            boolean found = false;
            for (Constructor<?> customCons : customClass.getDeclaredConstructors()) {
                if (Arrays.equals(targetCons.getParameterTypes(), customCons.getParameterTypes())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "Missing matching constructor for: " + targetCons);
        }
    }

    @Test
    public void testMethodsMatch() {
        for (Method targetMethod : targetClass.getDeclaredMethods()) {
            // Only check public/protected methods as private ones are not part of the API contract
            if (!Modifier.isPublic(targetMethod.getModifiers())
                    && !Modifier.isProtected(targetMethod.getModifiers())) {
                continue;
            }

            try {
                Method customMethod =
                        customClass.getDeclaredMethod(
                                targetMethod.getName(), targetMethod.getParameterTypes());

                assertTrue(
                        targetMethod.getReturnType().isAssignableFrom(customMethod.getReturnType()),
                        "Return type mismatch for " + targetMethod.getName());

            } catch (NoSuchMethodException e) {
                fail("Missing method in custom class: " + targetMethod);
            }
        }
    }

    @Test
    public void testStaticFieldsMatch() {
        for (Field targetField : targetClass.getDeclaredFields()) {
            // Only check public static fields (constants)
            if (!Modifier.isPublic(targetField.getModifiers())
                    || !Modifier.isStatic(targetField.getModifiers())) {
                continue;
            }

            try {
                Field customField = customClass.getDeclaredField(targetField.getName());
                assertTrue(
                        targetField.getType().isAssignableFrom(customField.getType()),
                        "Field type mismatch for " + targetField.getName());
            } catch (NoSuchFieldException e) {
                fail("Missing public static field: " + targetField.getName());
            }
        }
    }
}
