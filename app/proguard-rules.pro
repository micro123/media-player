# libmpv AAR supplies its own consumer JNI keep rules.

# SMBJ's optional Kerberos authenticator uses Java GSS, absent on Android.
# The application explicitly configures NTLM only.
-dontwarn org.ietf.jgss.**
# MBassador's optional expression-language filters are never used by SMBJ.
-dontwarn javax.el.**
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler <methods>;
}
