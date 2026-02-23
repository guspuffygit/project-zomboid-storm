package io.pzstorm.storm.patch.rendering;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnGetWorldMapFilterOptions;
import java.util.List;
import java.util.stream.Collectors;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.config.ConfigOption;
import zombie.worldMap.WorldMapRenderer;

/** Patches {@link zombie.worldMap.UIWorldMapV1} */
public class UIWorldMapV1Patch extends StormClassTransformer {

    public UIWorldMapV1Patch() {
        super("zombie.worldMap.UIWorldMapV1");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(GetOptionCountAdvice.class)
                                .on(ElementMatchers.named("getOptionCount")))
                .visit(
                        Advice.to(GetOptionByIndexAdvice.class)
                                .on(
                                        ElementMatchers.named("getOptionByIndex")
                                                .and(ElementMatchers.takesArgument(0, int.class))))
                .visit(
                        Advice.to(GetOptionByIndexAdvice.class)
                                .on(
                                        ElementMatchers.named("getOptionByIndex")
                                                .and(ElementMatchers.takesArgument(0, int.class))))
                .defineMethod("getExtraOptionNames", List.class, Visibility.PUBLIC)
                .intercept(MethodDelegation.to(NewMethodInterceptor.class));
    }

    public static class NewMethodInterceptor {
        public static List<String> getExtraOptionNames() {
            OnGetWorldMapFilterOptions event = new OnGetWorldMapFilterOptions();
            StormEventDispatcher.dispatchEvent(event);

            return event.getExtraConfigOptions().stream()
                    .map(ConfigOption::getName)
                    .collect(Collectors.toList());
        }
    }

    public static class GetOptionCountAdvice {
        @Advice.OnMethodExit
        public static void onExit(@Advice.Return(readOnly = false) int returnValue) {
            OnGetWorldMapFilterOptions event = new OnGetWorldMapFilterOptions();
            StormEventDispatcher.dispatchEvent(event);
            returnValue += event.getExtraConfigOptions().size();

            LOGGER.debug("GetOptionCountAdvice original value: {}", returnValue);
        }
    }

    public static class GetOptionByIndexAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter() {
            return true;
        }

        /**
         * @param renderer {@link zombie.worldMap.UIWorldMapV1#renderer}
         */
        @Advice.OnMethodExit
        public static void onExit(
                @Advice.Argument(0) int index,
                @Advice.FieldValue("renderer") WorldMapRenderer renderer,
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC)
                        ConfigOption returnValue) {

            try {
                returnValue = renderer.getOptionByIndex(index);
            } catch (Exception originalException) {
                OnGetWorldMapFilterOptions event = new OnGetWorldMapFilterOptions();
                StormEventDispatcher.dispatchEvent(event);
                try {
                    returnValue =
                            event.getExtraConfigOptions().get(index - renderer.getOptionCount());
                } catch (Exception patchException) {
                    LOGGER.error("Unable to getOptionByIndex", originalException);
                    LOGGER.error("Unable to get extraConfigOptions", patchException);
                    throw new RuntimeException(patchException);
                }
            }

            ObjectMapper mapper = new ObjectMapper();

            LOGGER.debug(
                    "GetOptionByIndexAdvice index: {}, name: {}, value: {}, tooltip: {}",
                    index,
                    returnValue.getName(),
                    returnValue.getValueAsString(),
                    returnValue.getTooltip());
            try {
                LOGGER.debug("GetOptionByIndexAdvice: {}", mapper.writeValueAsString(returnValue));
            } catch (JsonProcessingException e) {
                LOGGER.error("Unable to convert to JSON");
            }
        }
    }
}
