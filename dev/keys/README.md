# Dev JWT kalitlari

Bu yerdagi kalitlar **faqat lokal ishlab chiqish uchun**. Ular git'da ochiq
turadi va hech qanday sir emas.

Produksiyada kalitlar muhit o'zgaruvchisi orqali beriladi va bu papkadagi
fayllar umuman ishlatilmaydi:

```
JWT_PRIVATE_KEY_LOCATION=/run/secrets/jwt-private.pem
JWT_PUBLIC_KEY_LOCATION=/run/secrets/jwt-public.pem
```

Yangi juftlik yaratish:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
openssl rsa -pubout -in private.pem -out public.pem
```

Uchala servis **bir xil** ochiq kalitni ishlatadi (token bitta joyda beriladi,
hammasida tekshiriladi), lekin har biri o'z `aud` qiymatini talab qiladi —
shuning uchun ota-ona tokeni admin paneliga o'tmaydi.
