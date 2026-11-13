# Farzandim AI — backend

Uchta mustaqil deploy qilinadigan servis + bitta umumiy kutubxona.
Quarkus (Kotlin), PostgreSQL, Valkey, DigitalOcean Spaces.

```
farzandim-backend/
├── shared/           umumiy kutubxona: entity, migratsiya, xavfsizlik, storage, AI
├── school-backend/   8081 — Web CRM (maktab admini + o'qituvchi)
├── mobile-backend/   8082 — ota-ona ilovasi
└── admin-backend/    8083 — super admin paneli (front: ../farzandim-admin, 5190)
```

> Nega uchta servis, lekin bitta jadval bazasi: ma'lumot bitta — o'qituvchi
> qo'ygan baho ota-onaga darhol ko'rinishi kerak. Ajratilgan narsa —
> **yuklama, relizlar va xavfsizlik chegarasi**. Ota-ona ilovasi ertalab
> portlab ketsa, maktab CRM'i ishlashda davom etadi; admin panelining
> endpointlari esa umuman boshqa konteynerda turadi.

---

## Tez boshlash

```bash
cd farzandim-backend
cp .env.example .env

# 1. Infratuzilma (PostgreSQL + pgvector, Valkey, MinIO)
docker compose up -d postgres valkey

# 2. Maktab servisi — dev'da sxemani o'zi migratsiya qiladi, dev seed'ni
#    va servis rollarini (farzandim_school/_mobile/_admin) yaratadi
./mvnw -pl school-backend quarkus:dev

# 3. Boshqa terminalda (school-backend'dan KEYIN — ular migratsiya
#    holatini tekshiradi va migratsiyasiz bazada ishga tushmaydi)
./mvnw -pl mobile-backend quarkus:dev
./mvnw -pl admin-backend quarkus:dev
```

Har servis o'z cheklangan DB roli bilan ulanadi (dev parollar `%dev`
profilida) — ruxsat etilmagan jadvalga tegadigan kod dev'dayoq
"permission denied" beradi, production'da emas.

Port 5432 yoki 6379 band bo'lsa — `.env` dagi `POSTGRES_PORT` / `VALKEY_PORT` ni
o'zgartiring va `DB_URL` / `VALKEY_URL` ni moslang. Masalan (5442/6389):

```bash
POSTGRES_PORT=5442 VALKEY_PORT=6389 docker compose up -d postgres valkey
export DB_URL='jdbc:postgresql://localhost:5442/farzandim?stringtype=unspecified'
export VALKEY_URL='redis://localhost:6389'
./mvnw -pl school-backend quarkus:dev -Ddebug=false   # debug porti 5005 — uchala servis
                                                       # birga ishlashi uchun o'chiriladi
```

`shared` modul o'zgarsa — `./mvnw -pl shared install -DskipTests` va servislarni qayta
ishga tushiring (dev rejim uni qayta yuklay olmaydi).

Frontendlar: web CRM (5180) `/api` ni 8081 ga proksi qiladi, mobil ilova
`.env.development` orqali 8082 ga ulanadi, admin paneli (5190) — 8083 ga.

### Namunaviy hisoblar (dev seed)

| Kim | Login | Parol | Servis |
|---|---|---|---|
| Super admin | `superadmin` | `admin123` | admin-backend · 8083 |
| Maktab admini | `admin.maktab` | `parol123` | school-backend · 8081 |
| O'qituvchi | `oqituvchi1` … `oqituvchi4` | `parol123` | school-backend · 8081 |
| Ota-ona | `otaona1` … `otaona30` | `parol123` | mobile-backend · 8082 |

Seed 1 maktab, 3 guruh, 30 o'quvchi, 6 fan, ~250 dars, ~900 baho va
~2400 davomat yozuvini yaratadi — frontend real ma'lumotda ishlashi uchun.

`V903`/`V904` dev seed'lari fanlarga ikonka beradi va jadvaldagi o'qituvchini
guruh+fanga biriktirilgan o'qituvchiga moslaydi (aks holda o'qituvchi o'z darsiga
baho qo'ya olmas edi).

`V901` seed super admin paneli uchun yana 5 ta maktab qo'shadi — har biri
boshqa holatda (hammasining paroli `parol123`):

| Maktab | Tarif | Holat | Admin loginlari |
|---|---|---|---|
| Nurli kelajak | PREMIUM | to'liq ishlayapti, AI faol | `nurli.admin` (superadmin), `nurli.zavuch` |
| Ilm ziyo | STANDARD | obuna to'lanmagan | `ilmziyo.admin`, `ilmziyo.zavuch` |
| Kelajak akademiyasi | BASIC | ulangan, lekin ishga tushmagan | `kelajak.admin` |
| Yangi avlod | BASIC | obuna 5 kun oldin tugagan (kira olmaydi) | `yangiavlod.admin` |
| Oltin qalam | STANDARD | to'xtatilgan (sababi bilan) | `oltinqalam.admin` |

### Tekshirish

```bash
python3 scripts/smoke-test.py
```

103 ta tekshiruv: login va rollar, tenant izolyatsiyasi, refresh rotatsiyasi va
qayta ishlatishni aniqlash, jurnal, uy vazifasi, ota-ona kesimi, super admin
amallari, SMS / email / push kanallari, mustaqil ota-ona (B2C) oqimi,
xato kodlari, maktabni ulash (bir nechta admin, superadmin huquqlari),
tarif cheklovlari (BASIC'da AI yopiq), obuna tugaganda refresh yopilishi,
dars vaqti `HH:MM` va vaqt mintaqasida siljimasligi, majburiy parametr yo'q bo'lsa
422 (500 emas), fanlar o'rtachasi, o'quvchi davomati.

### Hujjat va kuzatuv

| Yo'l | Nima |
|---|---|
| `/openapi` · `/docs` | OpenAPI va Swagger UI |
| `/q/health` | tiriklik va tayyorlik |
| `/metrics` | Prometheus |

---

## SMS, email va push kanallari

Uchalasi ham provayder tanlanadigan: kalitsiz muhitda servis ishlashda
davom etadi, xabar esa faqat logga yoziladi.

```
farzandim.sms.provider   = log | eskiz | playmobile
farzandim.email.provider = log | smtp
farzandim.push.provider  = log | fcm
```

**SMS — eskiz.uz**

```bash
SMS_PROVIDER=eskiz
ESKIZ_EMAIL=siz@maktab.uz
ESKIZ_PASSWORD=...
SMS_FROM=4546            # operator tasdiqlagan jo'natuvchi nomi
```

Token (30 kun) avtomatik olinadi va Valkey'da saqlanadi — bir nechta pod
bitta tokendan foydalanadi. 401 kelsa bir marta qayta login qilinadi.

**SMS — playmobile.uz**

```bash
SMS_PROVIDER=playmobile
PLAYMOBILE_LOGIN=...
PLAYMOBILE_PASSWORD=...
```

**Email — SMTP**

```bash
EMAIL_PROVIDER=smtp
MAIL_HOST=smtp.zoho.eu        # yoki Yandex 360, SendGrid, Amazon SES...
MAIL_PORT=587
MAIL_USERNAME=no-reply@farzandim.uz
MAIL_PASSWORD=...
MAIL_START_TLS=REQUIRED
MAIL_MOCK=false               # muhim: true bo'lsa xat konsolga chiqadi, jo'natilmaydi
EMAIL_FROM=Farzandim AI <no-reply@farzandim.uz>
```

Email ikki joyda ishlatiladi: **OTP** (mustaqil ota-ona telefon o'rniga
email bilan kiradi) va **hisob ma'lumotlari** (`SMS_SEND_CREDENTIALS=true`
bo'lsa, telefon bilan birga email'ga ham yuboriladi).

Xatlar HTML va oddiy matn ko'rinishida birga ketadi — faqat HTML bo'lgan xat
ko'pincha spam deb belgilanadi. Tashqi rasm va shrift yuklanmaydi: pochta
mijozi ularni bloklaydi va yuklanish fakti "xat ochildi" signalini tashqariga
chiqaradi.

**Push — Firebase Cloud Messaging HTTP v1**

```bash
PUSH_PROVIDER=fcm
FCM_CREDENTIALS_FILE=/run/secrets/fcm-service-account.json
# yoki alohida o'zgaruvchilar bilan:
# FCM_PROJECT_ID=... FCM_CLIENT_EMAIL=... FCM_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\n..."
FCM_ANDROID_CHANNEL=farzandim-default   # RN tomonidagi kanal id bilan bir xil
```

Service account Firebase konsolida: *Project settings → Service accounts →
Generate new private key*. Kalit JSON'dagi `private_key` maydonida `\n`
ketma-ketligi bilan keladi — kod uni o'zi ajratadi.

**Kuzatuv va tekshirish** (admin-backend):

| Endpoint | Nima |
|---|---|
| `GET /admin/delivery/status` | qaysi provayder faol, bugungi SMS va email, faol qurilmalar |
| `GET /admin/sms` | oxirgi SMS'lar (raqam maskalangan) |
| `GET /admin/email` | oxirgi xatlar (manzil maskalangan) |
| `POST /admin/sms/test` | SMS shlyuzini tekshirish — haqiqiy SMS ketadi |
| `POST /admin/email/test` | SMTP ulanishini tekshirish — haqiqiy xat ketadi |
| `POST /admin/push/test?userId=` | foydalanuvchining qurilmalariga sinov push |
| `POST /webhooks/sms/{provider}` | provayderning yetkazib berish hisoboti |

Yetkazib berish hisoboti uchun provayder kabinetida callback URL'ini
ko'rsating:

```
SMS_CALLBACK_URL=https://api.farzandim.uz/api/v1/webhooks/sms/eskiz?token=<maxfiy>
SMS_CALLBACK_TOKEN=<maxfiy>
```

**Login/parolni SMS bilan yuborish** standart holatda o'chiq
(`SMS_SEND_CREDENTIALS=false`): parol SMS'da ochiq ketadi va telefonda
saqlanib qoladi, maktab uni qog'ozda berishi ko'pincha xavfsizroq.
Yoqilganda ham parol `sms_messages` / `email_messages` jadvaliga yozilmaydi —
u yerda faqat "kimga, qachon, qaysi maqsadda" qoladi.

---

## Production: baza

Har muhit uchun **bir marta**:

```bash
# Servis rollari (DBA yoki DigitalOcean doadmin nomidan)
DB_ADMIN_URL="postgresql://doadmin:...@host:25060/farzandim?sslmode=require" \
SCHOOL_DB_PASSWORD=... MOBILE_DB_PASSWORD=... ADMIN_DB_PASSWORD=... \
scripts/db-roles.sh
```

Har **relizda** — servislardan oldin (App Platform'da PRE_DEPLOY job):

```bash
DB_URL="jdbc:postgresql://host:25060/farzandim?sslmode=require&stringtype=unspecified" \
DB_MIGRATION_USER=... DB_MIGRATION_PASSWORD=... \
scripts/migrate.sh
```

Keyin uchala servis o'z `DB_USER` / `DB_PASSWORD` bilan (`.env.example` ga
qarang). Ular faqat tekshiradi: migratsiya qadami o'tkazib yuborilsa —
ishga tushmaydi.

Ulanishlar: standart hovuzlar 8 + 8 + 3 — DigitalOcean'ning eng kichik
tarifiga (~22) sig'adi. Ko'proq kerak bo'lsa — DigitalOcean **Connection
Pools** (PgBouncer, transaction rejimi) va DB_URL'da `prepareThreshold=0`.
Lokal sinash: `docker compose --profile pool up -d pgbouncer` (6432).
Batafsil: [`docs/07-backend-architecture.md`](../docs/07-backend-architecture.md), 8.5-bo'lim.

---

## Frontendni ulash

Ikkala frontend ham mock rejimda ishlaydi; ularni jonli backendga o'tkazish
uchun faqat bitta o'zgaruvchi kerak — kod tegilmaydi.

**Web CRM** (`farzandim-web/.env.local`):

```
VITE_API_URL=http://localhost:8081/api/v1
```

MSW'ni o'chirishni unutmang (`src/main.tsx` dagi mock ishga tushirish sharti).

**Mobil ilova** (`farzandim-mobile/.env`):

```
EXPO_PUBLIC_API_URL=http://localhost:8082/api/v1
EXPO_PUBLIC_USE_MOCK=false
```

Haqiqiy qurilmada `localhost` ishlamaydi — kompyuterning lokal IP'sini
yozing (`http://192.168.x.x:8082/api/v1`).

Backend javob qobig'i (`{ data, meta }` / `{ error }`) va maydon nomlari
mock bilan bir xil — `docs/03-api-contract.md` va
`farzandim-web/src/entities/types.ts` asos qilib olingan.

---

## Arxitektura qarorlari

To'liq tavsif: [`../docs/07-backend-architecture.md`](../docs/07-backend-architecture.md).

**Qisqacha:**

- **Sxema egasi — `school-backend`.** Faqat u `migrate-at-start` qiladi.
  Uchta servis bitta bazaga bir vaqtda migratsiya qilsa poyga chiqadi.
- **Tenant chegarasi repository qatlamida.** `TenantRepository.findInSchool()`
  har bir o'qishga `school_id` shartini qo'shadi. Kontrollerdagi tekshiruv
  yetarli emas — bitta unutilgan endpoint butun sizib chiqish demak.
- **JWT'da faqat identifikatsiya.** `sub`, `tenant_id`, `role`, `sid`.
  CRM ma'lumoti (ism, telefon, bolalar ro'yxati) tokenga solinmaydi.
- **Refresh rotatsiyasi + reuse detection.** Ishlatilgan token qayta kelsa,
  butun sessiya oilasi bekor qilinadi (alohida tranzaksiyada — aks holda
  401 istisnosi bekor qilishni ham qaytarib yuborardi).
- **Oddiy qidiruv `pg_trgm`, semantik qidiruv `pgvector`** — ikkalasi
  ataylab alohida yo'l.
- **Valkey'da yo'qolsa bo'ladigan narsa.** Kesh, limit, lock. Valkey
  uzilsa servis sekinlashadi, lekin ishlashda davom etadi.
- **AI ishi `ai_jobs` jadvalida.** Interaktiv endpointlar ishni darhol
  bajaradi, lekin baribir job yozuvi qoladi: kim, qancha token, qancha pul.
- **Fayl baytlari backend orqali o'tmaydi.** Presigned PUT → brauzer →
  Spaces; tasdiqlash `HEAD` bilan.
- **SMS, email va push tranzaksiyadan keyin ketadi.** `AFTER_SUCCESS`
  hodisasi: baho yozilmasa — "yangi baho" push'i ham ketmaydi. Yuborishning
  o'zi alohida oqimda, so'rovni ushlab turmaydi.

---

## Native build va konteyner

```bash
# JVM tasvir (~200 MB, start ~1.5 s)
./mvnw -pl school-backend package
docker build -f school-backend/src/main/docker/Dockerfile.jvm \
  -t farzandim/school-backend:jvm school-backend

# Native tasvir (~50 MB, start ~0.02 s, RAM ~64 MB)
./mvnw -pl school-backend package -Dnative
docker build -f school-backend/src/main/docker/Dockerfile.native \
  -t farzandim/school-backend:native school-backend
```

Native build GraalVM o'rniga konteynerda ketadi
(`quarkus.native.container-build=true`) — mahalliy mashinaga GraalVM
o'rnatish shart emas, lekin build 3–5 daqiqa oladi va ~6 GB RAM talab qiladi.

---

## Muhit o'zgaruvchilari

Hammasi [`.env.example`](.env.example) da izoh bilan. Eng muhimlari:

| O'zgaruvchi | Nima uchun |
|---|---|
| `DB_URL` | **`?stringtype=unspecified` majburiy** — `jsonb` ustunlar String sifatida yoziladi |
| `VALKEY_URL` | kesh va limitlar; yo'q bo'lsa servis ishlaydi, sekinroq |
| `JWT_PRIVATE_KEY_LOCATION` | produksiyada secret fayl; lokalda `dev-keys/` |
| `SPACES_KEY` / `SPACES_SECRET` | bo'sh bo'lsa fayl yuklash o'chiq (`STORAGE_ERROR`) |
| `ANTHROPIC_API_KEY` | bo'sh bo'lsa AI endpointlari `AI_UNAVAILABLE` qaytaradi |
| `AI_WORKER` | faqat bitta servisda `true` bo'lsin (standart: school-backend) |

---

## Nima qilinmagan

Ochiq va bilib qoldirilgan joylar:

- **Hisobot eksporti (PDF/XLSX)** — `/reports/export` yozilmagan.
- **Email yetkazib berish hisoboti** — SMS'da provayder callback'i bor,
  email'da esa yo'q: bounce/complaint webhook'lari provayderga qarab
  butunlay boshqacha (SES SNS, SendGrid Event Webhook).
- **Testcontainers bilan integratsion testlar** — hozircha SigV4 unit testi
  va `scripts/smoke-test.py` bor.
- **Embedding indeksatsiyasi** — job handler va pgvector qidiruvi tayyor,
  lekin standart holatda o'chiq (`EMBEDDING_ENABLED=false`).
