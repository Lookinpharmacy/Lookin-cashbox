# Lookin Cashbox

ระบบตู้เก็บเงินของร้านลูกอิน เภสัช (แทนซอฟต์แวร์ Tiger)

- `app/` แอป Android บนจอตู้ (ฝากเงิน, เปิดตู้ด้วย OTP)
- `firmware/probe/` เฟิร์มแวร์ตรวจสอบ ใช้หาแผนผังขาของบอร์ดฐาน (ไม่รับเงิน)
- `firmware/main/` เฟิร์มแวร์ใช้งานจริง (ทำหลังได้แผนผังขา)
- `PROTOCOL.md` ข้อความที่แอปกับบอร์ดคุยกัน

ทุกครั้งที่อัปโหลดไฟล์ใหม่ GitHub Actions จะสร้าง `cashbox.apk` และไฟล์ `.hex` ไว้ในหน้า Releases

ลิงก์ติดตั้งแอปบนจอตู้: `https://github.com/Lookinpharmacy/Lookin-cashbox/releases/latest/download/cashbox.apk`

รหัสลับ OTP ไม่อยู่ใน repo นี้ (อยู่ในเครื่องคลังและบอร์ดเท่านั้น)

กุญแจลงชื่อแอปไม่อยู่ใน repo นี้ เก็บใน Settings → Secrets and variables → Actions:
`CASHBOX_KEYSTORE_B64` และ `CASHBOX_KEYSTORE_PASS` (ต้นฉบับอยู่ที่เครื่องคลัง `C:\drgstrAPI\cashbox-signing`)
