# Uyku

Telefonu gece başucunda bir sonara çeviren kişisel uyku takip uygulaması.

- Hoparlörden duyulmayan 18–20 kHz'lik yumuşak bir frekans süpürmesi çalınır; mikrofon yankıları dinler.
- Göğüs ve karnın nefesle hareketi yankının fazını döndürür: nefes hızı, nefes duraklamaları (≥10 sn)
  ve hareket buradan çıkarılır. Duyulur bantta horlama ve gürültü olayları sayılır.
- Telefon göğüste ya da yatakta vücuda yakınsa ivmeölçer de nefesi ve hareketi ölçer; her saniye
  daha iyi olan kaynak kullanılır.
- Test ekranı tanı çizimleri gösterir: alınan spektrum, yankı profili, seçili yankının yörüngesi.
- Ses kaydedilmez; yalnızca 30 sn'lik özetler telefonun içinde saklanır. Uygulamanın internet izni yoktur.
- Sabah rapor: uyku süresi, verim, uykuya dalma süresi, uyanmalar, tahmini evreler (derin/hafif/REM),
  nefes hızı grafiği, duraklamalar, hareket/horlama/gürültü.

Tıbbi cihaz değildir.

Kaynak: `app/src/main/java/com/durmuseminalbayrak/uyku/`
- `Sonar.java` – sinyal tasarımı, kanal kestirimi, saat kayması izleme, nefes/hareket/duraklama
- `Accel.java` – ivmeölçerden nefes/hareket
- `PauseDetector.java` – nefes duraklaması algılama (ortak)
- `SoundMeter.java` – horlama ve gürültü olayları
- `NightRecorder.java`, `Night.java` – 30 sn'lik dönemler ve kayıt dosyası
- `SleepAnalyzer.java` – uyku/uyanıklık ve tahmini evreler
- `SleepService.java` – gece boyunca çalışan servis (hoparlör döngüsü + mikrofon)
- `MainActivity.java`, `ChartView.java`, `WaveView.java`, `DiagView.java` – arayüz
