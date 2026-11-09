# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to the flags specified
# in defaultProguardFile.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Room
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <methods>;
}

# Keep data classes / models used in Room or serialization if needed
-keepclassmembers class * {
    @androidx.room.Entity *;
    @androidx.room.Dao *;
}
