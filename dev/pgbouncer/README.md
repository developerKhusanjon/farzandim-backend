# Lokal PgBouncer (ixtiyoriy)

Production'da DigitalOcean Managed PostgreSQL'ning **Connection Pools**
funksiyasi ishlatiladi — bu papka faqat o'sha rejimni lokal sinash uchun.

```bash
docker compose --profile pool up -d pgbouncer   # 6432-port
```

`userlist.txt` dagi parollar — V902 dev seed'idagi DEV parollar. Production
parollarini bu yerga yozmang.
