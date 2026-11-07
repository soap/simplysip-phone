# SimplySIP Phone — แผนพัฒนา v0.2

ปรับปรุงล่าสุด: 7 พ.ย. 2026 (v0.1: 5 พ.ย. 2026)
อ้างอิง: [Cloud_IP_PBX_Android_Softphone_Technical_Specification_v1.0.docx](Cloud_IP_PBX_Android_Softphone_Technical_Specification_v1.0.docx) (เรียกว่า "สเปก")
ระบบ backend: **sip-manager** (Laravel, `D:\Coding\laravel\soap\sip-manager`) + **SIP Deployer** (เจ้าของ `opensips.cfg` และการติดตั้ง node)

**อ่านส่วนไหน**

| ผู้อ่าน | อ่าน |
|---|---|
| ทีม sip-manager | §1, **§4.1 งาน sip-manager**, **§4.3 สัญญา API**, §5 |
| ทีม SIP Deployer | §1, **§4.2 งาน SIP Deployer**, §5 |
| ทีม Android | ทั้งหมด |

---

## 1. สรุปสถานะ

| Phase | เนื้อหา | สถานะ |
|---|---|---|
| 0 | จัดโปรเจกต์ Android | ✅ 5 พ.ย. 2026 |
| 1 | SIP foundation (PJSIP, register) | ✅ Exit ผ่าน 7 พ.ย. 2026 |
| 2 | Voice calling | ✅ Exit ผ่าน 7 พ.ย. 2026 |
| **3** | **Softphone API + login/provisioning** | **⏭ ถัดไป — รอ §5 #1 และงาน sip-manager** |
| 4 | Background / push | ยังไม่เริ่ม — ต้องมี spike ของ SIP Deployer ก่อน |
| 5–6 | Production features, QA & release | ยังไม่เริ่ม |

ตอนนี้แอปบนเครื่องจริง (Samsung SM-S731B) register กับ node lab `dmo-2.sip.simplysip.cloud` ผ่าน TCP 5060 แล้วโทรออก/รับสาย/เสียงสองทาง/mute/hold/DTMF/ลำโพงได้ครบ แต่ **ยังต้องกรอก SIP credential เองในหน้า debug** — Phase 3 คือการเปลี่ยนเป็น login แล้วได้ config จาก sip-manager อัตโนมัติ

### 1.1 sip-manager / SIP Deployer เทียบกับสเปก

| เรื่อง | สเปก | sip-manager / node ตอนนี้ | ผลต่อแผน |
|---|---|---|---|
| Transport | SIP/TLS 5061 + SRTP | UDP/TCP 5060 เท่านั้น | SIP Deployer เพิ่ม TLS (§4.2) |
| Credential | แยกต่ออุปกรณ์ | 1 credential ต่อ extension (`sip_credentials.extension_id` unique); secret เข้ารหัสด้วย app key (อ่านกลับได้) | ตัดสินใจ §5 #1 |
| SIP identity | — | `opensips.cfg` บังคับ `$au == $tU` (REGISTER) และ `$au == $fU` (สายใหม่) — auth username ต้องเท่ากับเลข extension | ทาง (ก) ใน §5 #1 ต้องแก้ script |
| User ↔ Extension | login แล้วได้ extension ของตัวเอง | ไม่มี (มีแค่ tenant membership owner/admin/member) | เพิ่ม `user_extensions` |
| API auth | token ของผู้ใช้ปลายทาง | Sanctum token ผูก tenant (`personal_access_tokens.tenant_id`), ability `read`/`write` สำหรับแอดมิน/integration | เพิ่ม `/api/v1/softphone/*` แยก |
| Outbound proxy / Route | `outbound_proxy` | OpenSIPS ตอบ `403 Preload Route denied` ถ้า initial request มี `Route` | แอปต้อง **ไม่ใส่** outbound proxy; transport อยู่ใน R-URI |
| Push | RFC 8599 + FCM | `opensips.cfg` ไม่มี push | งานใหม่ทั้งสองทีม (Phase 4) |
| Media | — | rtpengine anchor ทุกสาย (`ICE=remove`, 10000–20000) | ✅ ไม่ต้องทำ ICE/STUN ฝั่งแอป |
| Domain | `sip.example.com` | โดเมนต่อ tenant `<slug>.<SIP_TENANT_DOMAIN_SUFFIX>`, realm = tenant domain | SIP domain = tenant domain |
| DTMF | RFC 4733 | `dtmf_mode = rfc4733` | ✅ ทดสอบ `*97` ผ่าน |
| CDR | `/call-history` | `call_records` (sync จาก Asterisk) ยังไม่อยู่ใน API | เปิด endpoint (§4.1) |
| Feature codes | — | `*43` echo, `*97` voicemail, `*8` pickup, `8<code><ext>` ข้าม tenant | ✅ ทดสอบผ่านทั้งหมด |

---

## 2. หลักการออกแบบฝั่ง Android

- Kotlin + Jetpack Compose, MVVM, Hilt, Coroutines/Flow, Retrofit/OkHttp, Room (เฉพาะข้อมูลไม่ลับ)
- UI ไม่เรียก PJSUA2 ตรง — ผ่าน `SipEngine` interface (สเปก §4.3)
- PJSUA2 ทั้งหมดอยู่บน thread เดียว (`libRegisterThread`), แปลง callback เป็น `Flow<SipEvent>`
- `CallStateMachine` เป็น sealed class ตามสเปก §4.4; event ซ้ำ/ผิดลำดับต้อง idempotent
- Secret เก็บด้วย Android Keystore (AES-GCM) เท่านั้น; ไม่ log credential/token
- SIP engine lifecycle แยกจาก Activity

### 2.1 Module

```
:app               UI, CallController, audio, foreground service
:core              common, coroutines, security (KeystoreCipher)
:sip:pjsip-native  prebuilt .so + PJSUA2 Java bindings
:sip:engine        SipEngine, PjsipEngine, FakeSipEngine, CallStateMachine
:data              (Phase 3) auth, provisioning, contacts, callhistory
```

---

## 3. Roadmap

### Phase 0 — เตรียมงาน ✅

package `com.simplysip.phone` (ชั่วคราว, debug = `.debug`), Java 17, Kotlin 2.4.21, AGP 9.5, Hilt 2.60.1 + KSP, strings EN/TH, CI `.github/workflows/android.yml`; tenant + extension ทดสอบบน node lab (202, 203 ใน `dmo-2`)

### Phase 1 — SIP Foundation ✅ (Exit ผ่าน 7 พ.ย. 2026)

**Exit:** เครื่องจริง register `203@dmo-2.sip.simplysip.cloud` ผ่าน TCP 5060 (OpenSIPS 3.6.10); `*43` และโทรหา 202 มีเสียงสองทาง (PCMU ผ่าน rtpengine)

- `sip/pjsip-native/scripts/build-pjsip.sh` — PJSIP 2.17, OpenSSL 3.5.9 (static, AES-GCM → SRTP), NDK r28c, swig 4.5.1, API 26, `arm64-v8a` + `x86_64`; ทุก `.so` 16 KB aligned; binary ไม่ commit — CI build แล้ว cache ตาม hash ของ script
- `PjsipEngine` — ไม่มี worker thread (`threadCnt=0`, `mainThreadOnly`) + poll `libHandleEvents`; transport UDP/TCP/TLS (TLS ตรวจ cert ด้วย CA ของ Android); SRTP ตาม `MediaEncryption`
- Instrumented test `PjsipEngineSmokeTest` 4 ข้อผ่านบน emulator API 36

สิ่งที่เจอกับ node lab (แก้ฝั่งแอปแล้ว — **ทีม backend ควรรู้**):

- `403 Preload Route denied` — OpenSIPS ปฏิเสธ initial request ที่มี `Route` → แอปไม่ใส่ proxy, transport/port อยู่ใน R-URI (`sip:<domain>;transport=tcp`). provisioning จึงต้องส่ง `outbound_proxy = null` จนกว่า script จะยอมรับ
- `401` วนจน `PJSIP_EAUTHSTALECOUNT` — extension ยัง `Retrying · node_unreachable` (subscriber ยังไม่ถึง node) ทั้งที่รหัสถูก → provisioning ต้องบอกสถานะนี้ (§4.3 `sip.status`)
- VAD ปิด — ให้ RTP ไหลต่อเนื่อง กัน rtpengine/NAT binding หมดอายุ
- ⏳ ยังไม่ทดสอบ: IPv6, register ค้างหลายชั่วโมง; ABI 32-bit ตัดออก (เพิ่มใน script ได้ถ้าต้องการ)

### Phase 2 — Voice Calling ✅ (Exit ผ่าน 7 พ.ย. 2026)

**Exit:** ทดสอบบนเครื่องจริงกับ node lab ผ่านทุกข้อ — AT-04 (โทรออก), AT-06 (เสียงสองทาง), AT-08 (mute/ลำโพง/พักสาย/DTMF), รับสายเข้าขณะเปิดแอป, `*97`, `*8`, `8<code><ext>`, สายที่สองได้ busy, ออกจากแอประหว่างสายเสียงยังอยู่, AT-10 บางส่วน (สลับ Wi-Fi/4G แล้วกลับมา Registered เอง)

- `CallStateMachine` — `Idle` / `Outgoing` / `Incoming` / `Active` / `Ended`; map SIP code → `EndCause`; unit test 11 ข้อ
- สายเดียว (§5 #6) — สายที่สองได้ `486 Busy Here` จาก engine
- Codec G.711 เท่านั้น (PCMU > PCMA)
- `CallController` — จุดเดียวที่สั่งสาย: เสียงเรียกเข้า + สั่น, ringback, audio session, foreground service
- `AudioRouteManager` — earpiece/speaker/wired/Bluetooth (API 31+); `OngoingCallService` (type `microphone`, ชั่วคราวจนถึง Core-Telecom ใน Phase 4)
- UI: Dialer, Call screen, proximity, ขอสิทธิ์ไมค์/notification, ไทย/อังกฤษ
- `KeystoreCipher` (`:core`) — AES-256-GCM; debug build จำบัญชีทดสอบ
- `RegistrationKeeper` — re-register เมื่อกลับ foreground และ `handleIpChange` เมื่อ network เปลี่ยน; retry 5 วิ แล้วทุก 60 วิ
- ⏳ ค้าง: SRTP (รอ TLS — §5 #2), จูน echo canceller ต่อรุ่น, สายเข้าบน lock screen (Phase 4)

### Phase 3 — Softphone API + Login (สัปดาห์ 5–6) ⏭

| ทีม | งาน | รายละเอียด |
|---|---|---|
| sip-manager | Softphone module + API | §4.1, สัญญา §4.3 |
| SIP Deployer | ยืนยัน multi-contact, เริ่ม TLS | §4.2 |
| Android | `:data`, หน้า login, register อัตโนมัติ | ด้านล่าง |

ฝั่ง Android:

- `:data` — Retrofit client ตาม §4.3, `SecretStore` (ใช้ `KeystoreCipher`), `device_installation_id` ใน DataStore
- OkHttp interceptor: bearer token, `X-Correlation-ID`, จัดการ `401` (กลับหน้า login)
- หน้า Login (+ เลือก tenant ถ้าผู้ใช้อยู่หลาย tenant), Logout
- ProvisioningRepository → `SipAccountConfig` → `SipEngine.registerAccount`; ถ้า `sip.status = pending` แสดงสถานะ "กำลังเตรียมบัญชี" และถามซ้ำแบบ backoff
- Register ล้มด้วย 401/403 → ดึง provisioning ใหม่ 1 ครั้ง (รหัสอาจถูกหมุน) ก่อนแจ้งผู้ใช้
- Fake backend (MockWebServer/fake repository) — ทำคู่ขนานได้โดยไม่ต้องรอ sip-manager
- หน้า debug SIP คงไว้เฉพาะ debug build
- **Exit:** login แล้ว register อัตโนมัติ ไม่ต้องกรอก SIP เอง (AT-01, AT-02, AT-03 ยกเว้นส่วน TLS)

### Phase 4 — Background / Push (สัปดาห์ 7–8) ⚠️

- **SIP Deployer:** registrar push (`pn-*`, RFC 8599) และ event เมื่อมีสายเข้าหา contact ที่หลับ (§4.2)
- **sip-manager:** consumer รับ event → queue `events` → FCM HTTP v1 (validate, dedupe, TTL, rate limit, บันทึกผล); `PUT /devices/{id}/push-token`
- **Android:** `FcmService` (high-priority data message, ตรวจ expiry), Core-Telecom `CallsManager.addCall`, foreground service type `phoneCall`, `CallStyle` notification, resume engine + re-register, contact URI มี `pn-provider`/`pn-prid`/`pn-param`
- Payload มีแค่ opaque call reference + expiry — ห้ามมี credential/token
- **Exit:** AT-09 ในสถานะ background ที่ตกลงกัน

### Phase 5 — Production Features (สัปดาห์ 9–10)

Transfer, contacts (`/softphone/contacts`), call history (Room cache), Bluetooth API < 31, device management/revoke (AT-11), backoff + jitter ของ re-register

### Phase 6 — QA & Release (สัปดาห์ 11–12)

- OEM matrix: Pixel, Samsung, Xiaomi/Redmi, OPPO/OnePlus, Vivo
- Network: Wi-Fi, 4G/5G, handover (AT-10), NAT; **IPv6 กับ rtpengine** (ยังไม่เคยตรวจ — มือถือมักได้ IPv6)
- Security: invalid cert, revoked credential, brute force (fail2ban), cross-tenant IDOR, token replay
- Battery, Force Stop (AT-13 — ระบุว่าไม่รองรับ)
- Play Store: ประกาศ foreground service `phoneCall`, license notices (PJSIP, OpenSSL)

---

## 4. งานฝั่ง backend

### 4.1 งาน sip-manager (Phase 3)

ทำตามแบบโค้ดเดิม: modular monolith, `TenantContext`, outbox → queue `provisioning`, audit (`Audit::SECRET_KEYS`), `Idempotency-Key`, error ไม่เปิดเผย secret

1. **Module ใหม่ `app/Modules/Softphone/`**
   - `softphone_devices`: `id` (ULID), `tenant_id`, `user_id`, `extension_id`, `installation_id`, `device_name`, `platform`, `app_version`, `os_version`, `status` (active/revoked), `last_seen_at`, `revoked_at`; unique (`tenant_id`, `user_id`, `installation_id`); index (`tenant_id`, `extension_id`)
   - `softphone_events` (allowlisted/redacted เท่านั้น) — บันทึก login, provisioning fetch, revoke
   - `device_push_tokens` — สร้างตารางใน Phase 4 ได้
2. **`user_extensions`** (`tenant_id`, `user_id`, `extension_id`) + หน้าจอในแอดมิน tenant ให้กำหนดผู้ใช้ของ extension (หน้า Extensions หรือ Members) และแสดงรายการอุปกรณ์/ปุ่ม revoke (แทนที่ "⏳ Devices" ในแผน sip-manager)
3. **Auth ของ softphone** — token ของผู้ใช้ (tokenable = user) ผูก tenant เหมือน `ApiToken` แต่มี ability `softphone` เท่านั้น; middleware ใหม่คู่กับ `SetApiTenant` ที่ยอมเฉพาะ ability นี้ และ token `read`/`write` เดิมเรียก `/softphone/*` ไม่ได้ (และกลับกัน); throttle login แยก; ต้องรองรับผู้ใช้ที่เปิด 2FA ของ Fortify (ดู §4.3 `two_factor_required`)
4. **Endpoints ตาม §4.3** — `auth/login`, `auth/logout`, `me`, `devices/register`, `provisioning`, `devices/{id}` (DELETE), `call-history`
5. **Provisioning** — คืนค่า config ของ extension ที่ผู้ใช้ถูกกำหนด; `sip.status`:
   - `ready` เมื่อ subscriber ถูก apply ถึง node แล้ว (operation ล่าสุดของ extension `applied`)
   - `pending` + `operation_id` เมื่อยังไม่ถึง node (เช่น `node_unreachable`) — แอปจะไม่ register จนกว่าจะ `ready`
   - ทุกครั้งที่คืน password: บันทึก audit/event (ไม่มี secret), header `Cache-Control: no-store`
6. **Call history** — `GET /softphone/call-history` จาก `call_records` เฉพาะสายของ extension ของผู้ใช้, cursor pagination
7. **Tests**
   - Tenant isolation ทุก endpoint (AT-12): token ของ tenant A อ่าน device/provisioning/call history ของ tenant B ไม่ได้; ผู้ใช้อ่าน extension ที่ไม่ได้ถูกกำหนดไม่ได้
   - Secret ไม่อยู่ใน log, audit, `softphone_events`, response อื่นนอกจาก `provisioning` (AT-02)
   - Device ที่ revoke แล้วเรียก provisioning ไม่ได้ (`device_revoked`)
   - Token `read`/`write` เดิมใช้กับ `/softphone/*` ไม่ได้
8. **ส่งให้ทีม Android**: URL ของ environment ทดสอบ, ผู้ใช้ทดสอบ 2 คนใน tenant `dmo-2` ที่ผูกกับ 202 และ 203, ผู้ใช้ 1 คนใน tenant อื่น (ทดสอบ isolation)

### 4.2 งาน SIP Deployer

**Phase 3 (ต้องตอบก่อน sip-manager เริ่มข้อ 5):**

1. ยืนยัน `opensips.cfg` บน node จริง: `registrar` `max_contacts` (เอกสารออกแบบเขียน 5) และ `lookup()` แตกสายหาทุก contact พร้อมกัน — จำเป็นสำหรับ §5 #1 ทาง (ข) (โทรศัพท์โต๊ะ + มือถือหลายเครื่อง register เป็น 203 พร้อมกันแล้วดังทุกเครื่อง)
2. ระบุ REGISTER expiry ที่ใช้ (min/max/default) — แอปจะตั้ง refresh ตามนี้
3. ถ้าเลือกทาง (ก): ประเมินการแก้ `$au == $tU` / `$au == $fU` ให้ map auth username ต่ออุปกรณ์ (เช่น `203-d01`) ไปเป็น AOR/From ของ extension (`203`) และผลต่อ caller ID

**ขนานตั้งแต่ตอนนี้ (block release / Phase 4):**

4. **TLS:** `proto_tls` 5061 + cert wildcard `*.<SIP_TENANT_DOMAIN_SUFFIX>` จาก CA สาธารณะ (แอปตรวจ cert ด้วย CA ของ Android — ห้ามใช้ self-signed) แล้วแจ้งทีม Android/sip-manager เพื่อเปลี่ยนค่า provisioning เป็น `tls`/5061
5. **SRTP:** ตกลงกับ rtpengine — SDES `RTP/SAVP` ขาโทรศัพท์ ↔ RTP ขา Asterisk (หลัง TLS เท่านั้น)
6. **Push spike (ความเสี่ยงสูงสุดของโครงการ):** ทดลอง registrar push ของ OpenSIPS 3.6 (RFC 8599, `pn-provider`/`pn-prid`/`pn-param` ใน contact), พฤติกรรมเมื่อ contact หลับ (ถือ INVITE ไว้แล้วส่งต่อหลัง re-register) และ event ที่ปล่อยให้ sip-manager รับไปส่ง FCM — สรุปผลเป็นเอกสารก่อนเริ่ม Phase 4
7. (ถ้าทำได้) อนุญาต `Route` ของ outbound proxy ที่ชี้มาที่ตัวเอง — ไม่ด่วน แอปทำงานได้โดยไม่ใช้ proxy

### 4.3 สัญญา API ที่แอปจะเขียนตาม (ร่าง — ทีม sip-manager แก้ได้ แต่แจ้งทีม Android)

**ข้อตกลงทั่วไป**

- Base: `/api/v1/softphone`, HTTPS, JSON, `Authorization: Bearer <token>` (ยกเว้น `auth/login`)
- แอปส่ง `X-Correlation-ID` (UUID) ทุก request — server ใส่ใน log และตอบกลับใน error
- ID ทุกตัวเป็น string ใน JSON (รองรับทั้ง ULID และเลข)
- Error envelope เดียวทุก endpoint:

```json
{ "error": { "code": "invalid_credentials", "message": "อีเมลหรือรหัสผ่านไม่ถูกต้อง" }, "correlation_id": "…" }
```

| HTTP | `code` | แอปทำอะไร |
|---|---|---|
| 401 | `invalid_credentials` | แจ้งผู้ใช้ |
| 401 | `unauthenticated` | token หมดอายุ/ถูกเพิกถอน → กลับหน้า login |
| 409 | `tenant_required` | ให้เลือก tenant จาก `tenants` แล้ว login ใหม่ |
| 409 | `two_factor_required` | ขอรหัส 2FA แล้วส่งซ้ำพร้อม `code` |
| 403 | `tenant_suspended` | แจ้งผู้ใช้, ไม่ register |
| 403 | `device_revoked` | ลบ credential, unregister, กลับหน้า login |
| 409 | `no_extension_assigned` | แจ้งให้ติดต่อผู้ดูแล tenant |
| 422 | `validation_failed` | + `errors` แบบ Laravel |
| 429 | `too_many_requests` | + `Retry-After` |

**`POST /auth/login`**

```json
// request
{ "email": "user@example.com", "password": "…", "tenant": "dmo-2", "code": null,
  "device_name": "Samsung SM-S731B" }
// 200
{ "token": "…", "user": { "id": "1", "name": "สมชาย", "email": "user@example.com" },
  "tenant": { "id": "01J…", "slug": "dmo-2", "name": "Demo 2" } }
// 409 tenant_required
{ "error": { "code": "tenant_required", "message": "…" },
  "tenants": [ { "slug": "dmo-2", "name": "Demo 2" } ], "correlation_id": "…" }
```

- `tenant` ไม่ต้องส่งถ้าผู้ใช้อยู่ tenant เดียว
- token ผูก tenant เดียว, ability `softphone`; ไม่หมดอายุเอง แต่เพิกถอนได้ (logout, revoke device, ผู้ใช้ออกจาก tenant)

**`POST /auth/logout`** → `204` (ลบ token ปัจจุบัน; ไม่ revoke device)

**`GET /me`** → `{ "user": {…}, "tenant": {…}, "role": "member" }`

**`POST /devices/register`** (idempotent ตาม `installation_id`)

```json
// request
{ "installation_id": "5b0e…uuid", "device_name": "Samsung SM-S731B", "platform": "android",
  "app_version": "0.1.0", "os_version": "Android 16",
  "capabilities": { "push": false, "srtp": true, "tls": true } }
// 200 (มีอยู่แล้ว) / 201 (ใหม่)
{ "device": { "id": "01J…", "status": "active" } }
```

**`GET /provisioning?device_id=01J…`** — `Cache-Control: no-store`

```json
{
  "device_id": "01J…",
  "tenant": { "id": "01J…", "name": "Demo 2" },
  "extension": { "id": "42", "number": "203", "display_name": "สมชาย" },
  "sip": {
    "status": "ready",
    "operation_id": null,
    "domain": "dmo-2.sip.simplysip.cloud",
    "username": "203",
    "password": "…",
    "credential_version": 3,
    "transport": "tcp",
    "port": 5060,
    "outbound_proxy": null,
    "media_encryption": "none",
    "register_expires": 300
  }
}
```

- `status: "pending"` → `password` เป็น `null` และมี `operation_id`; แอปถามซ้ำ (5 วิ → 10 → 30 → 60)
- `credential_version` เปลี่ยนเมื่อหมุนรหัส — แอปใช้ตัดสินว่าต้อง re-register
- `transport` ∈ `udp`/`tcp`/`tls`, `media_encryption` ∈ `none`/`srtp` — เปลี่ยนเป็น `tls`/5061/`srtp` ฝั่ง server ได้โดยไม่ต้องออกแอปใหม่
- `outbound_proxy` ต้องเป็น `null` กับ `opensips.cfg` ปัจจุบัน (Preload Route)
- ถ้าผู้ใช้มีหลาย extension: MVP ใช้ extension แรกที่ถูกกำหนด (เพิ่ม `?extension_id=` ภายหลัง)

**`DELETE /devices/{id}`** → `204` (ผู้ใช้ revoke เครื่องตัวเอง หรือแอดมิน revoke จากหน้าเว็บ)

**`GET /call-history?cursor=…`**

```json
{ "data": [ { "id": "…", "direction": "outbound", "remote_number": "202",
              "remote_name": "…", "started_at": "2026-11-07T10:00:00+07:00",
              "answered": true, "duration_seconds": 42 } ],
  "next_cursor": "…" }
```

---

## 5. เรื่องที่ต้องตัดสินใจ

| # | เรื่อง | ตัวเลือก / ข้อแนะนำ | ผู้ตัดสิน | สถานะ |
|---|---|---|---|---|
| 1 | **Device credential** (block Phase 3) | **(ข) แนะนำสำหรับ MVP:** ทุกเครื่องของ extension ใช้ credential เดิมของ extension — ใช้ได้ทันทีกับ `$au == $tU`/`$fU`, ดังทุกเครื่องผ่าน multi-contact; ข้อเสีย: revoke ได้แค่ device ใน API (เครื่องที่จำรหัสไว้ยัง register ได้จนกว่าจะหมุนรหัส extension ซึ่งกระทบทุกเครื่อง) — AT-11 ผ่านไม่เต็ม. **(ก)** credential ต่ออุปกรณ์ เช่น `203-d01` — ตรงสเปก แต่ต้องแก้ `opensips.cfg` (§4.2 ข้อ 3), `NumberCompiler`/dialplan และ caller ID. ข้อเสนอ: ทำ (ข) ใน Phase 3 แล้วออกแบบ schema ให้ย้ายไป (ก) ก่อน release ได้ | เจ้าของผลิตภัณฑ์ + ทั้งสองทีม | **TBD** |
| 2 | TLS/SRTP ใน MVP | บังคับ TLS ก่อน Play Store; SRTP แบบ SDES ผ่าน rtpengine | เจ้าของผลิตภัณฑ์ | TBD |
| 3 | ผู้รับผิดชอบงาน SIP Deployer (TLS, push) | ทีม SIP Deployer | — | ✅ |
| 4 | Package name ของแอป | ใช้ `com.simplysip.phone` ชั่วคราว — ยืนยันก่อนสร้าง Firebase project (Phase 4) | เจ้าของผลิตภัณฑ์ | TBD |
| 5 | License PJSIP | GPL หรือ commercial license (Teluu) ถ้าแอป closed source — ต้องรู้ก่อน release | เจ้าของผลิตภัณฑ์ | TBD |
| 6 | สายเดียวหรือ call waiting ใน MVP | สายเดียว — สายที่สองได้ 486 Busy | — | ✅ (เปลี่ยนได้) |
| 7 | เวอร์ชัน PJSIP / NDK | PJSIP 2.17, OpenSSL 3.5.9, NDK r28c | — | ✅ |
| 8 | Codec | G.711 ก่อน, Opus หลังทดสอบ interoperability | — | ✅ G.711 |
| 9 | Media anchoring | rtpengine | — | ✅ |
| 10 | ผู้ใช้หลาย extension | MVP ใช้ extension แรก; เลือกได้ภายหลัง | ทีม Android + sip-manager | ข้อเสนอ |

---

## 6. ความเสี่ยงหลัก

| ความเสี่ยง | การลดความเสี่ยง |
|---|---|
| Push RFC 8599 กับ OpenSIPS 3.6 ยังไม่เคยพิสูจน์ใน config นี้ | spike ของ SIP Deployer ตอนนี้ (§4.2 ข้อ 6) |
| ยังไม่มี TLS — SIP password วิ่งบนเครือข่ายมือถือ | บังคับ TLS ก่อน release (§4.2 ข้อ 4) |
| credential ร่วมต่อ extension (ถ้าเลือก §5 #1 ทาง ข) | revoke ได้ทั้ง extension เท่านั้น — วางแผนย้ายไปทาง ก |
| OEM battery policy ทำให้สายเข้าหาย | device matrix, คำแนะนำผู้ใช้, telemetry |
| IPv6 บนมือถือกับ rtpengine | ทดสอบใน Phase 6 หรือเร็วกว่าถ้ามี SIM ที่ได้ IPv6 |
| PJSIP native build / 16 KB page size | ✅ แก้แล้วใน Phase 1 (pin เวอร์ชัน, CI cache) |

---

## 7. ขั้นถัดไป

1. **ตัดสินใจ §5 #1** (แนะนำ ข) — block งาน provisioning ของ sip-manager
2. **SIP Deployer** ตอบ §4.2 ข้อ 1–2 (multi-contact, expiry)
3. **sip-manager** ทบทวนสัญญา §4.3 แล้วเริ่ม §4.1; แจ้งทีม Android เมื่อมี environment ทดสอบ
4. **Android** เริ่ม `:data` + หน้า login กับ fake backend ตามสัญญา §4.3 ได้ทันที
5. **SIP Deployer** เริ่ม TLS และ push spike ขนานกันไป
