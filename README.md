# Tuya Pilot

Aplikacja na Androida do sterowania urządzeniami Tuya / Smart Life: gniazdami, oświetleniem i bramą garażową. Steruje nimi **lokalnie** w sieci domowej, bez chmury Tuya. Spoza domu łączy się przez VPN **Tailscale**.

- **Aplikacja:** [pobierz tuya-pilot.apk](https://github.com/zooolw-sudo/tuya-pilot/releases/latest/download/tuya-pilot.apk) (buduje się sama po każdej zmianie w `main`, tak jak Wędzarnia i Pogoda Buk)
- Android 8.0 lub nowszy

## Co umie

- **Gniazda i włączniki:** włączanie i wyłączanie, listwy z kilkoma gniazdami i USB, pomiar mocy, napięcia, prądu i zużycia energii (jeśli gniazdo mierzy).
- **Oświetlenie:** włączanie, jasność, barwa bieli, kolor (odcień i nasycenie).
- **Brama garażowa:** stan z czujnika (otwarta / zamknięta), przyciski „Otwórz” i „Zamknij”. Każdy trzeba **przytrzymać przez sekundę**, żeby brama nie otworzyła się przypadkiem w kieszeni. Po komendzie aplikacja śledzi czujnik, aż brama skończy ruch.
- **Wszystkie punkty danych (DP)** każdego urządzenia, z opisami z chmury, także te bez osobnego ekranu (np. zachowanie po zaniku prądu).
- Protokół lokalny Tuya w wersjach **3.1, 3.3, 3.4 i 3.5**, z automatycznym rozpoznawaniem wersji.
- Wyszukiwanie urządzeń w sieci (adres IP i wersja).
- Jasny i ciemny motyw, jak w Radio RMF.

Smart Life dalej działa normalnie obok tej aplikacji.

## 1. Klucze z chmury Tuya

Każde urządzenie ma **klucz lokalny** (local key), bez którego nie da się z nim rozmawiać w sieci. Klucze zna tylko chmura Tuya, więc pobiera się je raz, z darmowego konta deweloperskiego. Potem chmura nie jest już potrzebna.

1. Załóż konto na [iot.tuya.com](https://iot.tuya.com/). Przy pytaniu o rodzaj konta wybierz „Skip this step…”.
2. **Cloud → Create Cloud Project.** Jako *Data Center* wybierz **Central Europe**. Konta Smart Life z Polski są zwykle tam.
3. Kreator konfiguracji możesz pominąć. Na karcie projektu (**Overview**) znajdziesz **Access ID / Client ID** i **Access Secret / Client Secret**.
4. W projekcie: **Devices → Link Tuya App Account → Add App Account**. Wybierz „Automatic” i „Read Only Status” (sterowanie i tak działa). Pojawi się kod QR.
5. W aplikacji **Smart Life**: karta **Ja** → ikona skanera w prawym górnym rogu → zeskanuj kod. W projekcie pojawią się wszystkie Twoje urządzenia.
   - Brak urządzeń? Zmień centrum danych projektu (np. na Western Europe) i spróbuj jeszcze raz.
6. Na karcie **Service API** muszą być **IoT Core** i **Authorization**. Gdyby ich brakowało: **Go to Authorize**, zaznacz i **Subscribe**. Wyłącz blokadę wyskakujących okien, inaczej subskrypcja po cichu się nie uda.
7. W Tuya Pilot: **Ustawienia → Klucze z chmury Tuya**. Wybierz centrum danych, wklej Access ID i Access Secret, potem **Pobierz urządzenia i klucze**.

Darmowy okres usługi IoT Core trwa miesiąc. Do codziennego sterowania nie jest potrzebny, ale **po dodaniu albo ponownym sparowaniu urządzenia** trzeba pobrać klucze jeszcze raz (przy parowaniu klucz się zmienia). Gdy okres wygaśnie: iot.tuya.com → **Cloud → Project Management** → przy IoT Core **Extend trial period**, wypełnij krótką ankietę i po kilku minutach odśwież stronę.

Masz już plik `devices.json` z kreatora tinytuya (`python -m tinytuya wizard`)? Możesz go wkleić: **Ustawienia → Urządzenia → Wklej devices.json**.

## 2. Adresy IP w sieci domowej

Będąc w domowym Wi-Fi, stuknij **Ustawienia → Szukaj urządzeń**. Aplikacja odbiera rozgłoszenia urządzeń i zapisuje ich adresy IP oraz wersje protokołu. Po imporcie z chmury robi to sama.

Spoza domu rozgłoszenia nie dochodzą, więc aplikacja używa zapamiętanych adresów. Dlatego **ustaw w routerze stałe adresy** dla urządzeń Tuya: w Archer MR600 jest to rezerwacja adresów w ustawieniach serwera DHCP. Inaczej po restarcie routera urządzenie może dostać inny adres. Wtedy wystarczy ponowne wyszukiwanie w domu.

## 3. Dostęp spoza domu (Tailscale)

Starlink w zwykłym planie nie daje publicznego adresu IPv4 (CGNAT), więc z zewnątrz nie da się przekierować portu do domu. Tailscale omija to bez konfiguracji routera.

1. Załóż konto na [tailscale.com](https://tailscale.com/) (darmowy plan wystarczy) i zainstaluj aplikację **Tailscale** w telefonie.
2. W domu potrzebne jest jedno urządzenie włączone 24/7, które będzie **subnet routerem**, czyli udostępni sieć domową w Tailscale:
   - **Raspberry Pi** (np. Zero 2 W) albo inny komputer z Linuksem:
     ```bash
     curl -fsSL https://tailscale.com/install.sh | sh
     echo 'net.ipv4.ip_forward = 1' | sudo tee -a /etc/sysctl.d/99-tailscale.conf
     sudo sysctl -p /etc/sysctl.d/99-tailscale.conf
     sudo tailscale up --advertise-routes=192.168.1.0/24
     ```
     Zamiast `192.168.1.0/24` wpisz pulę swojej sieci. Jeśli adresy urządzeń zaczynają się od `192.168.1.`, to jest właśnie ta.
   - albo **tablet / telefon z Androidem stale na ładowarce**: Tailscale → menu konta → **Settings → Subnet routing → Add route** → pula sieci.
3. W panelu [login.tailscale.com](https://login.tailscale.com/admin/machines) przy tym urządzeniu: **⋯ → Edit route settings** → zaznacz pulę → **Save**.
4. Poza domem włącz Tailscale w telefonie. Tuya Pilot działa wtedy tak samo jak w domu. Gdy aplikacja wykryje, że jesteś poza siecią bez VPN, pokaże przycisk **Otwórz Tailscale**.

Jeśli sieć, w której akurat jesteś (np. u kogoś w domu), ma tę samą pulę adresów co Twoja (też `192.168.1.x`), urządzenia mogą być nieosiągalne. Wtedy pomaga zmiana puli w domowym routerze na mniej typową, np. `192.168.77.x`, a potem ponowne wyszukiwanie urządzeń.

## Ograniczenia

- Urządzenia **Zigbee** podłączone przez bramkę są widoczne na liście, ale ta wersja nimi nie steruje.
- Urządzenia, które w Smart Life działają **tylko przez Bluetooth**, nie mają sterowania przez sieć.
- Urządzenia Tuya przyjmują mało jednoczesnych połączeń lokalnych. Aplikacja łączy się na chwilę, na czas jednej operacji.

## Bezpieczeństwo

Klucze lokalne są tylko w pamięci aplikacji w telefonie, nie w tym repozytorium. **Kopia** z ustawień zawiera klucze: nie udostępniaj jej publicznie. Klucz podpisu APK (`android/tuya-pilot.keystore`) jest w repozytorium, tak jak w innych projektach, żeby kolejne wersje instalowały się jako aktualizacja.

## Budowa

```
android/                     aplikacja (Java, bez dodatkowych bibliotek)
  app/src/main/assets/index.html          interfejs (WebView)
  app/src/main/java/.../MainActivity.java most między interfejsem a rdzeniem
  app/src/main/java/.../core/             rdzeń: protokół Tuya 3.1–3.5, wyszukiwanie UDP, chmura
tests/
  CoreTests.java     testy rdzenia
  fake_device.py     udawane urządzenie Tuya (ramki pakuje biblioteka tinytuya)
  vectors.py         wzorcowe pakiety z tinytuya do porównania bajt w bajt
  cloud_mock.py      udawane API chmury, sprawdza podpis tak jak tinytuya
  ui-mock.js         udawany most do podglądu interfejsu w przeglądarce
```

Opis protokołu: [PROTOCOL.md w projekcie tinytuya](https://github.com/jasonacox/tinytuya/blob/master/PROTOCOL.md). Przed zbudowaniem APK GitHub uruchamia testy rdzenia: pakiety muszą zgadzać się z tinytuya, a odczyt i sterowanie muszą działać z udawanym urządzeniem w każdej wersji protokołu.

Testy lokalnie:

```bash
pip install tinytuya cryptography
mkdir -p build/core
javac -encoding UTF-8 -d build/core android/app/src/main/java/pl/zooolw/tuyapilot/core/*.java tests/CoreTests.java
java -cp build/core CoreTests tests
```
