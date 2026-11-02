مشوارك — V229.1 | إصلاح خطأ فشل بناء APK

سبب العطل:
كانت شاشة عدم الاتصال الجديدة في V229 تستخدم ثلاث فئات من Android Graphics من غير استيرادها:
Canvas، Paint، RectF. وبناء عليه فشل compileDevDebugKotlin وcompilePlayDebugKotlin.

الإصلاح الوحيد:
إضافة الثلاثة أسطر التالية قرب imports أعلى MainActivity.kt:
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

لم يتغير أي منطق للحجز أو الملاحة أو معالجة انقطاع الإنترنت.

ملف الاستبدال داخل ZIP:
app/src/main/java/com/jekonix/mashwarak/MainActivity.kt

خطوات تركيب GitHub:
1) افتح هذا الملف من مشروعك الحالي.
2) إن كان عندك نفس كود V229، استبدله بمحتوى الملف الجديد. لو أضفت تعديلات بعدها، اكتف بإضافة الثلاثة imports فقط.
3) Commit changes.
4) GitHub Actions -> تشغيل بناء APK.
5) إذا نجح، نزّل النسخة واختبر: فتح من غير إنترنت، استعادة الاتصال، Google Maps، والرجوع إلى نموذج حجز.

التحقق الذي تم محليًا:
- تمت مقارنة الملف الجديد بسابقه حرفيًا: الاختلاف الوحيد هو إضافة 3 imports.
- لم يتم تشغيل Android/Gradle build في هذه البيئة، ولا توجد نتيجة بناء ناجح مؤكدة حتى الآن.
