# DDMS Ratio — aplikacja Android (budowanie APK przez GitHub Actions)

Ten projekt pakuje aplikację `www/index.html` do natywnego pliku **APK**
przy użyciu [Capacitor](https://capacitorjs.com/), budowanego automatycznie
przez GitHub Actions — **nie potrzebujesz Android Studio ani lokalnego
SDK**, cały build dzieje się na serwerach GitHub. To wyłącznie aplikacja na
Androida — nie ma tu osobnej wersji przeglądarkowej/strony WWW.

## Co jest w tym repozytorium

| Ścieżka | Przeznaczenie |
|---|---|
| `www/index.html` | Cała aplikacja (HTML+CSS+JS w jednym pliku) — wsad dla Capacitora |
| `www/html5-qrcode.min.js` | Biblioteka skanująca, używana tylko jako zapasowy mechanizm (patrz niżej) |
| `www/exceljs.min.js` | Biblioteka do generowania plików Excel (offline) |
| `www/manifest.json`, `www/service-worker.js`, `www/icon-*.png` | Pliki PWA wymagane wewnętrznie przez Capacitor — nie hostujemy ich jako osobnej strony |
| `package.json` | Zależności Node/Capacitor |
| `capacitor.config.ts` | Konfiguracja projektu Capacitor (appId, nazwa, folder www) |
| `android-patches/MainActivity.java` | Podmienia wygenerowany `MainActivity` — przyznaje dostęp do kamery i obsługuje natywny zapis plików |
| `android-patches/patch_manifest.py` | Dodaje uprawnienia `CAMERA`/`FLASHLIGHT` do wygenerowanego `AndroidManifest.xml` |
| `.github/workflows/build-apk.yml` | Workflow CI: generuje projekt Android, patchuje go, buduje APK, wrzuca jako artefakt do pobrania |

## Jak zbudować APK — krok po kroku

1. Stwórz nowe repozytorium na GitHub (albo użyj istniejącego — np. tego z błędem 404, ale wtedy najpierw je wyczyść).
2. Skopiuj **wszystkie** pliki i foldery z tego projektu do głównego katalogu repo, zachowując strukturę folderów (`www/`, `android-patches/`, `.github/workflows/` muszą zostać jako podfoldery — to jedyne wyjątki od "płaskiej" struktury, bo wymaga ich Capacitor/GitHub Actions).
3. Commit i push na branch `main`:
   ```bash
   git add .
   git commit -m "Dodaj build Android (Capacitor)"
   git push
   ```
4. Wejdź w zakładkę **Actions** w repozytorium na GitHub → poczekaj, aż workflow **build-apk** się zakończy (kilka minut, zielony ptaszek).
5. Otwórz zakończony przebieg (run) → przewiń do sekcji **Artifacts** → pobierz **ddms-ratio-debug-apk**. Rozpakuj — w środku jest `app-debug.apk`.
6. Skopiuj APK na telefon z Androidem i zainstaluj (trzeba zezwolić na "Instalację z nieznanych źródeł" w aplikacji, której użyjesz do otwarcia pliku).

## Skanowanie i zdjęcia używają prawdziwego natywnego aparatu (nie WebView)

Podgląd kamery renderowany wewnątrz WebView (przez `getUserMedia`) ma z
zasady gorszą jakość, ostrość i autofocus niż systemowy aparat Androida —
to ograniczenie przeglądarkowego API, którego nie da się w pełni obejść
samą konfiguracją. Dlatego aplikacja korzysta teraz z dwóch prawdziwych,
natywnych komponentów Androida (poprzez wtyczki Capacitor):

- **Krok 1 i 2 (skan DDMS i SN)** → `@capacitor-mlkit/barcode-scanning` —
  otwiera pełnoekranowy, natywny skaner Google (ML Kit), z pełną jakością
  natywnej kamery, własnym autofocusem i własną latarką. Po zeskanowaniu
  aplikacja automatycznie wraca do odpowiedniego ekranu z wynikiem.
- **Krok 3 (zdjęcie)** → `@capacitor/camera` — otwiera systemową aplikację
  aparatu telefonu (tę samą, której używasz robiąc zwykłe zdjęcia), z pełną
  jakością, HDR, autofocusem itd. Zrobione zdjęcie wraca do aplikacji i
  dalej przechodzi przez ten sam proces co dotychczas (podgląd, zapis do
  IndexedDB, osadzenie w Excelu).

Każdy z tych kroków ma też widoczny przycisk ("Skanuj kod DDMS" / "Skanuj
numer SN" / "Otwórz aparat") — skaner **nie uruchamia się automatycznie**;
to Ty decydujesz, kiedy go odpalić, co daje czas na odsunięcie telefonu od
poprzedniego kodu przed kolejnym skanem.

Kod oparty na `getUserMedia` (`www/html5-qrcode.min.js`) pozostaje w
projekcie wyłącznie jako zapasowy mechanizm na wypadek, gdyby wtyczka
natywna z jakiegoś powodu była niedostępna na danym urządzeniu — w
normalnej pracy aplikacji nie jest używany.

### Rzeczy, na które warto zwrócić uwagę

- **Pierwsze uruchomienie skanera** może poprosić o chwilę dostępu do
  internetu — Google pobiera wtedy mały moduł skanowania ML Kit przez Usługi
  Google Play (jednorazowo, potem działa już offline). Aplikacja sama to
  sprawdza i w razie potrzeby inicjuje instalację przed otwarciem skanera —
  to standardowe zalecenie autorów wtyczki `@capacitor-mlkit/barcode-scanning`.
- **Wersje pakietów w `package.json` oraz dokładne API wtyczek
  (`BarcodeScanner.scan()`, `Camera.getPhoto()`, kształt zwracanych danych)
  zostały zweryfikowane bezpośrednio w rejestrze npm i w plikach `.d.ts`
  faktycznie opublikowanych paczek** — nie są zgadywane z pamięci. Mimo to
  jest to pierwsza integracja tych konkretnych wtyczek z tym projektem i
  nie miałem możliwości realnie zbudować pełnego APK lokalnie (brak Android
  SDK/Gradle w tym środowisku). Jeśli build w GitHub Actions i tak się
  wysypie na którymś z kroków Capacitor/Gradle, wklej log — naprawimy to
  tak samo jak dotychczasowe błędy w tej rozmowie.
- `android-patches/MainActivity.java` (przyznawanie dostępu do kamery w
  WebView) został zachowany jako mechanizm zapasowy — nie przeszkadza, nawet
  jeśli główną ścieżką są teraz wtyczki natywne.

## Gdzie i jak zapisywany jest wygenerowany Excel

Zwykły link `<a download>` **nie działa** wewnątrz Android WebView — nie ma
tam domyślnej obsługi pobierania plików. Dlatego `www/index.html` zapisuje
plik w następującej kolejności:

1. **Mechanizm główny.** Otwiera się prawdziwe **systemowe okno Androida
   "Zapisz jako"** (Storage Access Framework) — to samo okno, którego używa
   dowolna aplikacja do zapisywania plików. Inspektor sam wybiera folder
   (np. Pobrane, Dysk Google, karta SD, konkretny podfolder) oraz może
   zmienić nazwę pliku przed zapisaniem. Technicznie: `www/index.html`
   wywołuje most JS→natywny `window.AndroidSaveFile.saveFile(...)`
   zdefiniowany w `android-patches/MainActivity.java`, który uruchamia
   `Intent.ACTION_CREATE_DOCUMENT` i zapisuje bajty pliku dokładnie tam,
   gdzie wskaże użytkownik.
2. **Awaryjnie, gdyby most natywny z jakiegoś powodu był niedostępny** → plik
   zapisywany jest przez wtyczkę `@capacitor/filesystem` do prywatnego
   folderu aplikacji, a następnie otwiera się natywne okno
   "Zapisz/Udostępnij" (`@capacitor/share`), żeby i tak dało się go
   przenieść w widoczne miejsce.

Przed samym generowaniem pliku aplikacja pyta, do której **zmiany (A/B)**
zaliczyć eksport — wybór trafia do nagłówka arkusza i jest zapamiętywany
jako podpowiedź na następny raz.

## Dlaczego patch kamery jest potrzebny

Nawet z zadeklarowanymi uprawnieniami, Android WebView domyślnie blokuje
dostęp do kamery (`getUserMedia()`), dopóki aplikacja hostująca nie przyzna
go jawnie. Plik `android-patches/MainActivity.java` nadpisuje obsługę
uprawnień WebView, żeby przyznawać dostęp do kamery, i prosi o uprawnienie
systemowe `CAMERA` przy pierwszym uruchomieniu. Workflow kopiuje ten plik na
miejsce (i patchuje manifest) po każdym wygenerowaniu natywnego projektu
przez Capacitor — nigdy nie musisz ręcznie dotykać wygenerowanego kodu
natywnego.

## Dane lokalne

Aplikacja przechowuje dane inspekcji w `IndexedDB` telefonu, w piaskownicy
własnej aplikacji. Odinstalowanie aplikacji usuwa te dane — tak jak w
każdej aplikacji na Androida.

## Synchronizacja między telefonami

Projekt zawiera opcjonalną synchronizację przez WebSocket. Dane nadal są
zapisywane lokalnie, a połączenie sieciowe przekazuje kopię rekordu do innych
telefonów w tym samym pokoju.

1. Uruchom `sync-server` na komputerze w tej samej sieci Wi‑Fi:
   ```bash
   cd sync-server
   npm install
   npm start
   ```
2. W aplikacji wybierz **Menu → Ustawienia zmiany**.
3. Wpisz na każdym telefonie ten sam adres, np. `ws://192.168.1.10:8787`,
   oraz tę samą nazwę pokoju, np. `ZLECENIE-001`.
4. Po zapisaniu skanu pozostałe telefony w tym pokoju otrzymają rekord wraz ze
   zdjęciem i pokażą go na liście.

### Tryb bez komputera

Nie trzeba uruchamiać `sync-server`, jeśli jeden telefon ma pełnić rolę
serwera. Na telefonie głównym wybierz **Menu → Ustawienia zmiany → Uruchom
serwer na tym telefonie**. Aplikacja pokaże adres `ws://...:8787`. Wpisz ten
adres oraz tę samą nazwę pokoju na pozostałych telefonach. Telefony muszą być
w tej samej sieci Wi‑Fi albo połączone z hotspotem telefonu-serwera.

Serwer w telefonie działa w pamięci aplikacji i zatrzymuje się po wybraniu
opcji zatrzymania lub wymuszeniu zamknięcia aplikacji. Przed użyciem
produkcyjnym należy dodać trwałą bazę i autoryzację.

## Zmiana nazwy/ikony aplikacji

- **Nazwa aplikacji**: edytuj `appName` w `capacitor.config.ts`.
- **Identyfikator pakietu (appId)**: edytuj `appId` w `capacitor.config.ts`
  **oraz** nazwę pakietu wewnątrz `android-patches/MainActivity.java`
  (pierwsza linia: `package com.ddms.ratio;`) **oraz** ścieżkę docelową w
  kroku "Enable camera access" w `.github/workflows/build-apk.yml` — wszystkie
  trzy muszą się zgadzać.
- **Ikona aplikacji**: Capacitor używa domyślnej ikony, dopóki nie dodasz
  własnej. Daj znać, jeśli chcesz pomoc z wygenerowaniem ikony z logo.
