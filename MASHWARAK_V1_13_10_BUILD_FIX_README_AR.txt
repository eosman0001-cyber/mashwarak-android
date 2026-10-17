MASHWARAK Android V1.13.10 — إصلاح فشل GitHub Actions (OnboardingActivity.kt)

السبب:
OnboardingActivity.kt سطر 130 يستخدم ScrollView.LayoutParams وهي ليست فئة LayoutParams متاحة كعضو في ScrollView، مما سبب ثلاثة أخطاء Kotlin Unresolved reference.

التعديل الدقيق:
1) إضافة import android.widget.FrameLayout
2) تغيير سطر إضافة scroller child إلى:
scroller.addView(middle, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
استخدام WRAP_CONTENT يسمح بتمرير المحتوى عموديًا مع تكبير الخط. ScrollView.isFillViewport = true كما في المصدر السابق.

طريقة الإصلاح الأسرع في GitHub:
اذهب إلى المستودع > Code > main > app/src/main/java/com/jekonix/mashwarak/OnboardingActivity.kt
اضغط قلم Edit، وأضف import بعد أسطر الاستيراد وبدّل السطر القديم أعلاه. Commit changes ثم راقب Actions.

طريقة رفع ملف كامل بدل التعديل اليدوي:
ارفع OnboardingActivity.kt المصحح من مجلده فقط، في المسار نفسه بالضبط.

يوجد أيضًا ZIP كامل تم إصلاحه: نفس ملفات مصدر GitHub السابقة، باستثناء OnboardingActivity.kt المصحح. مجلد ROLLBACK غير مدرج في ZIP الكامل لأنه خاص بالنسخ الاحتياطية، ولا يلزم رفعه.

لم يتغير: رقم الإصدار V1.13.10، اسم التطبيق MASHWARAK، الشاشة العربية، وظائف الحجز والموقع، ملف MainActivity، Gradle، الـManifest.

التحقق: تم التأكد آليًا من وجود التصحيح داخل الملفات وفروق الكود وعدم تغيير باقي الملفات. لم يتم إجراء build كامل محليًا ببيئة Android SDK؛ نتيجة GitHub Actions هي اختبار الترجمة النهائي.
