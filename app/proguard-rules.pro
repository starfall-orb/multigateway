# Add project specific ProGuard rules here.

# jlatexmath-android loads fonts, symbol tables and macros by reflection and from assets.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
