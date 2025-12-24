package com.qb20nh.cbbg.config.gui;

import com.qb20nh.cbbg.config.CbbgConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CbbgConfigScreenTest {

        @Test
        void powerOfTwoSlider_mapsToPowersOfTwo() throws Exception {
                Class<?> sliderClass = Class.forName(
                                "com.qb20nh.cbbg.config.gui.CbbgConfigScreen$PowerOfTwoSlider");
                Constructor<?> ctor = sliderClass.getDeclaredConstructor(int.class, int.class,
                                int.class, int.class, Component.class, int.class, int.class,
                                int.class, IntConsumer.class);
                ctor.setAccessible(true);

                AtomicInteger lastSet = new AtomicInteger(-1);
                Object slider = ctor.newInstance(0, 0, 100, 20, Component.literal("Size: "), 16,
                                256, 128, (IntConsumer) lastSet::set);

                setSliderValue(slider, 0.0);
                invokeProtected(sliderClass, slider, "applyValue");
                Assertions.assertEquals(16, lastSet.get());

                setSliderValue(slider, 1.0);
                invokeProtected(sliderClass, slider, "applyValue");
                Assertions.assertEquals(256, lastSet.get());
        }

        @Test
        void floatSlider_clampsInputAndFormatsTwoDecimals() throws Exception {
                Class<?> sliderClass = Class
                                .forName("com.qb20nh.cbbg.config.gui.CbbgConfigScreen$FloatSlider");
                Constructor<?> ctor = sliderClass.getDeclaredConstructor(int.class, int.class,
                                int.class, int.class, Component.class, float.class, float.class,
                                float.class, DoubleConsumer.class);
                ctor.setAccessible(true);

                AtomicReference<Double> lastSet = new AtomicReference<>();
                Object slider = ctor.newInstance(0, 0, 100, 20, Component.literal("Strength: "),
                                0.5f, 4.0f, 999.0f, (DoubleConsumer) lastSet::set);

                // Constructor clamps currentValue to max, so initial apply should be max.
                invokeProtected(sliderClass, slider, "applyValue");
                Assertions.assertEquals(4.0, lastSet.get(), 0.0001);

                // Message should include two decimals.
                Method getMessage = sliderClass.getMethod("getMessage");
                Component msg = (Component) getMessage.invoke(slider);
                Assertions.assertTrue(msg.getString().contains("4.00"));
        }

        private static void setSliderValue(Object slider, double value) throws Exception {
                Field f = AbstractSliderButton.class.getDeclaredField("value");
                f.setAccessible(true);
                f.setDouble(slider, value);

                // Keep displayed label in sync with internal value.
                invokeProtected(slider.getClass(), slider, "updateMessage");
        }

        private static void invokeProtected(Class<?> cls, Object instance, String methodName)
                        throws Exception {
                Method m = cls.getDeclaredMethod(methodName);
                m.setAccessible(true);
                m.invoke(instance);
        }
}
