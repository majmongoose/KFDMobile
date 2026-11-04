# KFDMobile R8 rules

# usb-serial-for-android: driver classes are looked up through probe tables.
-keep class com.hoho.android.usbserial.** { *; }

# Fragments and DialogFragments are re-created by class name after rotation or
# process restore, so keep their names and no-arg constructors.
-keep public class * extends androidx.fragment.app.Fragment {
    public <init>();
}

# Container model is Parcelable (saved across rotation). The default rules keep
# CREATOR; this keeps the field layout stable as well.
-keep class com.sentinelcomms.kfdmobile.container.** implements android.os.Parcelable { *; }
