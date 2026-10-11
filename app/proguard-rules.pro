# FoldBook project-specific R8/ProGuard rules.
#
# AndroidX, Jetpack Compose, WindowManager, DocumentFile and Google Play Billing
# publish their own consumer rules where required. Keep this file intentionally
# minimal so R8 can shrink, optimize and obfuscate as much code as safely possible.
#
# Add narrowly-scoped -keep rules here only if a future release build exposes a
# reflection/serialization issue. Avoid -dontshrink, -dontoptimize or
# -dontobfuscate in production builds.
