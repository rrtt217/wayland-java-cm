# ProGuard rules for shrinking an application jar.
#
# Two goals, in this order:
#   1. confine every removal to org.freedesktop.wayland.* -- nothing else may be
#      touched, so the application and any other input classes survive verbatim;
#   2. inside that package, keep the reflection and method-handle sites that no
#      static analysis can see.
#
# Verified with ProGuard 7.10.0, which REQUIRES proguard-core 9.4.0. Pairing it
# with an older core gives a misleading
#   "Unsupported version number [69.0] (maximum 68.65535)"
# that looks like ProGuard cannot read Java 25 class files. It can.

-dontobfuscate
-dontoptimize
-dontwarn
-dontnote

# InterfaceMeta reads @Interface / @Message at runtime, and MessageMeta filters
# argument types with isAnnotationPresent(Interface.class). Dropping
# RuntimeVisibleAnnotations therefore does not merely lose metadata: every
# wl_message.types entry would become NULL, breaking object and new_id
# arguments. Keep this line whatever else changes.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# ---------------------------------------------------------------------------
# 1. The hard boundary.
#
# A class specification consisting only of negations means "everything except
# these", so this keeps every class outside the Wayland package tree. It covers
# the application (wcm.**) and anything else on the input; the JDK is a library
# and is never shrunk anyway.
-keep class !org.freedesktop.wayland.** { *; }

# ---------------------------------------------------------------------------
# 2. Inside org.freedesktop.wayland.* -- only shrinkable region.

# EnumUtil.buildEnumMap reflects: enumClass.getDeclaredMethod("getValue").invoke(...)
# and calls enumClass.getEnumConstants(). Keeping only getValue() is not enough:
# ProGuard also strips the constants and values(), after which getEnumConstants()
# returns null and the enum's static initialiser blows up with
#   ExceptionInInitializerError caused by NullPointerException:
#   Cannot read the array length because "<local4>" is null
# so the whole enum package is kept.
-keepclasseswithmembers class * { public int getValue(); }
-keep class org.freedesktop.wayland.shared.** { *; }

# Proxy.marshalConstructor -> marshalProxy -> findMatchingConstructor(
#     newProxyCls, MemorySegment.class, implementation.getClass(), int.class)
# reflectively invokes the generated proxy constructor, so the constructors and
# the events hierarchy behind them must survive even when nothing calls them.
-keepclassmembers class org.freedesktop.wayland.client.** {
    public <init>(...);
}

# libwayland's dispatcher is installed as an UPCALL: the generated
# upcallHandle() does MethodHandles.lookup().findVirtual(fi, name, type) on
# wl_dispatcher_func_t$Function. Nothing in the source mentions reflection, so
# this is the easy one to miss; losing it surfaces as wayland-java's generic
#   RuntimeException: "Uh oh, this is a bug!"
#   caused by AssertionError: NoSuchMethodException: no such method:
#     ...wl_dispatcher_func_t$Function.apply(...)int/invokeInterface
-keep interface org.freedesktop.wayland.raw.** { *; }
-keep class org.freedesktop.wayland.raw.**$*Holder { *; }

# InterfaceMeta.get(type) builds wl_interface metadata from the annotations, and
# reachable only through it.
-keep class org.freedesktop.wayland.util.InterfaceMeta { *; }
-keep class org.freedesktop.wayland.util.Interface { *; }
-keep class org.freedesktop.wayland.util.Message { *; }
-keep class org.freedesktop.wayland.util.Arguments { *; }
