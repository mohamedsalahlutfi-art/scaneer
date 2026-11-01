SmartScan Pro - ملفات المشروع المعدل

الملف المرفق مشروع Android مفتوح المصدر تم تعديله، وليس APK جاهز للتثبيت.

طريقة الحصول على APK بدون تعديل الكود:

1) افتح المجلد باستخدام Android Studio مع Android SDK 36 و JDK 21.
2) شغّل BUILD_ON_WINDOWS.bat على Windows، أو:
   ./gradlew :app:testFossDebugUnitTest :app:assembleFossDebug
3) بعد نجاح البناء ستجد:
   app/build/outputs/apk/foss/debug/app-foss-debug.apk

أو يمكن رفع محتويات المشروع إلى GitHub وتشغيل:
Actions > Build SmartScan Pro APK > Run workflow
ستظهر النسخة في Artifacts عند نجاح البناء.

اقرأ SMARTSCAN_AR.md لمعرفة المميزات التي تم تعديلها وما لم يختبر بعد.
