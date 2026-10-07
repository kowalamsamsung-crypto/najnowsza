# Serwer synchronizacji DDMS Ratio

Uruchom na komputerze widocznym dla telefonów w tej samej sieci Wi‑Fi:

```bash
npm install
npm start
```

Domyślny port to `8787`. W aplikacji otwórz **Menu → Ustawienia zmiany** i na każdym telefonie wpisz np. `ws://192.168.1.10:8787` oraz tę samą nazwę pokoju, np. `ZLECENIE-001`.

Serwer przechowuje dane w pamięci procesu. Do produkcji dodaj trwałą bazę, autoryzację i `wss://`.
