# ============================================================================
# Eka Scribe SDK - Consumer ProGuard Rules
# These rules are automatically applied to any app that depends on this SDK.
# ============================================================================

# --- Gson Serialization ---
# Keep all fields in model classes used by Gson for JSON serialization.
# Without this, R8 renames fields and Gson can't map JSON keys to fields.

# Public API models (exposed to host app)
-keep class com.eka.scribesdk.api.models.** { *; }

# Remote request/response DTOs (internal, serialized via Gson/Retrofit)
-keep class com.eka.scribesdk.data.remote.models.** { *; }

# AWS S3 config response
-keep class com.eka.scribesdk.data.remote.S3Credentials { *; }

# --- Gson itself ---
# Keep Gson's internal TypeToken (used for generic type resolution)
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Keep @SerializedName annotations accessible at runtime
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep fields annotated with @SerializedName (redundant with above, but safe)
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- Retrofit ---
# Keep Retrofit service interface methods (annotations must stay)
-keep,allowobfuscation interface com.eka.scribesdk.data.remote.api.** {
    @retrofit2.http.* <methods>;
}

# --- ONNX Runtime ---
# Keep ONNX Runtime JNI classes
-keep class ai.onnxruntime.** { *; }

# --- Room ---
# Room generates code that must not be obfuscated
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.ColumnInfo *;
}

# --- AWS SDK ---
-keep class com.amazonaws.** { *; }
-dontwarn com.amazonaws.**
