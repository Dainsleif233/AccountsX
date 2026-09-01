package top.syshub.accountsx.common.utils.access;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

/**
 * Sole {@code accountsx:impl-lookup-accessor} strategy registered in
 * {@code fabric.mod.json}. It obtains {@code MethodHandles.Lookup.IMPL_LOOKUP}
 * via {@code sun.misc.Unsafe} memory-access methods.
 * <p>
 * These memory-access methods are deprecated for removal (JEP 471) and are
 * expected to be removed in a future JDK (tentatively 26+). Once they are gone
 * on the targeted runtime, add an FFM ({@code java.lang.foreign}) based
 * accessor and register it as an additional fallback entry in
 * {@code fabric.mod.json}'s {@code accountsx:impl-lookup-accessor} array.
 * Because this module targets Java 17 bytecode, any FFM code must be reached
 * reflectively (or via a multi-release jar) so it does not trip a
 * {@code NoClassDefFoundError} on Java 17-21 runtimes where FFM does not exist.
 */
@SuppressWarnings("unused") // instantiated reflectively via fabric.mod.json's accountsx:impl-lookup-accessor; IDEA cannot see the usage
public final class UnsafeLookupAccessor {
    private UnsafeLookupAccessor() {
    }

    /**
     * Obtain {@code MethodHandles.Lookup.IMPL_LOOKUP} via {@code sun.misc.Unsafe}.
     * Memory-access methods used here are deprecated for removal (JEP 471).
     */
    @SuppressWarnings("removal")
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
