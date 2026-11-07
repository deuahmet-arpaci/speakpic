# SpeakPic – Mağaza Girişi Metinleri

Play Console → **Büyüme → Mağaza varlığı → Ana mağaza girişi**. Metinleri kopyalayıp yapıştırın.
Varsayılan dil İngilizce; Türkçeyi "Çeviri ekle" ile ekleyin. Diğer 8 dil için de istediğinizde çeviririm.

Sınırlar: Uygulama adı ≤ 30, Kısa açıklama ≤ 80, Tam açıklama ≤ 4000 karakter.

---
## English (default)

**App name:** `SpeakPic: Voice Photo Notes`

**Short description:** `Add voice notes to your photos. Speech-to-text saved right in the image.`

**Full description:**
```
SpeakPic gives your photos a voice.

Take a photo, tap the microphone and simply describe it. SpeakPic turns your speech into text and saves it inside the photo file itself (EXIF), so the story travels with the picture.

★ Voice note → text → saved in the photo's EXIF data
★ Gallery with all your photos, including pictures taken with other apps
★ Add, edit or clear descriptions by voice or keyboard
★ Filter to see only photos that have a description
★ Listen to a photo's description read aloud
★ Share a photo (with its description), copy the description, rename, edit or delete photos
★ Select many photos at once to share or delete
★ Camera with tap-to-focus, pinch zoom, grid, flash and HDR (on supported devices)
★ 10 languages – switch the language inside the app: English, Türkçe, Deutsch, Français, Español, Italiano, Português, Русский, العربية, 日本語
★ No account, no ads, no tracking

GOOD TO KNOW
• Speech recognition is provided by your device's speech service. Depending on your device, an internet connection or an offline language pack may be required.
• Descriptions can be saved in JPEG, PNG and WebP images.
• To work with photos taken by other apps, allow photo access when asked. Android asks for your confirmation before SpeakPic edits or deletes those photos.
```

---
## Türkçe

**Uygulama adı:** `SpeakPic: Sesli Foto Notları`

**Kısa açıklama:** `Fotoğraflarınıza sesle not ekleyin. Not, görselin içinde saklanır.`

**Tam açıklama:**
```
SpeakPic fotoğraflarınıza ses verir.

Fotoğraf çekin, mikrofona dokunun ve anlatın. SpeakPic konuşmanızı yazıya çevirir ve fotoğraf dosyasının içine (EXIF) kaydeder; böylece hikâye fotoğrafla birlikte yolculuk eder.

★ Sesli not → yazı → fotoğrafın EXIF verisine kayıt
★ Diğer uygulamalarla çektikleriniz dahil tüm fotoğraflarınızla galeri
★ Açıklamayı sesle ya da klavyeyle ekleyin, düzenleyin, silin
★ Yalnızca açıklaması olan fotoğrafları süzün
★ Fotoğrafın açıklamasını sesli dinleyin
★ Fotoğrafı (açıklamasıyla) paylaşın, açıklamayı kopyalayın, yeniden adlandırın, düzenleyin, silin
★ Çok sayıda fotoğrafı birden seçip paylaşın veya silin
★ Dokunarak odaklama, parmakla yakınlaştırma, ızgara, flaş ve HDR (destekleyen cihazlarda) olan kamera
★ 10 dil – dili uygulama içinden değiştirin: Türkçe, English, Deutsch, Français, Español, Italiano, Português, Русский, العربية, 日本語
★ Hesap yok, reklam yok, izleme yok

BİLMENİZDE FAYDA VAR
• Konuşma tanıma cihazınızın konuşma servisi tarafından sağlanır. Cihazınıza göre internet bağlantısı ya da çevrimdışı dil paketi gerekebilir.
• Açıklamalar JPEG, PNG ve WebP görüntülere kaydedilebilir.
• Başka uygulamalarla çekilmiş fotoğraflarla çalışmak için istenince fotoğraf erişimine izin verin. SpeakPic bu fotoğrafları düzenlemeden veya silmeden önce Android sizden onay ister.
```

---
## Foto ve video izinleri beyanı (Play Console → Uygulama içeriği → Foto ve video izinleri)

Form İngilizce; aşağıdaki gerekçeyi kullanabilirsiniz:

```
SpeakPic is a camera and gallery app whose core feature is adding and managing voice-transcribed
descriptions for the user's photos. The in-app gallery lists all of the user's photos (including those
taken with other apps), lets the user add/edit/clear a description stored in the image's EXIF data, filter
photos that have descriptions, read descriptions aloud, and perform batch actions (share, delete) and
renaming. This requires persistent, library-wide read access (READ_MEDIA_IMAGES) to browse and
search the whole library and to read EXIF descriptions from many files. The Android Photo Picker cannot
provide a browsable, filterable, persistent library view or batch management, so it is not sufficient for
the core functionality. Writing or deleting photos created by other apps always goes through the
system's user-consent dialogs (MediaStore.createWriteRequest / createDeleteRequest). If the user denies
access, the app still works: the camera and the gallery of photos taken with SpeakPic remain available,
and an in-app banner lets the user grant access later.
```

---
## Veri güvenliği (Data safety) – önerilen yanıtlar

> Bunlar önerimdir; Play Console sorularını ve Google'ın yardım sayfasını okuyarak son kararı siz verin.

- **Uygulama kullanıcı verisi topluyor mu veya paylaşıyor mu?** Geliştirici olarak hiçbir veri toplamıyoruz ve sunucumuz yok.
  Ancak konuşma tanıma, cihazın sistem servisi (genelde Google) tarafından yapılır ve ses o servis tarafından işlenebilir.
  **Tutucu seçenek (öneri):** "Ses → Ses veya ses kayıtları: Toplanıyor, paylaşılmıyor, geçici (ephemeral) işleniyor, amaç: uygulama işlevi, isteğe bağlı" olarak beyan edin.
  Gizlilik politikasında bunu zaten açıkladık.
- **Veriler aktarım sırasında şifreleniyor mu?** Sistem servisi kullanıldığı için "Evet" (sistem servisi bağlantıları şifreli) – emin değilseniz Console'daki açıklamaya bakın.
- **Kullanıcılar veri silme isteyebilir mi?** Hesap yok; veri yalnızca kullanıcının cihazında. Silme: açıklamayı temizle / fotoğrafı sil.
- **Fotoğraflar:** Cihazdan okunuyor ama geliştiriciye veya üçüncü kişilere gönderilmiyor → "toplanmıyor".

## Diğer formlar (kısa yanıtlar)
- **Reklamlar:** Hayır, uygulamada reklam yok.
- **Reklam kimliği (Advertising ID):** Kullanmıyor.
- **Uygulama erişimi:** Tüm işlevler kısıtlama olmadan erişilebilir (giriş yok).
- **Hedef kitle:** Yalnızca 13 yaş ve üzeri grupları seçin (13 yaş altını seçmek Aileler politikasını devreye sokar).
- **İçerik derecelendirme (IARC):** Kategori "Yardımcı program/Fotoğraf"; şiddet, cinsellik, kumar vb. yok; kullanıcılar arası içerik paylaşımı yok → en düşük yaş derecesi çıkması beklenir.
- **Kategori:** Fotoğrafçılık (Photography). **Etiketler:** Camera, Photo editor/gallery, Voice.
- **İletişim e-postası:** Herkese açık görünür; gizlilik politikasındakiyle aynı olsun.
