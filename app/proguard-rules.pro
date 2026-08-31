# The JNI bridge is looked up by name from native code, so neither the class
# nor its method names may be renamed or stripped.
-keep class app.llmobi.engine.LlamaBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the Kotlin metadata Compose relies on.
-keep class kotlin.Metadata { *; }

# WorkManager instantiates workers reflectively.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Line numbers make a production crash report readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
