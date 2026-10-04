# R8 keep rules for release builds.
#
# This project calls everything directly — there is no reflection, no
# serialization framework and no dependency-injection container that resolves
# types by name — so nothing of the app's own code needs a keep rule. The one
# exception is NewPipeExtractor: it resolves YouTube/audio streams through a
# parser whose entry points are reached from code this project does not own, and
# a renamed entry point there would fail at runtime (on a device, in the Media
# tab) rather than at build time. Keeping it is the cheap, honest side of that
# trade: it costs some obfuscation percentage for a library, never correctness
# for a feature.
#
# Everything else relies on the consumer rules its own library ships (Compose,
# Firebase, media3/ExoPlayer, androidx.media), which AGP applies automatically.

-keep class org.schabi.newpipe.extractor.** { *; }

# NewPipeExtractor pulls in Rhino (org.mozilla.javascript) to decipher YouTube
# signatures. Rhino's JSON converter references java.beans.* — a desktop-only
# package that does not exist on Android, and which that code path never reaches
# on a device. R8 still sees the reference and fails the build over it, so the
# missing classes are declared as warnings. `javax.script` is the same story: a
# service Rhino advertises in META-INF/services that Android does not provide.
-dontwarn java.beans.**
-dontwarn javax.script.**

# Room generates one `<Name>_Impl` subclass per database and, at runtime, looks
# that class up by name and instantiates it through its NO-ARG constructor. AGP 9
# runs R8 in strict full mode (android.r8.strictFullModeForKeepRules=true), where
# `-keep class A` no longer implies `-keep class A { <init>(); }` — so the
# constructor was stripped and the app crashed at startup with
# `NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init>`. Keeping
# the constructor explicitly is what the reflective lookup needs. The WorkManager
# database is only one instance; covering every RoomDatabase subclass covers any
# future ones without a rule per database.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
