# Add project specific ProGuard rules here.
# Wi-Fi Direct and networking classes must not be obfuscated.
-keep class android.net.wifi.p2p.** { *; }
-keepattributes Exceptions, Signature
