package com.tiimoo.dexrefresh.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Null-returning reflection helpers.
 *
 * <p>Every lookup here answers "does this exist on *this* firmware?" rather than
 * throwing. Samsung's One UI internals diverge from AOSP in ways that are only
 * discoverable at runtime, so nothing in this module may assume a name resolves.
 */
public final class Reflect {

    private Reflect() {
    }

    public static Class<?> cls(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** First name that resolves, or null. Use for AOSP-moved-the-class cases. */
    public static Class<?> firstClass(ClassLoader cl, String... names) {
        for (String name : names) {
            Class<?> c = cls(cl, name);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    public static Method method(Class<?> c, String name, Class<?>... params) {
        if (c == null) {
            return null;
        }
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                // keep walking up
            }
        }
        return null;
    }

    /** Every declared overload with this name, walking the hierarchy. */
    public static List<Method> methodsNamed(Class<?> c, String name) {
        List<Method> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Method[] declared;
            try {
                declared = k.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }
            for (Method m : declared) {
                if (m.getName().equals(name)) {
                    try {
                        m.setAccessible(true);
                    } catch (Throwable ignored) {
                        // still hookable without accessibility
                    }
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** Declared methods (this class only) whose name matches the pattern. */
    public static List<Method> methodsMatching(Class<?> c, Pattern p) {
        List<Method> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        Method[] declared;
        try {
            declared = c.getDeclaredMethods();
        } catch (Throwable t) {
            return out;
        }
        for (Method m : declared) {
            if (m.isSynthetic() || m.isBridge()) {
                continue;
            }
            // Native and abstract methods must never be hooked. SurfaceControl
            // in particular has private static native methods whose names match
            // the refresh-rate pattern (nativeSetDesiredDisplayModeSpecs);
            // hooking those is unsupported and would perturb the very path we
            // are trying to observe.
            int mods = m.getModifiers();
            if (Modifier.isNative(mods) || Modifier.isAbstract(mods)) {
                continue;
            }
            if (p.matcher(m.getName()).find()) {
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                    // still hookable
                }
                out.add(m);
            }
        }
        return out;
    }

    public static List<Constructor<?>> constructors(Class<?> c) {
        List<Constructor<?>> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        try {
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                try {
                    ctor.setAccessible(true);
                } catch (Throwable ignored) {
                    // still hookable
                }
                out.add(ctor);
            }
        } catch (Throwable ignored) {
            // nothing we can do
        }
        return out;
    }

    public static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // keep walking up
            }
        }
        return null;
    }

    /** Field value by name, or null if absent/unreadable. */
    public static Object get(Object target, String name) {
        if (target == null) {
            return null;
        }
        Field f = field(target.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object getStatic(Class<?> c, String name) {
        Field f = field(c, name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * First field on {@code target} whose declared type name contains
     * {@code typeFragment}. For finding e.g. the VotesStorage instance without
     * knowing what Samsung called the field.
     */
    public static Object findByTypeFragment(Object target, String typeFragment) {
        if (target == null) {
            return null;
        }
        String needle = typeFragment.toLowerCase(java.util.Locale.US);
        for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fields;
            try {
                fields = k.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (!f.getType().getName().toLowerCase(java.util.Locale.US).contains(needle)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(target);
                    if (v != null) {
                        return v;
                    }
                } catch (Throwable ignored) {
                    // try the next candidate
                }
            }
        }
        return null;
    }

    /** Invoke with no args, swallowing everything. Returns null on any failure. */
    public static Object call(Object target, String name) {
        if (target == null) {
            return null;
        }
        Method m = method(target.getClass(), name);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String sig(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(simple(m.getReturnType())).append(' ').append(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(simple(ps[i]));
        }
        return sb.append(')').toString();
    }

    public static String sig(Constructor<?> c) {
        StringBuilder sb = new StringBuilder("<init>(");
        Class<?>[] ps = c.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(simple(ps[i]));
        }
        return sb.append(')').toString();
    }

    public static String simple(Class<?> c) {
        if (c == null) {
            return "?";
        }
        if (c.isArray()) {
            // Otherwise Display.Mode[] renders as the raw "Display$Mode;".
            return simple(c.getComponentType()) + "[]";
        }
        String n = c.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(dot + 1);
    }
}
