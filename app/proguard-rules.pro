-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room and ML Kit publish their own consumer rules. Keep only serialized enum names and
# model serializers whose stable names are part of the app-private schema.
-keepclassmembers enum app.shareguard.core.model.** { *; }
-keep,includedescriptorclasses class app.shareguard.core.model.**$$serializer { *; }
