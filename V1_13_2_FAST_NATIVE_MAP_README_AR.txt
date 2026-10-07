MASHWARAK ANDROID V1.13.2 — FAST NATIVE MAP FIX

الإصلاحات:
- إعادة استخدام منطق GPS السريع: cached location فوراً عند توفره + fresh high accuracy بالخلفية + timeout 7 ثوانٍ.
- الخريطة لا تنتظر GPS لتظهر؛ البحث وتحريك الخريطة متاحان فور تحميلها.
- دبوس المنتصف لا يظهر قبل تحميل الخريطة فعلياً.
- رسالة واضحة بعد 8 ثوانٍ إذا فشل تحميل خرائط Google.
- منع استخدام Firebase Web API key كبديل صامت لـ Maps API key، لأنه قد ينتج APK سليم لكن خريطة فارغة.

إعداد ضروري مرة واحدة قبل بناء هذا الإصدار:
1) Google Cloud Console > مشروع Mashwarak > APIs & Services > Library > Maps SDK for Android > Enable.
2) أنشئ API key لخرائط Android.
3) GitHub > Repository Settings > Secrets and variables > Actions > New repository secret.
4) الاسم: MASHWARAK_MAPS_API_KEY
5) القيمة: مفتاح Google Maps.

لا تغيير في Backend V5.3.0 ولا Web/Index في هذا الإصلاح.
Release المقترح: v1.13.2
