#!/usr/bin/env python3
"""
Farzandim AI — backend smoke-test.

Uchala servisni tirik holatda tekshiradi: login, rollar, tenant izolyatsiyasi,
jurnal, uy vazifasi, ota-ona kesimi, super admin amallari.

Ishlatish (uchala servis ishga tushgan bo'lsin):

    docker compose up -d postgres valkey
    ./mvnw -pl school-backend quarkus:dev     # 8081, seed bilan
    # boshqa terminalda:
    python3 scripts/smoke-test.py

Portlarni muhit o'zgaruvchisi bilan o'zgartirish mumkin:
    SCHOOL_URL=http://localhost:8091 MOBILE_URL=... ADMIN_URL=... python3 scripts/smoke-test.py

Kirish ma'lumotlari `db/seed/V900__dev_seed.sql` dagi namunaviy hisoblar.
"""
import os
import time

SCHOOL_URL = os.environ.get("SCHOOL_URL", "http://localhost:8081") + "/api/v1"
MOBILE_URL = os.environ.get("MOBILE_URL", "http://localhost:8082") + "/api/v1"
ADMIN_URL = os.environ.get("ADMIN_URL", "http://localhost:8083") + "/api/v1"

import json, urllib.request, urllib.error

BASE = SCHOOL_URL

def call(path, token=None, school=None, method="GET", body=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token: req.add_header("Authorization", "Bearer " + token)
    if school: req.add_header("X-School-Id", school)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")

def ok(label, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + label + ((" — " + str(extra)) if extra else ""))
    return cond

results = []

# 1. Login
st, login = call("/auth/login", method="POST", body={"login": "admin.maktab", "password": "parol123"})
results.append(ok("admin login", st == 200 and "data" in login))
token = login["data"]["accessToken"]; refresh = login["data"]["refreshToken"]
school = login["data"]["user"]["schoolId"]

# 2. Noto'g'ri parol
st, bad = call("/auth/login", method="POST", body={"login": "admin.maktab", "password": "xato"})
results.append(ok("noto'g'ri parol 401", st == 401 and bad["error"]["code"] == "UNAUTHORIZED", bad.get("error", {}).get("code")))

# 3. Tokensiz so'rov
st, _ = call("/students")
results.append(ok("tokensiz 401", st == 401, st))

# 4. Ota-ona school-backend'ga kira olmasligi
st, parent = call("/auth/login", method="POST", body={"login": "otaona1", "password": "parol123"})
results.append(ok("ota-ona CRM'ga kira olmaydi", st == 403, (st, parent.get("error", {}).get("code"))))

# 5. Boshqa maktab konteksti
st, other = call("/students", token=token, school="00000000-0000-0000-0000-000000000001")
results.append(ok("begona X-School-Id 403", st == 403, (st, other.get("error", {}).get("code"))))

# 6. Gradebook
st, groups = call("/groups", token, school)
gid = groups["data"][0]["id"]
st, subjects = call("/subjects", token, school)
sid = subjects["data"][3]["id"]
st, gb = call(f"/gradebook?groupId={gid}&subjectId={sid}", token, school)
results.append(ok("gradebook", st == 200 and len(gb["data"]["students"]) == 10,
                  f"sanalar={len(gb['data']['dates'])} oquvchi={len(gb['data']['students'])}"))

# 7. Homework
st, hw = call("/homework", token, school)
results.append(ok("homework ro'yxati", st == 200 and len(hw["data"]) > 0,
                  [(h["title"], h["groupName"], h["totalStudents"], h["doneCount"]) for h in hw["data"][:2]]))

# 8. Schedule
st, sch = call(f"/schedule?groupId={gid}", token, school)
results.append(ok("jadval", st == 200 and len(sch["data"]) == 20, f"slot={len(sch['data'])}"))

# 8a. Dars vaqti "HH:MM" (docs/02-data-model.md) — Jackson standarti "08:30:00" emas
first_slot = sch["data"][0] if sch["data"] else {}
results.append(ok("dars vaqti HH:MM", len(first_slot.get("startTime", "")) == 5, first_slot.get("startTime")))
# Seed'da birinchi dars 08:30. UTC bo'lmagan mashinada (Asia/Tashkent) Hibernate
# `time` ustunini JVM mintaqasi orqali siljitib 14:30 qilardi — LocalTimeTextConverter.
results.append(ok("dars vaqti bazadagidek (mintaqa siljimagan)", first_slot.get("startTime") == "08:30",
                  first_slot.get("startTime")))

# 8b. Majburiy query parametri berilmasa — 422 va parametr nomi (500 "Ichki xatolik" emas)
st, miss = call("/gradebook", token, school)
results.append(ok("majburiy parametr yo'q — 422",
                  st == 422 and "groupId" in (miss.get("error", {}).get("fields") or {}),
                  (st, miss.get("error", {}).get("code"))))

# 8c. Fanlar o'rtachasi — umumiy SubjectScoreDto shakli (subjectName, delta)
st, avg = call(f"/schools/{school}/subject-averages", token, school)
first_avg = avg["data"][0] if st == 200 and avg["data"] else {}
results.append(ok("fanlar o'rtachasi subjectName/delta", "subjectName" in first_avg and "delta" in first_avg,
                  sorted(first_avg)))

# 9. Teachers
st, te = call("/teachers", token, school)
results.append(ok("o'qituvchilar", st == 200 and len(te["data"]) == 4,
                  [(t["lastName"], t["username"], len(t["groupIds"])) for t in te["data"][:2]]))

# 10. Baho qo'yish (admin)
st, students = call(f"/students?groupId={gid}&size=1", token, school)
stu = students["data"][0]["id"]
st, lessons = call(f"/lessons?groupId={gid}", token, school)
lesson = lessons["data"][0]["id"] if lessons["data"] else None
st, grade = call("/grades", token, school, "POST",
                 {"studentId": stu, "subjectId": sid, "lessonId": lesson, "value": 88, "maxValue": 100,
                  "type": "QUIZ", "comment": "Test bahosi"})
results.append(ok("baho qo'yish", st == 200, grade.get("error") or grade["data"]["value"]))

# 11. Validatsiya xatosi
st, invalid = call("/grades", token, school, "POST",
                   {"studentId": stu, "subjectId": sid, "value": 150, "maxValue": 100, "type": "QUIZ"})
results.append(ok("baho > maxValue rad etiladi", st == 422, (st, invalid.get("error", {}).get("code"))))

# 12. Davomat
if lesson:
    st, att = call("/attendance/bulk", token, school, "POST",
                   {"records": [{"studentId": stu, "lessonId": lesson, "status": "LATE", "minutesLate": 7}]})
    results.append(ok("davomat belgilash", st == 200, att.get("error") or att["data"][0]["status"]))

    # 12a. O'quvchi profilidagi davomat (GET /students/{id}/attendance)
    st, satt = call(f"/students/{stu}/attendance", token, school)
    marked = [a for a in (satt.get("data") or []) if a["lessonId"] == lesson]
    results.append(ok("o'quvchi davomati", st == 200 and len(marked) == 1 and marked[0]["status"] == "LATE",
                      (st, len(satt.get("data") or []))))

# 13. Bildirishnoma ota-onaga yetdimi
st, notif = call("/notifications", token, school)
results.append(ok("bildirishnoma endpoint", st == 200, f"soni={len(notif['data'])}"))

# 14. Refresh rotatsiyasi
st, r1 = call("/auth/refresh", method="POST", body={"refreshToken": refresh})
results.append(ok("refresh rotatsiyasi", st == 200 and r1["data"]["refreshToken"] != refresh))
new_refresh = r1["data"]["refreshToken"]

# 15. Eski refresh qayta ishlatilsa — reuse detection
st, r2 = call("/auth/refresh", method="POST", body={"refreshToken": refresh})
results.append(ok("eski refresh rad etiladi (reuse)", st == 401, (st, r2.get("error", {}).get("code"))))

# 16. Reuse aniqlangach yangi token ham bekor bo'ldi (butun oila)
st, r3 = call("/auth/refresh", method="POST", body={"refreshToken": new_refresh})
results.append(ok("reuse'dan keyin butun sessiya bekor", st == 401, (st, r3.get("error", {}).get("code"))))

# 17. AI o'chiq bo'lganda tushunarli xato
st, ai = call("/homework/generate", token, school, "POST",
              {"subjectId": sid, "topic": "Kasrlar", "gradeLevel": 4, "difficulty": "NORMAL"})
results.append(ok("AI o'chiq — 503", st == 503, (st, ai.get("error", {}).get("code"))))

# 18. Notfound
st, nf = call("/students/00000000-0000-0000-0000-000000000009", token, school)
results.append(ok("mavjud bo'lmagan o'quvchi 404", st == 404, (st, nf.get("error", {}).get("code"))))


print()
def make(base):
    def call(path, token=None, school=None, method="GET", body=None):
        req = urllib.request.Request(base + path, method=method)
        req.add_header("Content-Type", "application/json")
        if token: req.add_header("Authorization", "Bearer " + token)
        if school: req.add_header("X-School-Id", school)
        data = json.dumps(body).encode() if body is not None else None
        try:
            with urllib.request.urlopen(req, data, timeout=30) as r:
                return r.status, json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode() or "{}")
    return call

mobile = make(MOBILE_URL)
admin  = make(ADMIN_URL)
school_api = make(SCHOOL_URL)

def wait_for(fetch, predicate, attempts=15, delay=0.3):
    """
    SMS/email/push tranzaksiya commit bo'lgandan keyin, alohida oqimda
    yuboriladi — testda darhol tekshirish poyga bo'ladi. Shu yerda kutamiz.
    """
    result = None
    for _ in range(attempts):
        result = fetch()
        if predicate(result):
            return result
        time.sleep(delay)
    return result


def ok2(label, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + label + ((" — " + str(extra)) if extra else ""))
    results.append(bool(cond))

print("========== MOBILE-BACKEND ==========")
st, login = mobile("/auth/login", method="POST", body={"login": "otaona1", "password": "parol123"})
ok2("ota-ona login", st == 200, login.get("error"))
ptok = login["data"]["accessToken"]

st, staff = mobile("/auth/login", method="POST", body={"login": "admin.maktab", "password": "parol123"})
ok2("maktab admini mobil ilovaga kira olmaydi", st == 403, (st, staff.get("error", {}).get("code")))

st, children = mobile("/parent/children", ptok)
ok2("farzandlar ro'yxati", st == 200 and len(children["data"]) == 1,
   [(c["lastName"], c["firstName"], c["academicScore"], c["attendanceRate"], c["groupName"]) for c in children["data"]])
child = children["data"][0]["id"]

st, dash = mobile(f"/parent/children/{child}/dashboard", ptok)
ok2("dashboard", st == 200,
   dash.get("error") or f"vazifalar={len(dash['data']['tasks'])} oxirgi_dars={'bor' if dash['data']['lastLesson'] else 'yo`q'}")

st, subs = mobile(f"/students/{child}/subjects", ptok)
ok2("fanlar", st == 200 and len(subs["data"]) > 0,
   subs.get("error") or [(s["shortName"], s["score"], s["teacherName"]) for s in subs["data"][:3]])

if st == 200 and subs["data"]:
    sid = subs["data"][0]["subjectId"]
    st, det = mobile(f"/students/{child}/subjects/{sid}", ptok)
    ok2("fan tafsiloti", st == 200, det.get("error") or (det["data"]["name"], det["data"]["score"], len(det["data"]["byTopic"])))

st, prog = mobile(f"/students/{child}/progress?period=MONTH", ptok)
ok2("dinamika", st == 200, prog.get("error") or (prog["data"]["average"], len(prog["data"]["points"])))

st, ch = mobile(f"/students/{child}/character", ptok)
ok2("xarakter profili", st == 200, ch.get("error") or [(g["label"], g["score"], len(g["traits"])) for g in ch["data"]])

st, sw = mobile(f"/students/{child}/strengths", ptok)
ok2("kuchli/zaif tomonlar", st == 200, sw.get("error") or (len(sw["data"]["strengths"]), len(sw["data"]["weaknesses"])))

st, ints = mobile(f"/students/{child}/interests", ptok)
ok2("qiziqishlar", st == 200 and len(ints["data"]) == 2, [(i["label"], i["level"]) for i in ints["data"]])

st, ab = mobile(f"/students/{child}/abilities", ptok)
ok2("qobiliyatlar", st == 200, ab.get("error") or [(a["label"], a["level"]) for a in ab["data"]])

st, hw = mobile(f"/parent/children/{child}/homework?scope=all", ptok)
ok2("uy vazifalari", st == 200, hw.get("error") or [(h["title"], h["status"], bool(h["parentHint"])) for h in hw["data"][:2]])

st, recs = mobile(f"/students/{child}/recommendations", ptok)
ok2("AI tavsiyalar", st == 200, recs.get("error") or [(r["areaLabel"], r["title"], r["withParent"]) for r in recs["data"]])

st, sc = mobile("/parent/school", ptok)
ok2("maktab ma'lumoti", st == 200, sc.get("error") or sc["data"]["name"])

st, tch = mobile("/parent/teachers", ptok)
ok2("o'qituvchilar", st == 200, tch.get("error") or [(t["lastName"], t["subjectName"]) for t in tch["data"][:2]])

st, sched = mobile("/parent/schedule", ptok)
ok2("dars jadvali", st == 200 and len(sched["data"]) == 20, f"slot={len(sched.get('data', []))}")

# Begona bolaning ma'lumotini so'rash
st, others = school_api("/students?size=5", school_api("/auth/login", method="POST",
                        body={"login": "admin.maktab", "password": "parol123"})[1]["data"]["accessToken"],
                        login["data"]["user"]["schoolId"])
foreign = [s["id"] for s in others["data"] if s["id"] != child][0]
st, deny = mobile(f"/parent/children/{foreign}/dashboard", ptok)
ok2("begona farzand 404", st == 404, (st, deny.get("error", {}).get("code")))

st, dev = mobile("/parent/devices", ptok, method="POST",
                 body={"token": "fcm-test-token-123", "platform": "ANDROID", "locale": "uz"})
ok2("push token ro'yxatdan o'tdi", st == 200, dev.get("error"))

# Har ishga tushirishda boshqa raqam — OTP chastota chegarasi (soatiga 5 ta)
# aynan shu raqam bo'yicha hisoblanadi.
otp_phone = "+99890" + str(int(time.time()))[-7:]
st, otp = mobile("/auth/login/otp/request", method="POST", body={"phone": otp_phone})
ok2("OTP so'rovi (mustaqil ota-ona)", st == 200, otp.get("error") or otp["data"])

st, otp_bad = mobile("/auth/login/otp/verify", method="POST",
                     body={"phone": otp_phone, "code": "000000"})
ok2("noto'g'ri OTP kod rad etiladi", st == 422, (st, otp_bad.get("error", {}).get("code")))

print("\n========== ADMIN-BACKEND ==========")
st, alogin = admin("/auth/login", method="POST", body={"login": "superadmin", "password": "admin123"})
ok2("super admin login", st == 200, alogin.get("error"))
atok = alogin["data"]["accessToken"]

st, deny = admin("/auth/login", method="POST", body={"login": "admin.maktab", "password": "parol123"})
ok2("maktab admini admin panelga kira olmaydi", st == 403, (st, deny.get("error", {}).get("code")))

st, schools = admin("/admin/schools?q=zamonaviy", atok)
seeded = [s for s in schools.get("data", []) if s["shortName"] == "zamonaviy"]
ok2("maktablar ro'yxati", st == 200 and len(seeded) == 1,
   schools.get("error") or [(s["name"], s["plan"], s["studentCount"], s["teacherCount"]) for s in schools["data"]])
sch_id = seeded[0]["id"]

st, detail = admin(f"/admin/schools/{sch_id}", atok)
ok2("maktab kartochkasi", st == 200,
   detail.get("error") or (detail["data"]["academicYear"], len(detail["data"]["admins"]), len(detail["data"]["subscriptions"])))

st, stats = admin("/admin/stats", atok)
ok2("platforma statistikasi", st == 200, stats.get("error") or stats["data"])

st, plans = admin("/admin/plans", atok)
ok2("tariflar", st == 200 and len(plans["data"]) == 3,
   [(p["code"], p["pricePerStudentUzs"], p["aiDailyLimit"]) for p in plans["data"]])

st, flags = admin("/admin/feature-flags", atok)
ok2("feature flag'lar", st == 200 and len(flags["data"]) == 4, [f["key"] for f in flags["data"]])

st, flag = admin("/admin/feature-flags/ai.vector_search", atok, method="PATCH",
                 body={"enabled": True, "schoolId": sch_id, "schoolEnabled": True})
ok2("bayroqni o'zgartirish", st == 200, flag.get("error") or (flag["data"]["enabled"], flag["data"]["schoolOverrides"]))

# Har ishga tushirishda unikal qisqa nom — test qayta-qayta ishlashi kerak.
suffix = str(int(time.time()))[-6:]
st, newschool = admin("/admin/schools", atok, method="POST", body={
    "name": f"Sinov maktabi {suffix}", "shortName": f"sinov{suffix}", "phone": "+998 71 100 00 00",
    "email": "info@yangi.uz", "plan": "STANDARD", "subscriptionMonths": 6,
    "adminFirstName": "Aziz", "adminLastName": "Karimov", "adminPhone": f"+9989012{suffix}"})
ok2("maktab yaratish + admin hisobi", st == 200,
   newschool.get("error") or (newschool["data"]["school"]["school"]["name"],
                              newschool["data"]["adminCredentials"]["username"]))

if st == 200:
    new_id = newschool["data"]["school"]["school"]["id"]
    creds = newschool["data"]["adminCredentials"]
    st, ok_login = school_api("/auth/login", method="POST",
                              body={"login": creds["username"], "password": creds["password"]})
    ok2("yangi admin CRM'ga kira oladi", st == 200, ok_login.get("error"))

    # Bir xil qisqa nom ikki marta — CONFLICT
    st, dup = admin("/admin/schools", atok, method="POST", body={
        "name": "Takror", "shortName": f"sinov{suffix}",
        "adminFirstName": "A", "adminLastName": "B"})
    ok2("takroriy qisqa nom rad etiladi", st == 409, (st, dup.get("error", {}).get("code")))

    # Band telefon raqami — 500 emas, tushunarli 409
    st, dupphone = admin("/admin/schools", atok, method="POST", body={
        "name": "Takror telefon", "shortName": f"tel{suffix}",
        "adminFirstName": "A", "adminLastName": "B", "adminPhone": f"+9989012{suffix}"})
    ok2("band telefon 409 beradi", st == 409, (st, dupphone.get("error", {}).get("message")))

    st, susp = admin(f"/admin/schools/{new_id}/suspend", atok, method="POST")
    ok2("maktabni to'xtatish", st == 200 and susp["data"]["school"]["status"] == "SUSPENDED")

    st, blocked = school_api("/auth/login", method="POST",
                             body={"login": creds["username"], "password": creds["password"]})
    ok2("to'xtatilgan maktabga kirish yopiq", st == 403, (st, blocked.get("error", {}).get("code")))

st, jobs = admin("/admin/jobs", atok)
ok2("job monitoringi", st == 200, f"soni={len(jobs.get('data', []))}")

st, audit = admin("/admin/audit", atok)
ok2("audit jurnali", st == 200 and len(audit["data"]) > 0,
   [(a["action"], a["entityType"]) for a in audit["data"][:3]])

st, usage = admin("/admin/ai/usage", atok)
ok2("AI sarfi", st == 200, f"qator={len(usage.get('data', []))}")

st, forbidden = admin("/admin/schools", ptok)
ok2("ota-ona tokeni admin panelda ishlamaydi", st in (401, 403), st)

print("\n========== SMS VA PUSH ==========")

st, status = admin("/admin/delivery/status", atok)
ok2("kanal holati", st == 200,
    status.get("error") or (status["data"]["smsProvider"], status["data"]["pushProvider"],
                            "qurilma:", status["data"]["activeDevices"]))

# OTP so'rovi yuqorida yuborilgan — u sms_messages ga tushgan bo'lishi kerak
st, smslist = wait_for(
    lambda: admin("/admin/sms?limit=20", atok),
    lambda r: any(row["purpose"] == "OTP" for row in r[1].get("data", [])),
)
otp_rows = [r for r in smslist.get("data", []) if r["purpose"] == "OTP"]
ok2("OTP SMS hisobga tushdi", st == 200 and len(otp_rows) > 0,
    [(r["provider"], r["purpose"], r["status"], r["parts"]) for r in smslist.get("data", [])[:3]])

ok2("raqam admin panelida ham maskalanadi",
    all("***" in r["recipient"] for r in smslist.get("data", [])),
    [r["recipient"] for r in smslist.get("data", [])[:3]])

# Sinov SMS + provayder identifikatori
test_phone = "+99893" + str(int(time.time()))[-7:]
st, testsms = admin("/admin/sms/test", atok, method="POST",
                    body={"phone": test_phone, "text": "Farzandim AI: sinov."})
ok2("sinov SMS yuborildi", st == 200 and testsms["data"]["accepted"],
    testsms.get("error") or testsms["data"])
provider_id = testsms["data"].get("providerMessageId")

# Yetkazib berish hisoboti (provayder callback'i)
if provider_id:
    st, cb = call_webhook = mobile("/webhooks/sms/log", method="POST",
                                   body={"message_id": provider_id, "status": "DELIVRD"})
    ok2("yetkazib berish callback'i qo'llandi", st == 200 and cb.get("applied") == 1, (st, cb))

    st, again = admin(f"/admin/sms?limit=50", atok)
    delivered = [r for r in again.get("data", []) if r["status"] == "DELIVERED"]
    ok2("SMS holati DELIVERED ga o'tdi", len(delivered) > 0, len(delivered))

st, cb_bad = mobile("/webhooks/sms/log", method="POST",
                    body={"message_id": "begona-identifikator", "status": "DELIVRD"})
ok2("begona callback hech narsa o'zgartirmaydi", st == 200 and cb_bad.get("applied") == 0, cb_bad)

# Sutkalik chegara
limit_phone = "+99894" + str(int(time.time()))[-7:]
rejected = None
for i in range(22):
    st, r = admin("/admin/sms/test", atok, method="POST",
                  body={"phone": limit_phone, "text": f"limit sinovi {i}"})
    if st == 200 and not r["data"]["accepted"]:
        rejected = r["data"]
        break
ok2("sutkalik SMS chegarasi ishlaydi", rejected is not None and rejected.get("errorCode") == "DAILY_LIMIT",
    rejected)

# Push: qurilmasiz foydalanuvchi
st, nopush = admin(f"/admin/push/test?userId={alogin['data']['user']['id']}", atok, method="POST")
ok2("qurilmasiz foydalanuvchiga push", st == 200 and nopush["data"]["devices"] == 0, nopush.get("data"))

# Push: ota-ona yuqorida qurilma ro'yxatdan o'tkazgan
st, parent_me = mobile("/auth/me", ptok)
parent_user_id = parent_me["data"]["user"]["id"]
st, withpush = admin(f"/admin/push/test?userId={parent_user_id}", atok, method="POST")
ok2("ro'yxatdan o'tgan qurilmaga push", st == 200 and withpush["data"]["devices"] >= 1,
    withpush.get("error") or withpush["data"])

print("\n========== EMAIL ==========")

email_addr = f"sinov{int(time.time())}@farzandim-test.uz"
st, eotp = mobile("/auth/login/otp/request", method="POST", body={"email": email_addr})
ok2("email OTP so'rovi", st == 200, eotp.get("error") or eotp["data"])

st, bad_email = mobile("/auth/login/otp/request", method="POST", body={"email": "salom"})
ok2("noto'g'ri email rad etiladi", st == 422, (st, bad_email.get("error", {}).get("code")))

st, emails = wait_for(
    lambda: admin("/admin/email?limit=20", atok),
    lambda r: any(row["purpose"] == "OTP" for row in r[1].get("data", [])),
)
otp_mails = [r for r in emails.get("data", []) if r["purpose"] == "OTP"]
ok2("email OTP hisobga tushdi", st == 200 and len(otp_mails) > 0,
    [(r["provider"], r["purpose"], r["status"], r["subject"]) for r in emails.get("data", [])[:2]])

ok2("manzil admin panelida maskalanadi",
    all("***" in r["recipient"] for r in emails.get("data", [])),
    [r["recipient"] for r in emails.get("data", [])[:3]])

st, temail = admin("/admin/email/test", atok, method="POST",
                   body={"email": f"test{int(time.time())}@farzandim-test.uz"})
ok2("sinov xati yuborildi", st == 200 and temail["data"]["accepted"], temail.get("error") or temail["data"])

# To'liq B2C oqimi — faqat `otp-echo-code` yoqilgan muhitda tekshiriladi
debug_code = eotp.get("data", {}).get("debugCode")
if debug_code:
    st, verified = mobile("/auth/login/otp/verify", method="POST",
                          body={"email": email_addr, "code": debug_code,
                                "firstName": "Mustaqil", "lastName": "Ota-ona"})
    ok2("email kodi bilan kirish", st == 200 and verified["data"]["user"]["schoolId"] is None,
        verified.get("error") or (verified["data"]["user"]["firstName"], verified["data"]["user"]["schoolId"]))

    if st == 200:
        itok = verified["data"]["accessToken"]
        st, me = mobile("/auth/me", itok)
        ok2("mustaqil foydalanuvchi deb belgilandi", st == 200 and me["data"]["independent"] is True,
            me.get("data"))

        st, children = mobile("/parent/children", itok)
        ok2("mustaqil foydalanuvchida farzand yo'q", st == 200 and children["data"] == [], children.get("data"))

        st, school_denied = mobile("/parent/school", itok)
        ok2("mustaqil foydalanuvchiga maktab bo'limi yopiq", st == 403,
            (st, school_denied.get("error", {}).get("code")))

    st, reused = mobile("/auth/login/otp/verify", method="POST",
                        body={"email": email_addr, "code": debug_code})
    ok2("ishlatilgan kod qayta o'tmaydi", st == 422, (st, reused.get("error", {}).get("code")))
else:
    print("SKIP email bilan to'liq kirish — farzandim.auth.otp-echo-code o'chiq")

st, status2 = admin("/admin/delivery/status", atok)
ok2("statistika yangilandi", st == 200 and status2["data"]["smsSentToday"] > 0
    and status2["data"]["emailSentToday"] > 0,
    (status2["data"]["smsProvider"], status2["data"]["smsSentToday"],
     status2["data"]["emailProvider"], status2["data"]["emailSentToday"],
     status2["data"]["activeDevices"]))


print("\n========== MAKTABNI ULASH (V9) ==========")
import base64

def token_groups(tok):
    part = tok.split(".")[1]
    part += "=" * (-len(part) % 4)
    return json.loads(base64.urlsafe_b64decode(part)).get("groups", [])

onb = str(int(time.time()))[-6:]
st, created = admin("/admin/schools", atok, method="POST", body={
    "name": f"Ulash sinovi {onb}", "shortName": f"ulash{onb}", "legalName": "Sinov MChJ",
    "tin": "4" + onb + "01", "plan": "BASIC", "subscriptionMonths": 3, "contractStudents": 100,
    "discountPercent": 10,
    "admins": [
        {"firstName": "Bosh", "lastName": "Admin" + onb, "phone": f"+9989051{onb}", "level": "SUPER"},
        {"firstName": "Oddiy", "lastName": "Admin" + onb, "level": "REGULAR"},
    ]})
ok2("maktab + 2 admin (superadmin va oddiy)", st == 200 and len(created.get("data", {}).get("credentials", [])) == 2,
    created.get("error") or [(c["username"], c["level"]) for c in created["data"]["credentials"]])

if st == 200:
    detail = created["data"]["school"]
    onb_id = detail["school"]["id"]
    sub = detail["subscriptions"][0]
    # BASIC: max(600 000 × 3, 12 000 × 100 × 3) × 0.9
    ok2("narx shartnomadagi o'quvchilar va chegirma bo'yicha", sub["amountUzs"] == 3240000,
        (sub["amountUzs"], sub["studentCount"], sub["discountPercent"]))
    ok2("ishga tushirish ro'yxati 0/9", detail["school"]["onboardingDone"] == 0 and len(detail["onboarding"]) == 9,
        detail["school"]["onboardingDone"])

    st, dup_tin = admin("/admin/schools", atok, method="POST", body={
        "name": "Takror STIR", "shortName": f"stir{onb}", "tin": "4" + onb + "01",
        "admins": [{"firstName": "A", "lastName": "B"}]})
    ok2("takroriy STIR rad etiladi", st == 409, (st, dup_tin.get("error", {}).get("message")))

    st, no_admin = admin("/admin/schools", atok, method="POST", body={"name": "Adminsiz", "shortName": f"noadm{onb}"})
    ok2("adminsiz maktab yaratilmaydi", st == 422, (st, no_admin.get("error", {}).get("code")))

    sup_c, reg_c = created["data"]["credentials"]
    st, sup_login = school_api("/auth/login", method="POST", body={"login": sup_c["username"], "password": sup_c["password"]})
    st2, reg_login = school_api("/auth/login", method="POST", body={"login": reg_c["username"], "password": reg_c["password"]})
    ok2("ikkala admin CRM'ga kiradi", st == 200 and st2 == 200, (st, st2))
    sup_tok = sup_login["data"]["accessToken"]
    reg_tok = reg_login["data"]["accessToken"]
    ok2("superadmin tokenida SCHOOL_SUPER_ADMIN guruhi", "SCHOOL_SUPER_ADMIN" in token_groups(sup_tok)
        and "SCHOOL_SUPER_ADMIN" not in token_groups(reg_tok), (token_groups(sup_tok), token_groups(reg_tok)))

    st, _ = school_api("/school-admins", method="POST", token=reg_tok, body={"firstName": "X", "lastName": "Y"})
    ok2("oddiy admin admin qo'sha olmaydi", st == 403, st)
    st, added = school_api("/school-admins", method="POST", token=sup_tok, body={"firstName": "Yangi", "lastName": "Admin" + onb})
    ok2("superadmin admin qo'shadi", st == 200 and added["data"]["password"], added.get("error"))

    st, admins_list = school_api("/school-admins", token=sup_tok)
    me_row = [a for a in admins_list.get("data", []) if a["isMe"]]
    st, self_block = school_api(f"/school-admins/{me_row[0]['id']}/block", method="POST", token=sup_tok)
    ok2("superadmin o'zini bloklay olmaydi", st == 422, (st, self_block.get("error", {}).get("message")))

    # BASIC tarif — AI yopiq (ilgari ai_daily_limit = 0 "cheksiz" deb o'tkazib yuborilardi)
    st, conv = school_api("/ai/conversations", method="POST", token=sup_tok, body={"title": "sinov"})
    st, ai_denied = school_api(f"/ai/conversations/{conv['data']['id']}/messages", method="POST", token=sup_tok,
                               body={"content": "salom"})
    ok2("BASIC tarifda AI yopiq", st == 403 and ai_denied["error"]["code"] == "FEATURE_NOT_AVAILABLE",
        (st, ai_denied.get("error")))

    st, overview = admin(f"/admin/schools/{onb_id}/overview", atok)
    ok2("maktab statistikasi (overview)", st == 200 and overview["data"]["admins"] == 3 and len(overview["data"]["daily"]) == 30,
        overview.get("error") or overview["data"]["admins"])

    st, students_view = admin(f"/admin/schools/{onb_id}/students", atok)
    st, viewed = admin(f"/admin/audit?schoolId={onb_id}&action=SCHOOL_DATA_VIEWED", atok)
    ok2("shaxsiy ma'lumotni ko'rish auditga yoziladi", st == 200 and viewed["meta"]["total"] >= 1, viewed.get("meta"))

    # Obuna tugashi refresh'da ham tekshiriladi
    sup_refresh = sup_login["data"]["refreshToken"]
    st, cancelled = admin(f"/admin/subscriptions/{sub['id']}/cancel", atok, method="POST", body={"reason": "smoke"})
    ok2("obunani bekor qilish", st == 200 and cancelled["data"]["status"] == "CANCELLED", cancelled.get("error"))
    st, refreshed = school_api("/auth/refresh", method="POST", body={"refreshToken": sup_refresh})
    ok2("obuna tugagach refresh ham yopiladi", st == 402 and refreshed["error"]["code"] == "SUBSCRIPTION_EXPIRED",
        (st, refreshed.get("error")))

    st, renewed = admin(f"/admin/schools/{onb_id}/subscriptions", atok, method="POST",
                        body={"planCode": "STANDARD", "months": 1, "paymentStatus": "PAID", "paymentMethod": "bank"})
    ok2("yangi obuna (to'langan)", st == 200 and renewed["data"]["paymentStatus"] == "PAID"
        and renewed["data"]["effectiveStatus"] == "ACTIVE", renewed.get("error"))

st, alerts = admin("/admin/stats/alerts", atok)
ok2("ogohlantirishlar ro'yxati", st == 200 and isinstance(alerts["data"], list), alerts.get("error"))
st, metrics = admin("/admin/stats/schools", atok)
ok2("maktablarni taqqoslash", st == 200 and len(metrics["data"]) >= 1, metrics.get("error"))
st, trends = admin("/admin/stats/trends?months=6", atok)
ok2("oylik trendlar", st == 200 and len(trends["data"]) == 6, trends.get("error"))
st, billing = admin("/admin/subscriptions/summary", atok)
ok2("to'lovlar xulosasi", st == 200 and "unpaidUzs" in billing["data"], billing.get("error"))
st, team = admin("/admin/team", atok)
ok2("platforma jamoasi", st == 200 and any(u["username"] == "superadmin" for u in team["data"]), team.get("error"))


failed = len(results) - sum(results)
print(f"\n{sum(results)}/{len(results)} test o'tdi" + (f" — {failed} ta yiqildi" if failed else ""))
raise SystemExit(1 if failed else 0)
