# KatapultOPTA_HMI

Operatörs-HMI för Arduino Opta på en läsplatta. Driftkortet ritas i Katapults
byggläge och skickas till PLC:ns flash ("Skicka till Optan"); plattan kör en
liten Android-app som hämtar kortet därifrån. Målet är en "dum" HMI: inga
beroenden eller underhåll på plattan, Optan äger projektet, en utbytt platta
får kortet automatiskt.

Allt som är tänkt, beslutat och verifierat står i **[docs/DESIGN.md](docs/DESIGN.md)**.

## Installera på plattan

1. Ladda ner senaste **KatapultOPTA_HMI.apk** från
   [Releases](../../releases) (förhandsreleasen *Senaste bygget* uppdateras vid varje push till main).
2. Tillåt installation från okända källor när Android frågar, öppna filen.
3. Starta appen. Den söker Optan på nätet med Katapults UDP-upptäckt och
   visar driftkortet som ligger i PLC:n. Hittas ingen: ange adressen
   (direktkabel utan router: Optan svarar på `192.168.200.1`).
4. **Inställningar:** håll **volym ned i 3 s** — Optans adress, Sök igen,
   kiosk (fäst app) på/av, version.
5. Vill du ha appen som startskärm (autostart efter strömavbrott, kiosk utan
   verktyg): välj den som hem-app när Android frågar. Äkta kiosk utan utväg:
   `adb shell dpm set-device-owner se.jlstyr.katapultopta/.MainActivity`
   på en nyinstallerad platta.

Att titta kräver ingen inloggning. Första ändringen (börvärde, relä,
tidkanal) ber om Optans lösenord: 1111 Operatör, 2222 Tekniker, 9999 Service
(standard — byt i Optans HMI under Inställningar → Säkerhet).

## Bygga

GitHub Actions bygger vid varje push (`.github/workflows/apk.yml`). Lokalt:
JDK 17 + Android SDK (platform 34, build-tools 34), sedan
`gradle -p apk assembleDebug`. Runtimen synkas in från katapult-scada med
`tools/sync-runtime.sh` (bygger vite-bundlen och kopierar till
`apk/app/src/main/assets/www`).

Appen har inga bibliotek: en `Activity`, en `WebView`, en lokal assetserver
på 127.0.0.1, UDP-upptäckt och nätbindning — se `apk/app/src/main/kotlin`.

## Syskonrepon

`optaModbusRemoteIO` (firmware: inloggning, MANUAL-relä, driftkortspaketet i
flash), `katapult-scada` (runtime med OptaClient, byggläget),
`webbstyr-energy-platform` (Katapult: Skicka till Optan via pollern),
`katapult-stack` (nav och larmcentral).

MIT-licens, JL STYR AB.
