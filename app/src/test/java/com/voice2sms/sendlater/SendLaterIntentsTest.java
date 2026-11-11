package com.voice2sms.sendlater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/** SendLaterIntents is a String-constants-only holder shared by every Send Later node. */
public class SendLaterIntentsTest {

    @Test
    public void onlyPublicStaticFinalStringConstants() throws Exception {
        Class<?> c = Class.forName("com.voice2sms.sendlater.SendLaterIntents");
        Field[] fields = c.getDeclaredFields();
        assertTrue("SendLaterIntents declares no constants", fields.length > 0);
        Set<Object> values = new HashSet<>();
        int constants = 0;
        for (Field f : fields) {
            if (f.isSynthetic()) continue;
            int mod = f.getModifiers();
            assertTrue(f.getName() + " must be public static final",
                    Modifier.isPublic(mod) && Modifier.isStatic(mod) && Modifier.isFinal(mod));
            assertEquals(f.getName() + " must be a String", String.class, f.getType());
            String v = (String) f.get(null);
            assertTrue(f.getName() + " must be non-empty", v != null && !v.isEmpty());
            values.add(v);
            constants++;
        }
        assertEquals("constant values must be distinct", constants, values.size());
        for (Method m : c.getDeclaredMethods()) {
            assertFalse("SendLaterIntents must hold constants only, found method " + m.getName(), !m.isSynthetic());
        }
    }
}
