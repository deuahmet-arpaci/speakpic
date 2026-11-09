# SpeakPic – İmzalama ve Google Play Yayın Rehberi

Tahmini süre (yeni kişisel hesap): hesap/kimlik doğrulama birkaç gün + 14 günlük kapalı test + inceleme ≈ 3-4 hafta.

---
## 1. Uygulama kimliği (applicationId) – ŞİMDİ karar verin

`app/build.gradle.kts` içinde: `applicationId = "io.github.deuahmetarpaci.speakpic"`

- Play'e ilk yüklemeden sonra bu kimlik **bir daha değiştirilemez**.
- Dünyada tekil olmalı. Kendi alan adınız varsa ters yazımı iyi bir tercihtir (örn. `com.sirketiniz.speakpic`).
- Değiştirecekseniz sadece o satırı değiştirin; kod paketi (`com.speakpic.app`) aynı kalabilir.

## 2. İmza (yükleme) anahtarı oluşturma – bir kez yapılır

Play App Signing kullanacağız: siz **yükleme anahtarıyla** imzalarsınız, Google dağıtım anahtarını kendi saklar.
(Yükleme anahtarını kaybederseniz Google destekle sıfırlanabilir; yine de yedekleyin.)

**Yol A – Android Studio:** Build → Generate Signed App Bundle / APK → Android App Bundle → *Create new…*
Dosya adı `speakpic-upload.jks`, bir parola belirleyin, alias `speakpic`, geçerlilik 25+ yıl (örn. 10000 gün).

**Yol B – komut satırı** (JDK gerekir; Android Studio'nun `jbr/bin/keytool` dosyası da olur):
```
keytool -genkeypair -v -keystore speakpic-upload.jks -alias speakpic -keyalg RSA -keysize 2048 -validity 10000
```

> **Anahtarı ve parolaları kimseyle (benimle dahil) paylaşmayın, GitHub'a dosya olarak yüklemeyin** (`.gitignore` zaten engeller).
> `.jks` dosyasını ve parolaları parola yöneticisi + ikinci bir yerde yedekleyin.

## 3. GitHub'a imza sırlarını (secrets) ekleme ve AAB üretme

1. Anahtarı base64 metne çevirin:
   - Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("speakpic-upload.jks")) | Set-Clipboard`
   - Mac/Linux: `base64 -w0 speakpic-upload.jks` (Mac'te `base64 -i speakpic-upload.jks`)
2. GitHub deposu → **Settings → Secrets and variables → Actions → New repository secret**. Dört sır ekleyin:

   | Ad | Değer |
   |---|---|
   | `KEYSTORE_BASE64` | 1. adımda kopyalanan uzun metin |
   | `KEYSTORE_PASSWORD` | anahtar deposu parolası |
   | `KEY_ALIAS` | `speakpic` |
   | `KEY_PASSWORD` | anahtar parolası (Studio'da aynı parolayı girdiyseniz aynısı) |

3. **Actions** sekmesi → "SpeakPic Derle" → **Run workflow**.
4. Bitince **Artifacts** bölümünden `SpeakPic-release-signed` zip'ini indirin: içinde
   - `app-release.aab` → **Play Console'a yüklenecek dosya**
   - `app-release.apk` → imzalı sürümü telefona doğrudan kurup denemek için.
5. `versionCode` her derlemede otomatik artar (GitHub çalıştırma numarası); Play her yüklemede daha büyük numara ister.

## 4. Google Play geliştirici hesabı

- https://play.google.com/console → hesap oluşturun; tek seferlik kayıt ücreti vardır (tutarı kayıt sırasında görürsünüz).
- Kimlik (ve adres) doğrulaması istenir; birkaç gün sürebilir.
- **Kişisel hesap** (13 Kasım 2023'ten sonra açılmış) → yayından önce **kapalı test** zorunlu (bkz. 8). **Kuruluş hesabı** bundan muaftır ama D-U-N-S numarası gerekir.

## 5. Uygulamayı oluşturma

Console → **Uygulama oluştur**: ad `SpeakPic: Voice Photo Notes`, varsayılan dil English (United States), Uygulama, Ücretsiz,
bildirimleri işaretleyin. **Play Uygulama İmzalama**'ya (App Signing) kayıt: ilk AAB'yi yüklerken varsayılan olarak etkindir; kabul edin.

## 6. Ana mağaza girişi

`docs/STORE_LISTING.md` içindeki metinler ve `store_assets/` klasöründeki görseller:
- `icon-512.png` → Uygulama simgesi (512×512)
- `feature-graphic-1024x500.png` → Öne çıkan grafik (1024×500)
- **Telefon ekran görüntüleri: en az 2** (sizin telefonunuzdan: kamera, açılmış bilgi paneli, galeri, görüntüleyici, dil seçimi). Farklı dillerde çekerseniz çeviri girişlerine ekleyebilirsiniz.

## 7. Uygulama içeriği (Politika → Uygulama içeriği) formları

| Form | Ne yapılır |
|---|---|
| Gizlilik politikası | `docs/privacy-policy.html` dosyasında `[TARİH]`, `[ADINIZ]`, `[E-POSTA]` alanlarını doldurup GitHub Pages ile yayınlayın: depo → Settings → Pages → *Deploy from a branch* → Branch `main`, klasör `/docs` → Save. Adres: `https://<kullanıcı>.github.io/<depo-adı>/privacy-policy.html` (depo herkese açık olmalı). Bu URL'yi forma girin. |
| Reklamlar | Hayır |
| Uygulama erişimi | Kısıtlama yok |
| İçerik derecelendirme | Anketi dürüstçe doldurun (şiddet/cinsellik/kumar yok) |
| Hedef kitle | Yalnızca 13+ gruplarını seçin |
| Veri güvenliği | `STORE_LISTING.md` içindeki önerilere bakın |
| **Foto ve video izinleri** | **Zorunlu beyan** – gerekçe metni `STORE_LISTING.md` içinde hazır |
| Reklam kimliği | Kullanmıyor |
| Diğerleri (finans, sağlık, devlet, haber…) | Uygulanmaz |

**Foto/video izni neden önemli?** Google bu izinlere yalnızca ana işlevi tüm fotoğrafları yönetmek olan uygulamalarda (ör. galeri uygulamaları) izin veriyor; sistem "Fotoğraf seçici" yeterliyse reddedebilir. SpeakPic bir galeri olduğu için gerekçemiz güçlü; ayrıca izin verilmezse uygulama çalışmaya devam ediyor (kendi çektikleriniz + uyarı bandı) — politika bunu şart koşuyor. İnceleme reddederse yanıta itiraz/ek açıklama yazabilir ya da galeriyi "Fotoğraf seçici" tabanlı kısıtlı moda indirmeyi birlikte yaparız.

## 8. Test aşamaları

1. **Dahili test** (Test ve yayınla → Test → Dahili test): AAB'yi yükleyin, kendi e-postanızı test kullanıcısı ekleyin; dakikalar içinde Play'den kurup tam akışı deneyin (izinler, dil değiştirme, galeri).
2. **Kapalı test** (kişisel hesap için zorunlu): Google'ın kuralı: **en az 12 test kullanıcısı, kesintisiz 14 gün boyunca testte kayıtlı kalmalı.** Adımlar:
   - Bir Google Grubu oluşturun veya e-posta listesi hazırlayın (12+ kişi, Android telefonu olan).
   - Kapalı test kanalı oluşturun, aynı AAB'yi (veya yenisini) yükleyin, kullanıcı listesini ekleyin.
   - Kullanıcılara **katılım bağlantısını** gönderin; her biri bağlantıdan "Tester ol" deyip Play'den kursun; 14 gün boyunca çıkmasınlar.
   - 14 gün dolunca Console'da **Üretime erişim için başvur** formunu doldurun (testte ne yaptığınız, geri bildirimler).
3. **Üretim:** Başvuru onaylanınca Üretim kanalında sürüm oluşturun. İlk yayında **kademeli dağıtım** (ör. %20) seçebilirsiniz.

## 9. Güncelleme yayınlama

Kodda değişiklik → GitHub'a yükleyin → Actions yeni imzalı AAB üretir (versionCode otomatik artar) → Console'da yeni sürüm oluşturup AAB'yi yükleyin. İsterseniz sürüm adını `app/build.gradle.kts` içindeki `versionName`'den güncelleyin.

## 10. Sık görülen red nedenleri

- Gizlilik politikası URL'si açılmıyor / eksik alanlar → politikayı doldurun, URL'yi test edin.
- Foto/video izni beyanı yetersiz → gerekçeyi kısa ve somut tutun, ekran videosu ekleyin (galerinin çalıştığını gösteren 30-60 sn).
- Ekran görüntüleri/metinler uygulamayı yansıtmıyor.
- Mağaza metninde başka marka adları / yanıltıcı ifadeler.

## v1.2 ile gelen yeni noktalar (yayın öncesi kontrol listesi)

- **INTERNET izni eklendi** (yalnızca isteğe bağlı yapay zekâ düzenleme için). Gizlilik politikası ve Veri güvenliği formu buna göre güncellendi; politikayı yayınlamadan önce `[TARİH]`, `[ADINIZ]`, `[E-POSTA]` alanlarını doldurun.
- **API anahtarı uygulamaya gömülü DEĞİL.** Kullanıcı kendi Anthropic anahtarını girer (yalnızca cihazda saklanır). Kendi anahtarınızı koda gömüp Play'e yüklemeyin: APK'dan çıkarılıp kötüye kullanılabilir. Herkese hazır AI sunmak isterseniz, anahtarı saklayan küçük bir sunucu (proxy) gerekir; bu ayrı bir iştir.
- Yapay zekâ özelliğini test ederken Anthropic Console'da küçük bir harcama limiti belirlemeniz önerilir.
- **Video hız değişimi:** kayıttan sonra Media3 ile yeniden kodlanır; uzun videolarda işlem birkaç on saniye sürebilir. Foto/video izni beyanı videoları da kapsıyor.
- İlk kapalı test sürümünde yeni özellikleri (video hızı, gece modu, düzenleyici) farklı telefonlarda denetleyin; kameralar farklı davranabilir.

## Teknik notlar

- Hedef API 36 (Android 16): Google'ın şartı. Uygulama tam ekran (edge-to-edge) çizer ve önerilen geri hareketini destekler.
- Uygulamada yerel (native) kod yok → 16 KB sayfa boyutu şartı sorun çıkarmaz.
- AAB'de dil bölünmesi kapalıdır (`enableSplit = false`); uygulama içi dil seçici bu yüzden tüm dillerde çalışır.
- Desteklenen diller: tr, en, de, fr, es, it, pt, ru, ar, ja. Yeni dil eklemek için `values-XX/strings.xml` ve `res/xml/locales_config.xml` dosyalarına ekleme yapılır; çeviriyi benden isteyebilirsiniz.
