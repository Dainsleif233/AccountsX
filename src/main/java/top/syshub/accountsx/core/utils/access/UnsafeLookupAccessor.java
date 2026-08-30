package top.syshub.accountsx.core.utils.access;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

public final class UnsafeLookupAccessor {
    private UnsafeLookupAccessor() {
    }

    /**
     * Obtain {@code MethodHandles.Lookup.IMPL_LOOKUP} via {@code sun.misc.Unsafe}.
     * Memory-access methods used here are deprecated for removal (JEP 471).
     */
    @SuppressWarnings({"removal"})
    public static MethodHandles.Lookup get() throws Throwable {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Unsafe u = (Unsafe) theUnsafe.get(null);
        Field implLookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        Object base = u.staticFieldBase(implLookup);
        long offset = u.staticFieldOffset(implLookup);
        return (MethodHandles.Lookup) u.getObject(base, offset);
    }
}
