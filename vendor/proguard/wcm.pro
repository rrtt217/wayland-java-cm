# ProGuard 7.10.0 rules that actually keep wayland-java working.
#
# Verified on Java 25 (class file v69). ProGuard 7.10.0 REQUIRES proguard-core
# 9.4.0 -- using an older core gives a misleading
#   "Unsupported version number [69.0] (maximum 68.65535)"
# that looks like ProGuard does not support Java 25. It does.
#
# Every keep below corresponds to a reflection / method-handle lookup site that
# is invisible to static analysis. Each one was found by a failing run.

-dontobfuscate
-dontoptimize

# InterfaceMeta reads @Interface / @Message at runtime.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# EnumUtil.buildEnumMap does enumClass.getDeclaredMethod("getValue").invoke(...)
-keepclasseswithmembers class * { public int getValue(); }

# Proxy.marshalProxy -> findMatchingConstructor(newProxyCls, MemorySegment.class,
# implementation.getClass(), int.class): reflectively invokes the generated
# proxy constructor, so every proxy class AND the events hierarchy must survive.
-keep class org.freedesktop.wayland.client.** { *; }
-keep class org.freedesktop.wayland.shared.** { *; }
-keep class org.freedesktop.wayland.util.** { *; }

# libwayland's dispatcher is installed as an UPCALL: LibWayland$shared's
# upcallHandle() does MethodHandles.lookup().findVirtual(fi, name, type) on
# wl_dispatcher_func_t$Function. Nothing in the source mentions reflection, so
# this was the subtle one; losing it surfaces as wayland-java's generic
#   RuntimeException: "Uh oh, this is a bug!"
#   caused by AssertionError: NoSuchMethodException: no such method:
#     ...wl_dispatcher_func_t$Function.apply(...)int/invokeInterface
-keep class org.freedesktop.wayland.raw.** { *; }

# the application
-keep class wcm.** { *; }
