# Uyku

Telefonu gece başucunda bir sonara çeviren kişisel uyku takip uygulaması.

- Hoparlörden duyulmayan 18–20 kHz'lik yumuşak bir frekans süpürmesi çalınır; mikrofon yankıları dinler.
- Göğüs ve karnın nefesle hareketi yankının fazını döndürür: nefes hızı, nefes duraklamaları (≥10 sn)
  ve hareket buradan çıkarılır. Duyulur bantta horlama ve gürültü olayları sayılır.
- Telefon göğüste ya da yatakta vücuda yakınsa ivmeölçer de nefesi ve hareketi ölçer; her saniye
  daha iyi olan kaynak kullanılır.
- Test ekranı tanı çizimleri gösterir: alınan spektrum, yankı profili, seçili yankının yörüngesi.
- Horlama gibi geniş bantlı sesler bant dışı kutulardan ölçülür; gürültülü bloklar daha az ağırlık alır,
  hareket ve nefes genliğinden gürültü payı çıkarılır (horlama hareket sayılmaz).
- Hoparlör ile mikrofon saatleri arasındaki kayma, ultrason çizgilerini komşu FFT kutularına sızdırır;
  kayma döngüden bilindiği için bu sızıntı her blokta geri alınır (zayıf yankıda belirgin kazanç).
  Nefes dalgası analizden önce 1 Hz'lik alçak geçirenden geçirilir.
- Hareket eşiği son 5 dk ve son 30 sn'lik seviyelere göre uyarlanır: dönünce yankı güçlenirse
  her nefes hareket sayılmaz.
- Sonar ve ivmeölçer nefesi göremezken düzenli horlama varsa nefes hızı horlamaların aralığından
  alınır (horlayanda her nefeste bir horlama). Odada başka horlayan biri varsa bu onun ritmi olabilir.
- Nefesin ölçülemediği ama hareketin olmadığı süre "uyku, evre ölçülemedi" sayılır; horlama = uyku.
  Son nefes/horlama kanıtından sonra tekrarlayan uyanıklık varsa (kayıt sonuna ≤60 dk) sabah uyanışı sayılır.
- Rapor ekranından gece CSV olarak dışa aktarılabilir (ses yok, yalnız ölçüm sayıları; saniyelik tanı kaydı:
  nefes hızı ve kaynağı, sinyal düzeyleri, hareket, saat kayması, horlama ritmi).
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
- `Exporter.java` – CSV dışa aktarma
- `MainActivity.java`, `ChartView.java`, `WaveView.java`, `DiagView.java` – arayüz
