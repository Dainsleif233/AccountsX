package net.burningtnt.accountsx.core.utils.access;

import java.lang.invoke.MethodHandles;

/**
 * Fallback for obtaining {@code MethodHandles.Lookup.IMPL_LOOKUP} without
 * {@code sun.misc.Unsafe} memory-access methods (JEP 471).
 * <p>
 * FFM is a standard API only from Java 22; this project still compiles core as
 * Java 17, so a full FFM+JNI implementation cannot live here yet.
 * See: <a href="https://gist.github.com/burningtnt/c188e65f048c2cf096db095e5858b5af">TrustedLookupAccessor</a>
 */
public final class FFMAPILookupAccessor {
    private FFMAPILookupAccessor() {
    }

    public static MethodHandles.Lookup get() throws Throwable {
        // UnsafeLookupAccessor may stop working when JEP 471 memory-access methods
        // are removed. A FFM-based workaround exists but requires Java 22+ sources
        // (or a multi-release jar), which this module does not target yet.
        throw new IllegalStateException(
                "FFMAPI lookup accessor is not implemented for Java 17-targeted builds. " +
                        "See https://gist.github.com/burningtnt/c188e65f048c2cf096db095e5858b5af"
        );
    }
}
