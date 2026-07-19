package top.syshub.accountsx.core.utils;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import top.syshub.accountsx.core.AccountsX;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.CustomValue;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class UnsafeVM {
    private UnsafeVM() {
    }

    private static final MethodHandles.Lookup GENERAL_LOOKUP = MethodHandles.lookup();

    @SuppressWarnings("deprecation")
    private static final Supplier<MethodHandles.Lookup> IMPL_LOOKUP = Suppliers.memoize(() -> {
        CustomValue.CvArray impls = FabricLoader.getInstance().getModContainer(AccountsX.MOD_ID).orElseThrow(
                () -> new IllegalStateException("I should be loaded.")
        ).getMetadata().getCustomValue("accountsx:impl-lookup-accessor").getAsArray();

        int l = impls.size();
        Throwable[] ts = new Throwable[l];
        for (int i = 0; i < l; i++) {
            try {
                return (MethodHandles.Lookup) MethodHandles.publicLookup().findStatic(
                        Class.forName(impls.get(i).getAsString()),
                        "get",
                        MethodType.methodType(MethodHandles.Lookup.class)
                ).invokeExact();
            } catch (Throwable t) {
                ts[i] = t;
            }
        }

        throw fail("MethodHandles.Lookup::IMPL_LOOKUP", ts);
    });

    private static final Function<Class<?>, MethodHandle> UNSAFE_ALLOCATOR = new Function<>() {
        private static final ConcurrentHashMap<Class<?>, MethodHandle> CACHE = new ConcurrentHashMap<>();

        private static final Function<Class<?>, MethodHandle> COMPUTER = new Function<>() {
            private static final Supplier<MethodHandle> ALLOCATOR = Suppliers.memoize(() -> {
                try {
                    MethodHandles.Lookup LOOKUP = getLookup();
                    Unsafe U = (Unsafe) LOOKUP.findStaticGetter(Unsafe.class, "theUnsafe", Unsafe.class).invokeExact();
                    return LOOKUP.findVirtual(Unsafe.class, "allocateInstance", MethodType.methodType(Object.class, Class.class)).bindTo(U);
                } catch (Throwable t) {
                    throw fail("Unsafe::allocateInstance", t);
                }
            });

            private static final AtomicInteger INDEX = new AtomicInteger(0);

            @Override
            public MethodHandle apply(Class<?> clazz) {
                try {
                    ClassWriter cw = new ClassWriter(0);
                    cw.visit(
                            Opcodes.V17, Opcodes.ACC_PUBLIC,
                            "top/syshub/accountsx/core/utils/UnsafeVM$" + Integer.toHexString(INDEX.getAndIncrement()),
                            null, "java/lang/Object", null
                    );
                    {
                        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
                        mv.visitCode();
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                        mv.visitInsn(Opcodes.RETURN);
                        mv.visitMaxs(1, 1);
                        mv.visitEnd();
                    }
                    {
                        String targetName = clazz.getName().replace('.', '/');
                        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "cast", "(Ljava/lang/Object;)L" + targetName + ";", null, null);
                        mv.visitCode();
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, targetName);
                        mv.visitInsn(Opcodes.ARETURN);
                        mv.visitMaxs(1, 1);
                        mv.visitEnd();
                    }
                    cw.visitEnd();

                    Class<?> adapter = GENERAL_LOOKUP.defineHiddenClass(cw.toByteArray(), true).lookupClass();
                    return MethodHandles.filterReturnValue(
                            ALLOCATOR.get().bindTo(clazz),
                            UnsafeVM.getLookup().findStatic(adapter, "cast", MethodType.methodType(clazz, Object.class))
                    );
                } catch (Throwable t) {
                    throw fail("Unsafe::allocateInstance", t);
                }
            }
        };

        @Override
        public MethodHandle apply(Class<?> clazz) {
            return CACHE.computeIfAbsent(clazz, COMPUTER);
        }
    };

    public static MethodHandles.Lookup getLookup() {
        return IMPL_LOOKUP.get();
    }

    public static MethodHandle getClassAllocator(Class<?> clazz) {
        return UNSAFE_ALLOCATOR.apply(clazz);
    }

    public interface MethodHandleProvider {
        MethodHandle compute(MethodHandles.Lookup lookup) throws ReflectiveOperationException;
    }

    public static MethodHandle prepareMH(String target, MethodHandleProvider provider) {
        try {
            return provider.compute(getLookup());
        } catch (Throwable t) {
            throw fail(target, t);
        }
    }

    private static final class UnexpectedClassChangeError extends Error {
        public UnexpectedClassChangeError(String message, Throwable[] ts) {
            super(message);

            for (Throwable t : ts) {
                addSuppressed(t);
            }
        }

        public UnexpectedClassChangeError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static Error fail(String hackTarget, Throwable t) {
        return new UnexpectedClassChangeError("Cannot hack " + hackTarget + " due to unexpected changes. Please remove " + AccountsX.MOD_NAME, t);
    }

    public static Error fail(String hackTarget, Throwable... t) {
        return new UnexpectedClassChangeError("Cannot hack " + hackTarget + " due to unexpected changes. Please remove " + AccountsX.MOD_NAME, t);
    }
}
