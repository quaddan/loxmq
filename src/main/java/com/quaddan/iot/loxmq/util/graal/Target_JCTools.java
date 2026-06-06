/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.util.graal;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.RecomputeFieldValue.Kind;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * GraalVM {@link TargetClass} substitutions for the standalone JCTools
 * {@code org.jctools.util.UnsafeRefArrayAccess} +
 * {@code UnsafeLongArrayAccess} static-init fields.
 *
 * <h3>Why</h3>
 * GraalVM's native-image points-to analysis tries to AUTO-recompute the
 * value of every {@code static final} field that comes from a call to
 * {@code sun.misc.Unsafe.arrayBaseOffset(Class)} /
 * {@code sun.misc.Unsafe.arrayIndexScale(Class)} so the image embeds
 * <em>target-architecture-specific</em> offsets (not host-build-time
 * offsets — which would corrupt ring-buffer index math on a different
 * arch). JCTools' static initialiser pattern stores the call result
 * through an intermediate, which the GraalVM analyser cannot follow:
 *
 * <pre>
 * Warning: RecomputeFieldValue.ArrayIndexScale automatic field value
 * transformation failed. Could not determine the field where the value
 * produced by the call to jdk.internal.misc.Unsafe.arrayIndexScale(Class)
 * for the array index scale computation is stored.
 * </pre>
 *
 * Result: the native image keeps the host's compile-time shift constant
 * (typically 2 or 3, depending on JVM compressed-oops mode). At runtime
 * inside the native binary, JCTools computes element offsets using a
 * stale shift → reads memory off-by-one-power-of-two → segfault under
 * burst load.
 *
 * <h3>What this class does</h3>
 * Explicitly tells GraalVM "for these fields, compute the value using
 * the proper {@code Kind.ArrayIndexShift} / {@code Kind.ArrayBaseOffset}
 * recompute mechanism — same as you do when the auto-detect succeeds".
 * The {@link Alias} annotation marks the field as a placeholder that
 * GraalVM redirects to the original class at native-image build time;
 * the {@link RecomputeFieldValue} annotation provides the recipe.
 *
 * <h3>Scope</h3>
 * Only the <em>standalone</em> {@code org.jctools.*} package is patched.
 * Netty's shaded copy ({@code io.netty.util.internal.shaded.org.jctools.*})
 * is already handled by Quarkus core ({@code quarkus-netty}'s
 * {@code NettyProcessor.unsafeAccessedFields()}). The HiveMQ client we
 * use ({@code quarkus-hivemq-client:2.5.0} → {@code hivemq-mqtt-client:1.3.5})
 * does NOT ship a shaded JCTools copy (verified via {@code unzip -l}),
 * so we only need the standalone-namespace substitutions here.
 *
 * <h3>Why a separate class per target</h3>
 * One {@link TargetClass} declaration per target class is the SVM
 * convention. Both substitution classes live in this single file as
 * nested package-private classes — keeps the related-by-purpose patches
 * together, but javac still emits the right top-level {@code .class}
 * names that SVM picks up.
 *
 * @see <a href="https://github.com/netty/netty/issues/10376">netty/netty#10376
 *      — original JCTools-on-GraalVM bug</a>
 * @see <a href="https://github.com/quarkusio/quarkus/issues/21236">quarkus#21236
 *      — Native code with HiveMQ runs into segfault</a>
 * @see <a href="https://developers.redhat.com/articles/2022/05/09/using-unsafe-safely-graalvm-native-image">
 *      Red Hat — Using Unsafe safely in GraalVM Native Image</a>
 */
public final class Target_JCTools
{
    private Target_JCTools() { }
}

/**
 * Substitution for the Object[] flavour of JCTools' Unsafe array access.
 * The {@code REF_*} fields are read by every Mpsc / Spsc queue offer /
 * poll under the HiveMQ async publish pipeline.
 */
@TargetClass( className = "org.jctools.util.UnsafeRefArrayAccess" )
final class Target_org_jctools_util_UnsafeRefArrayAccess
{
    /** Position-shift to convert an array index into a byte offset.
     *  E.g. for {@code Object[]} with compressed oops, shift is 2 (×4 bytes).
     *  THIS is the one GraalVM cannot auto-detect — without this @Alias
     *  the native image embeds the host's compile-time value, causing
     *  segfaults under burst load. {@code REF_ARRAY_BASE} sibling field
     *  IS auto-detected by GraalVM (verified via build warning), so we
     *  don't substitute it explicitly. */
    @Alias
    @RecomputeFieldValue( kind = Kind.ArrayIndexShift, declClass = Object[].class )
    public static int REF_ELEMENT_SHIFT;
}

/**
 * Substitution for the long[] flavour. Used by JCTools' counting /
 * timestamp queues internally — less hot than the ref variant but
 * still reachable transitively from HiveMQ's pipeline.
 * Only the SHIFT field needs explicit substitution (same reasoning as
 * {@link Target_org_jctools_util_UnsafeRefArrayAccess}).
 */
@TargetClass( className = "org.jctools.util.UnsafeLongArrayAccess" )
final class Target_org_jctools_util_UnsafeLongArrayAccess
{
    @Alias
    @RecomputeFieldValue( kind = Kind.ArrayIndexShift, declClass = long[].class )
    public static int LONG_ELEMENT_SHIFT;
}
