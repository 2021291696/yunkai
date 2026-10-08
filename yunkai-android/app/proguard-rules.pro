# 云开 R8 规则（2026-10-08 性能轮）

# 崩溃堆栈可读
-keepattributes SourceFile,LineNumberTable

# PDFBox-Android：字体/资源加载走反射，全保留（否则运行期 PDF 抽取崩）
-keep class com.tom_roush.** { *; }

# kotlinx-serialization：库自带 consumer 规则，此处兜底防内联生成器
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers class **$$serializer {
    *** INSTANCE;
}

# DataStore preferences_pb 序列化生成的类按字面引用，无需额外规则；Room/Compose 自带 consumer 规则

# PDFBox 可选 JPEG2000 编解码器（未打包依赖；文字抽取用不到 JPX 图像）
-dontwarn com.gemalto.jp2.**
