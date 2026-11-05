# SimplySIP Phone — แผนพัฒนา v0.1

วันที่: 5 พ.ย. 2026
อ้างอิง: [Cloud_IP_PBX_Android_Softphone_Technical_Specification_v1.0.docx](Cloud_IP_PBX_Android_Softphone_Technical_Specification_v1.0.docx) (เรียกว่า "สเปก")
ระบบ backend: **sip-manager** (`D:\Coding\laravel\soap\sip-manager`) + **SIP Deployer** (เจ้าของ `opensips.cfg` และการติดตั้ง node)

---

## 1. สถานะปัจจุบัน

### 1.1 โปรเจกต์ Android

- Template Compose เปล่า, module เดียว `:app`, ยังไม่มี commit
- `namespace`/`applicationId` = `com.example.simplysipphone` — ต้องเปลี่ยนก่อนผูก Firebase (google-services.json ผูกกับ package name)
- Dependencies ใน `gradle/libs.versions.toml` เก่า (core-ktx 1.10.1, lifecycle 2.6.1, activity-compose 1.8.0); ยังไม่มี Hilt, Coroutines, Retrofit/OkHttp, Room, NDK, PJSIP
- `minSdk = 26` (ตรงกับขั้นต่ำของ Core-Telecom; ยืนยันหลัง spike), Java 11

### 1.2 sip-manager / SIP Deployer เทียบกับสเปก

| เรื่อง | สเปก | sip-manager ตอนนี้ | ผลต่อแผน |
|---|---|---|---|
| Transport | SIP/TLS 5061 + SRTP | UDP/TCP 5060 เท่านั้น (ไม่มี TLS/WSS) | เพิ่ม TLS ใน SIP Deployer |
| Credential | แยกต่ออุปกรณ์ | 1 credential ต่อ extension (`sip_credentials.extension_id` unique) | ต้องตัดสินใจ (ดู §5 ข้อ 1) |
| User ↔ Extension | user login แล้วได้ extension ของตัวเอง | ไม่มีการผูก user กับ extension (มีแค่ tenant membership owner/admin/member) | เพิ่ม `user_extensions` |
| API auth | token ของผู้ใช้ปลายทาง | Sanctum token ผูก tenant, ability `read`/`write` สำหรับงานแอดมิน/integration | เพิ่ม `/api/v1/softphone/*` แยก |
| Push | RFC 8599 + FCM | `opensips.cfg` ไม่มี push | งานใหม่ทั้ง SIP Deployer และ sip-manager |
| Media | ยังไม่ตัดสินใจ | **rtpengine anchor ทุกสาย** (`ICE=remove`, พอร์ต 10000–20000) | ตัดสินแล้ว — ไม่ต้องทำ ICE/STUN ฝั่งแอป |
| Domain | `sip.example.com` | โดเมนต่อ tenant `<slug>.<SIP_TENANT_DOMAIN_SUFFIX>` (D6), ทุกสายผ่าน Asterisk | SIP domain/realm = tenant domain, outbound proxy = node |
| DTMF | RFC 4733 | `dtmf_mode = rfc4733` ผ่าน rtpengine ทดสอบแล้ว | ตรงกัน |
| CDR | `/call-history` | มี `call_records` (sync จาก Asterisk) แต่ยังไม่อยู่ใน API | เปิดเป็น endpoint |
| Feature codes | — | `*43` echo, `*97` voicemail, `*8` pickup, `8<code><ext>` ข้าม tenant | ใช้เป็น test case ได้ทันที |

หมายเหตุ: มีการทดสอบ MizuDroid (Android) register + `*43` echo ผ่าน UDP + rtpengine สำเร็จแล้ว — เส้นทางสื่อสารพื้นฐานกับมือถือใช้ได้

---

## 2. หลักการออกแบบฝั่ง Android

- Kotlin + Jetpack Compose, Clean Architecture + MVVM, Hilt, Coroutines/Flow, Retrofit/OkHttp, Room (เฉพาะข้อมูลไม่ลับ)
- UI/feature ไม่เรียก PJSUA2 ตรง — ผ่าน `SipEngine` interface (สเปก §4.3)
- เรียก PJSUA2 ทั้งหมดบน **single-thread dispatcher** เดียว (`libRegisterThread`), เก็บ reference Account/Call ไว้ป้องกัน GC, แปลง callback เป็น `Flow<SipEvent>`
- `CallStateMachine` กลางเป็น sealed class ตามสเปก §4.4 — ไม่ใช้ Boolean หลายตัว, event ซ้ำ/ผิดลำดับต้อง idempotent
- Secret เก็บด้วย Android Keystore (AES-GCM) เท่านั้น; ไม่ log credential/token
- SIP engine lifecycle แยกจาก Activity

### 2.1 โครงสร้าง module เริ่มต้น

เริ่มเล็ก แล้วแยกเพิ่มเมื่อโค้ดโต (สเปก §4.2 เป็นเป้าหมายระยะยาว):

```
:app
:core            common, network, security, database
:sip:pjsip-native  prebuilt .so + PJSUA2 Java bindings (AAR)
:sip:engine      SipEngine, CallStateMachine, account/registration/call/media
:data            auth, provisioning, contacts, callhistory
:feature:call    dialer, incoming, ongoing
```

---

## 3. Roadmap

### Phase 0 — เตรียมงาน (สัปดาห์ 0, 3–5 วัน)

1. จัดโปรเจกต์: เปลี่ยน package, อัปเดต dependencies, Java 17, เพิ่ม Hilt/Coroutines/Retrofit/OkHttp/Room/kotlinx-serialization, CI (GitHub Actions)
2. เตรียม node lab: tenant + extension ทดสอบ 2–3 ตัวผ่าน sip-manager
3. ตัดสินใจเรื่องที่ block (§5)

### Phase 1 — SIP Foundation (สัปดาห์ 1–2)

- Spike PJSIP native build: NDK (pin เวอร์ชัน), OpenSSL (เตรียม TLS/SRTP), SWIG → PJSUA2 bindings; ABI `arm64-v8a` + `x86_64`; **รองรับ 16 KB page size**
- `:sip:pjsip-native` เป็น prebuilt AAR (ไม่ build PJSIP ใหม่ทุกครั้ง)
- `:sip:engine` — `SipEngine` + register/unregister
- หน้าจอ Debug (เฉพาะ debug build) กรอก SIP config เอง จาก credential ที่ sip-manager แสดงครั้งเดียว
- ทดสอบกับ node lab ผ่าน **TCP 5060** ก่อน (ไม่รอ TLS)
- **ขนาน (SIP Deployer):** เพิ่ม `proto_tls` 5061 + cert wildcard `*.<suffix>`
- **ขนาน (spike):** ทดลอง push ของ registrar OpenSIPS 3.6 (RFC 8599) ตั้งแต่ตอนนี้ — เป็นความเสี่ยงสูงสุด
- **Exit:** register กับ node lab ได้เสถียรบนเครื่องจริง

### Phase 2 — Voice Calling (สัปดาห์ 3–4)

- `CallStateMachine` + unit tests
- โทรออก/รับสาย, G.711 (A-law/µ-law), DTMF RFC 4733, mute, hold (re-INVITE)
- Audio routing: earpiece/speaker/wired (Bluetooth → Phase 5)
- UI: Dialer, Ongoing call
- ทดสอบกับ `*43`, `*97`, ring group, pickup `*8`, โทรข้าม tenant `8<code><ext>`
- SRTP: ตกลงรูปแบบกับ rtpengine (SDES RTP/SAVP ขาโทรศัพท์ → RTP ขา Asterisk)
- **Exit:** เสียงสองทางผ่าน Asterisk (AT-04, AT-06, AT-08)

### Phase 3 — sip-manager Softphone API (สัปดาห์ 5–6)

ฝั่ง sip-manager (ตามแบบโค้ดเดิม: modular monolith, ULID tenant, outbox, audit, Idempotency-Key):

- Module ใหม่ `app/Modules/Softphone/`: `softphone_devices`, `device_push_tokens`, `softphone_events` (สเปก §8)
- `user_extensions` + หน้าจอให้ tenant admin กำหนดผู้ใช้ของ extension
- Device credential ตามที่ตัดสินใน §5 ข้อ 1 (เดิมแผน sip-manager มี `POST extensions/{id}/devices` ค้างอยู่)
- `/api/v1/softphone/*` ตามสเปก §7.2 ด้วย token ของผู้ใช้ (tokenable = user) แยกจาก tenant API token
- การเปลี่ยนที่ไปถึง PBX ใช้ outbox → queue `provisioning` → `202` + `operation_id`; Android รอ `applied` ก่อน register
- `GET /softphone/call-history` จาก `call_records`
- Tests: tenant isolation ทุก endpoint (AT-12), credential ไม่หลุดใน log/response ซ้ำ (AT-02)

ฝั่ง Android:

- `:data` auth/provisioning (Retrofit), `SecretStore` (Keystore), `device_installation_id` ใน DataStore
- OkHttp interceptor: token refresh + `correlation_id`
- Fake repository ตั้งแต่ Phase 1 เพื่อไม่ต้องรอ backend
- **Exit:** login แล้ว register อัตโนมัติ ไม่ต้องกรอก SIP เอง (AT-01..03)

### Phase 4 — Background / Push (สัปดาห์ 7–8) ⚠️

- **SIP Deployer:** เปิด registrar push (`pn_*`, RFC 8599) และ event สำหรับ contact ที่หลับ เมื่อมีสายเข้า
- **sip-manager:** consumer รับ event → queue `events` → FCM HTTP v1 (validate, dedupe, TTL, rate limit, บันทึกผล)
- **Android:** `FcmService` (high-priority data message, ตรวจ expiry), Core-Telecom `CallsManager.addCall`, `MANAGE_OWN_CALLS`, foreground service type `phoneCall`, `CallStyle` notification, resume engine + re-register, contact URI มี `pn-provider`/`pn-prid`/`pn-param`
- Payload มีแค่ opaque call reference + expiry — ห้ามมี credential/token
- **Exit:** AT-09 ในสถานะ background ที่ตกลงกัน

### Phase 5 — Production Features (สัปดาห์ 9–10)

Transfer, contacts (`GET directory` เดิม / `/softphone/contacts`), call history (Room cache), Bluetooth, reconnect เมื่อเปลี่ยน network (`NetworkCallback` + exponential backoff + jitter), device management/revoke (AT-11), ภาษาไทย/อังกฤษ

### Phase 6 — QA & Release (สัปดาห์ 11–12)

- OEM matrix: Pixel, Samsung, Xiaomi/Redmi, OPPO/OnePlus, Vivo
- Network: Wi-Fi, 4G/5G, handover (AT-10), NAT; **ตรวจ IPv6 กับ rtpengine** (sip-manager บันทึกไว้ว่ายังไม่ได้ตรวจ — มือถือมักได้ IPv6)
- Security: invalid cert, revoked credential, brute force (fail2ban), cross-tenant IDOR, token replay
- Battery, Force Stop (AT-13 — ระบุว่าไม่รองรับ)
- Play Store: ประกาศ foreground service `phoneCall`, license notices (PJSIP, OpenSSL)

---

## 4. ความเสี่ยงหลัก

| ความเสี่ยง | การลดความเสี่ยง |
|---|---|
| PJSIP native build / ABI / 16 KB page size | spike สัปดาห์แรก, pin PJSIP+NDK, CI build ต่อ ABI |
| Push RFC 8599 กับ OpenSIPS 3.6 ยังไม่เคยพิสูจน์ใน config นี้ | spike ขนานตั้งแต่ Phase 1 |
| ยังไม่มี TLS — SIP password วิ่งบนเครือข่ายมือถือ | บังคับ TLS ก่อน release |
| OEM battery policy ทำให้สายเข้าหาย | device matrix, คำแนะนำผู้ใช้, telemetry |
| credential ร่วมต่อ extension (ถ้าเลือกทาง ข) | revoke ได้ทั้ง extension เท่านั้น — วางแผนย้ายไปทาง ก |
| IPv6 บนมือถือกับ rtpengine | ทดสอบตั้งแต่ Phase 2 |

---

## 5. เรื่องที่ต้องตัดสินใจ

| # | เรื่อง | ตัวเลือก / ข้อแนะนำ | สถานะ |
|---|---|---|---|
| 1 | Device credential | (ก) หลาย credential ต่อ extension, username ต่ออุปกรณ์ เช่น `1001-d01` — ต้องแก้ `NumberCompiler`/dialplan ให้ดังทุกอุปกรณ์; (ข) MVP ใช้ credential ของ extension ร่วม | TBD |
| 2 | TLS/SRTP ใน MVP | แนะนำบังคับ TLS ก่อน Play Store; SRTP แบบ SDES ผ่าน rtpengine | TBD |
| 3 | ผู้รับผิดชอบงาน SIP Deployer (TLS, push) | — | TBD |
| 4 | Package name ของแอป | ใช้ `com.simplysip.phone` ชั่วคราว — ต้องยืนยันก่อนสร้าง Firebase project | TBD |
| 5 | License PJSIP | GPL หรือ commercial license (Teluu) ถ้าแอป closed source | TBD |
| 6 | สายเดียวหรือ call waiting ใน MVP | แนะนำสายเดียวใน MVP | TBD |
| 7 | เวอร์ชัน PJSIP / NDK | pin stable release หลัง spike | TBD |
| 8 | Codec | G.711 ก่อน, Opus หลังทดสอบ interoperability | แนะนำแล้ว |
| 9 | Media anchoring | rtpengine (ตาม opensips.cfg ปัจจุบัน) | ✅ ตัดสินแล้ว |

---

## 6. ขั้นถัดไป

1. ~~Phase 0 ข้อ 1 — จัดโปรเจกต์ Android~~ ✅ 5 พ.ย. 2026: package `com.simplysip.phone` (ชั่วคราว, debug = `.debug`), Java 17, Kotlin 2.4.21, AGP 9.5, Hilt 2.60.1 + KSP, module `:app` `:core` `:sip:engine` (`SipEngine`, `SipAccountConfig`, `SipEvent`, `FakeSipEngine`), strings EN/TH, CI `.github/workflows/android.yml`; `assembleDebug testDebugUnitTest lintDebug` ผ่าน (7 tests, lint 0 issue). Retrofit/OkHttp/Room/DataStore/Core-Telecom อยู่ใน version catalog แล้ว — ใส่เป็น dependency เมื่อสร้าง module ที่ใช้
2. Phase 0 ข้อ 2 — เตรียม tenant + extension ทดสอบบน node lab
3. ตัดสินใจ §5 ข้อ 1, 4, 5 ก่อนเริ่ม PJSIP spike และงานฝั่ง sip-manager
