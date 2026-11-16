# Tool schemas use reflection; preserve tool methods and their schema metadata.
-keep class com.galaxyrio.gracelauncher.assistant.MobileActionTools { *; }
-keep class com.galaxyrio.gracelauncher.assistant.WebSearchTools { *; }
-keepclassmembers class * {
    @com.google.ai.edge.litertlm.Tool <methods>;
}
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,RuntimeVisibleParameterAnnotations,MethodParameters,Signature,InnerClasses,EnclosingMethod
-keep class com.google.ai.edge.litertlm.** { *; }
