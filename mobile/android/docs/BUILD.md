# البناء من نسخة نظيفة

المتطلبات: JDK 21، وAndroid SDK فيه المنصة 35 وأدوات البناء 35 أو 34. Gradle Wrapper موجود في هذا المجلد ويحمّل Gradle 8.11.1.

```
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
echo "sdk.dir=$ANDROID_HOME" > local.properties
chmod +x gradlew
./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

`local.properties` غير ملتزم لأنه يحتوي مسار الجهاز. الناتج:

```
app/build/outputs/apk/debug/app-debug.apk
```

فحص الحزمة والتوقيع:

```
aapt dump badging app/build/outputs/apk/debug/app-debug.apk | head
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

التثبيت:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` يستبدل بناء debug السابق بنفس الشهادة ويبقي قاعدة البيانات. إن كانت الشهادة مختلفة يرفض أندرويد التثبيت. لا تحذف التطبيق قبل تصدير النسخة المحلية من الإعدادات إن كانت البيانات مهمة.

لا يُبنى release لأن المستودع لا يحتوي مفتاح إصدار، ولم يُنشأ مفتاح جديد حتى لا يُظن أنه يحدّث التثبيت القديم.

النسخة المحفوظة بشهادة التوقيع السابقة هي `dist/cloud.lork.audit-1.0.2.apk`. يتوفر أيضاً [بناء الفحص 1.0.4](../dist/cloud.lork.audit-1.0.4-test.apk) بشهادة مختلفة؛ راجع [تقرير الملف المرفوع](../dist/cloud.lork.audit-1.0.4-test.build.json). ملف `1.0.0` كان بتوقيع v2 فقط، ورافضه مثبّت الهاتف برسالة «التطبيق ليس مثبتًا». من 1.0.1 يحمل الملف توقيع JAR أيضاً. ملف `1.0.0` كان بتوقيع v2 فقط، ورافضه مثبّت الهاتف برسالة «التطبيق ليس مثبتًا». الإصدار 1.0.1 يحمل توقيع JAR أيضاً ويُستخرج مكتباته الأصلية عند التثبيت. سير `.github/workflows/android-audit.yml` يبني APK debug ويرفعه أثراً، وشهادته تختلف عن شهادة الملف المحفوظ. اقرأ SHA-1 من الملف الذي ستثبّته قبل إنشاء عميل OAuth. وقت الدفع كان حساب GitHub مقفلاً بسبب الفوترة، فلم يُنتج Actions أثراً.


بناء إصلاح اختيار الملفات: [APK 1.0.5](../dist/cloud.lork.audit-1.0.5-test.apk)، [تقرير البناء](../dist/cloud.lork.audit-1.0.5-test.build.json). يستخدم شهادة نسخة الفحص 1.0.4 نفسها.
