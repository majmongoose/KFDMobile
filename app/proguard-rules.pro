# KFDDroid ProGuard rules
# usb-serial-for-android uses JNI internally — keep driver classes
-keep class com.hoho.android.usbserial.** { *; }
