# Tool schemas use reflection; preserve only this tool set.
-keep class com.galaxyrio.gracelauncher.assistant.MobileActionTools { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,Signature,InnerClasses,EnclosingMethod
-keep class com.google.ai.edge.litertlm.** { *; }
