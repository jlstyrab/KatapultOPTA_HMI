# KatapultOPTA_HMI — designlogg

**Status:** idéfas, inget byggt. Senast uppdaterad 2026-10-16.
Dokumentet är den levande sanningen för spåret: beslut spikas här med datum,
öppna frågor står kvar tills de är stängda, parkerade idéer glöms inte.

## Syfte

Ett operatörs-HMI för Arduino Opta (firmwaren i `optaModbusRemoteIO`) på en
läsplatta, ritat som ett driftkort i Katapults byggläge. Ska vara så nära en
"dum" HMI som möjligt: inga beroenden och inget underhåll på plattan, ska sitta
och fungera år efter år. Första konkreta behov: **ändra börvärde från panelen**
i ett projekt.

## Principer

1. **Plattan är en skärm. Optan äger projektet** (scen, inloggning, nätläge).
   En utbytt platta ska bara behöva APK:n och kabeln.
2. **Inga tredjepartsberoenden på plattan**: egen APK, ingen kioskwebbläsare,
   inget konto, inga bibliotek. Enda beroendet är Androids WebView.
3. **Samma driftkort som i Katapult**: ritas i byggläget, samma runtime, samma
   symboler. Ingen parallell symbolkatalog.
4. **Minimal firmware**: Optan får bara det den måste äga.
5. **Pollningsbudget**: en klient i taget mot Optan, status var 2–5 s.
   Teknikersidan stängd när plattan kör.
6. **Fristående eller uppkopplad är ett val vid driftsättning**, inte två
   produkter.
7. Arnes UX-regler för HMI:t gäller (korta texter, hjälp bakom toggle, kvitto
   direkt, utgången säger vem som driver den).

## Arkitektur

```
[Katapult byggläge] ──"Skicka till Optan" (via pollern, host-net)──► [Opta]
                                                                      │ scenblob (flash bank 2)
                                                                      │ JSON-API (/api/status, /api/fb, /api/service …)
                                                                      │ inloggning (token)
                                                                      │ nätläge: fristående | uppkopplad
                       HTTP poll 2–5 s + formulär-POST vid skrivning   │
[APK på paddan] ───────────────────────────────────────────────────────┘
   WebView + runtime (dist) + OptaClient
   UDP-upptäckt port 5000 hittar Optan
   (tillval) HTTPS-heartbeat till Katapult-server

[Opta, uppkopplad] ──(tillval) heartbeat──► lyssnare ──► larmcentralen på navet
```

**Delar och var de bor**

| Del | Repo | Innehåll |
|---|---|---|
| OptaClient + statiskt paket | `katapult-scada` (runtime) | Klientlager mot Optans JSON i stället för Katapults API/WS; paket = dist + `project.yaml` + `scenes/*.json` |
| APK | detta repo | WebView-skal på några hundra rader Kotlin, runtimen som assets, blandat innehåll tillåtet, UDP-upptäckt, lock task, skärm alltid på, inloggningsdialogen återanvänd |
| Firmware | `optaModbusRemoteIO` | inloggning, scenblob, nätlägesval, heartbeat (tillval), DHCP-server (tillval) |
| Byggläge | `webbstyr-energy-platform` + `katapult-scada` | "Skicka till Optan" (poller-action som `opta_map`), ev. CORS på scada-containern om plattan ska hämta scen från servern |

## Beslutslogg

| # | Datum | Beslut | Varför |
|---|---|---|---|
| D1 | 2026-10-16 | Runtimen (klienten) bor på paddan, inte på Optan | Optan slipper blob-area, gzip-servering och cache-headers för 132 KB; OptaClient är samma kod oavsett värd, så Opta-hostad bundle kan läggas till senare |
| D2 | 2026-10-16 | Scenen bor i Optan som en liten blob | Utbytt platta får kortet automatiskt; Optan äger projektet; scenen är tiotals KB, inte hundratals |
| D3 | 2026-10-16 | Egen WebView-APK, inte kioskwebbläsare | Inga köpta beroenden; egen WebView får tillåta blandat innehåll (HTTPS-app → HTTP-Opta) och fetch från lokala assets, vilket Chrome blockerar |
| D4 | 2026-10-16 | Inloggning i Optan: läsning fri, skrivning kräver token | Skydd mot oavsiktlig ändring, inte kryptering (ren HTTP på kabel). Gäller teknikersidan och JSON-API:t lika |
| D5 | 2026-10-16 | Panelläge i firmwaren (lätt sida med stora siffror) parkerat som fallback | Täcker "ändra börvärde" billigt om driftkortsvägen drar ut på tiden |
| D6 | 2026-10-16 | **Förslag, ej spikat:** Ethernet för både padda och uppkoppling; USB avvisas | Se analysen nedan |
| D7 | 2026-10-16 | **Förslag, ej spikat:** nätläge väljs vid driftsättning i Optans HMI: *Fristående* (direktkabel, fallback-IP) eller *Uppkopplad* (DHCP från platsens router + heartbeat) | Ett val, inte två produkter |
| D8 | — | **Öppet:** Opta-ping, se avsnittet Övervakning | |

## Verifierade fakta som designen vilar på

Kollade i koden 2026-10-15/16. Ändras de, ändras designen.

- Runtimen läser `project.yaml` och `scenes/*.json` som **statiska filer** bredvid
  sig själv (`runtime/src/main.ts`, fetchText), inte via API. Ett paket = en mapp.
- Alla API-anrop i runtimen går genom **en klass**, `KatapultClient`, med ett
  dussin metoder (bootstrap, fetchCatalog, fetchAlarms, switchDevice, writeValue,
  fetchTimeChannels/putTimeChannel, fetchControlAuth, login, fetchHistory, WS).
  `main.ts` äger resten. OptaClient ersätter den klassen.
- Runtimens dist: **480 KB rå, 132 KB gzippad**. Byggmål **ES2022** →
  WebView från ca 2021 eller senare. Kan sänkas med en rad i bygget.
- Optans JSON-svar skickar redan `Access-Control-Allow-Origin: *`
  (`web_server.cpp` ~rad 1997). Skrivningar är **formulärkodade** → ingen
  CORS-preflight. En token i `Authorization`-huvud kräver OPTIONS-svar i
  firmwaren; token som formulärfält kräver inget.
- Optan **utan DHCP-server faller passivt tillbaka till 192.168.200.1**
  (`netconfig.h`). Fast IP finns. Verktygsläge via USER-knappen 5 s.
- Optan svarar på **UDP-upptäckt port 5000** (`KATAPULT_DISCOVER_v1`).
- Firmwaren är **Ethernet-only**. Ingen DHCP-server, ingen WiFi-AP, ingen auth.
- **Scan-tråden** (10 ms, realtidsprio) är skild från nätverkstråden. Ett trögt
  HMI rör aldrig reglerloopen, men webben delar nätverkstråden och ett fåtal
  lwip-sockets med Modbus-servern och RTU-klienten.
- Dagens tekniker-HMI: **115 KB okomprimerad** i firmwaren, pollar status var
  2 s och IO-redigeraren var 3 s.
- `persist` är nyckel-värde med **64 byte per värde**, 128 poster — passar inte
  en scen. Bank 2 i flashen har plats bredvid persist-arean (sista 256 KB);
  dual-bank gör att skrivning där inte stör koden i bank 1. **Utrymmet ska
  verifieras mot linkerscriptet.**
- Firmware.bin ca 482 KB.
- Larmcentralen på navet (`katapult-stack/docs/nav.md`): vakten rapporterar
  **två gånger per dygn + tio minuter efter omstart** via POST `/report`
  (WireGuard, port 8090); läge **TYST efter 26 h** (två uteblivna), **ALDRIG
  HÖRD** om vakten saknas. Johan: ett par kontroller per dygn räcker.

## Analys: USB mellan padda och Opta (2026-10-16)

Idé: USB-C mellan padda och Opta så Ethernet-porten blir ledig för uppkoppling.

Emot:
- **Plattans ström.** Som USB-värd mot Optan upptar plattan sin enda USB-C-port.
  Laddning samtidigt kräver en PD-dock med dataväg, och många Android-plattor
  laddar inte alls i värdläge. Det blir en extra låda och ett beroende, precis
  det vi vill undvika.
- **Ny transport i firmwaren och i appen.** Optans USB är i dag programmerings-
  och fickpanelsport (seriell CLI; 1200 baud går till bootloadern). JSON över
  seriell + USB-värdkod i APK:n + brygga till WebView är ett parallellt spår
  till HTTP som måste underhållas.
- **Backmatning.** Plattan matar 5 V på VBUS till en Opta som samtidigt har
  24 V. Oklart i dokumentationen, ska inte behöva utredas.

För: Ethernet-porten fri. Men samma sak löses med en **oadresserad
DIN-switch** (24 V, industriklass, inga inställningar, decenniers livslängd),
och en uppkopplad anläggning har ändå en router. Då får plattan dessutom
DHCP och behöver ingen fast IP.

**Förslag (D6):** Ethernet i båda lägena. Fristående = direktkabel med
fallback-IP. Uppkopplad = switch/router, plattan på LAN via Ethernet-adapter
(kabel före WiFi för år-efter-år). USB utgår.

## Övervakning: "Opta-ping"

Behov: en Opta som faller ifrån ska fångas som en Katapult-nod fångas av
vakten + larmcentralen. Mönstret är givet: gles rapport, TYST efter två
uteblivna, ALDRIG HÖRD tills första.

Vägar:

| | A: heartbeat i firmwaren | B: APK:n som gateway | C: platsens Katapult-nod pollar |
|---|---|---|---|
| Vem rapporterar | Optan själv | plattan, om Optan svarar | modbus-pollern (finns redan) |
| Firmware | HTTP-POST eller UDP-paket, HMAC-signerat med nyckel i persist, **ingen TLS** (TLS på Optan = certunderhåll) | inget | inget |
| Transport | ren HTTP/UDP ut genom platsens router | HTTPS gratis från Android | WireGuard |
| Innehåll | uptime, scan OK, firmware, summalarm (HR 99/228, DI 164–179) | samma + "Opta nåbar" | allt Katapult redan ser |
| Faller om | — | plattan dör (men då är HMI:t nere, vilket också ska larma) | kräver nod på plats |
| Lyssnare | behövs: publik, matar larmcentralen | Katapult-server (offline-larm finns per enhet) | finns |

Rekommendation: **A som mål** (Optan är anläggningen, PLC:n lever längst),
**B som nollfirmware-steg** eftersom plattan ändå pollar Optan, **C** när det
finns en nod på plats. Takt som vakten: två gånger per dygn + vid boot.

Öppet: var lyssnaren bor. Navet har bara WireGuard-adress; demo-servern är
publik. En publik HTTP-port utan TLS kräver HMAC och rate limit.

## Driftsättning (skiss)

I Optans HMI, sidan Nätverk, ett val **Fristående / Uppkopplad**:
- *Fristående*: DHCP av (fallback 192.168.200.1), ingen heartbeat, klockan
  sätts för hand.
- *Uppkopplad*: DHCP på, heartbeat-mål + nyckel, NTP.

På plattan: installera APK → appen hittar Optan (UDP) eller IP knappas in →
logga in en gång → lås appen. Scenen hämtas från Optan.

## Öppna frågor

- [ ] Fast IP på Androids Ethernet-gränssnitt: vilka tillverkare (Samsung ja?). Avgör plattval, eller DHCP-server i Optan.
- [ ] Auto-MDIX i Optans PHY — rak kabel direkt till plattan? Bänktest.
- [ ] Klockan vid strömavbrott utan NTP (fristående anläggning med tidkanaler).
- [ ] Flashutrymme för scenblob i bank 2 — verifiera mot linkerscript; sektorstorlek 128 KB.
- [ ] Token i `Authorization`-huvud (kräver OPTIONS) eller som formulärfält?
- [ ] Lägsta WebView-version vi stöder; sänka byggmålet från ES2022?
- [ ] Plattmodell: laddgräns ("skydda batteriet"), LCD, dockning, 24 V-matad nätadapter.
- [ ] Var Opta-ping-lyssnaren bor (nav bakom WG vs demo-servern).
- [ ] Vilken Opta-variant i projekten (RS485/WiFi) — påverkar bara WiFi-AP-idén.
- [ ] Hur "Skicka till Optan" hanterar scener som binder till taggar Optan saknar (entitetsvalidering som Lill-Berts ritverktyg har).

## Etapper (preliminära, inget spikat)

| Etapp | Innehåll | Repo |
|---|---|---|
| E0 | Bänktest: direktkabel padda–Opta med fast IP; lokalt serverad sida pollar och skriver mot bänk-Optan från annan origin | — |
| E1 | OptaClient i runtimen + statiskt paket; scen bunden till Optans taggnamn (samma konvention som Katapults import: `pid1_sp`) | katapult-scada |
| E2 | Inloggning i firmwaren (lösenord i persist, `POST /api/login` → kortlivad token, skrivning kräver token, teknikersidan får inloggning) | optaModbusRemoteIO |
| E3 | Scenblob i flash + `GET/POST /api/hmi/scene`; "Skicka till Optan" i byggläget via pollern | optaModbusRemoteIO + webbstyr-energy-platform + katapult-scada |
| E4 | APK: WebView-skal, assets, UDP-upptäckt, lock task, skärm på, inloggning | detta repo |
| E5 | Nätläge Fristående/Uppkopplad + heartbeat (A) + lyssnare | optaModbusRemoteIO + katapult-stack (nav) |
| E6 | Tillval: DHCP-server i Optan (noll konfig på plattan), Opta-hostad bundle | optaModbusRemoteIO |

## Parkerade idéer

- Panelläge i firmwaren (D5) — fallback.
- Bundle hostad på Optan — tillval när E3:s blob-mekanik finns.
- WiFi-AP på Opta WiFi — firmwaren är Ethernet-only; radio på PLC.
- USB-transport — se analysen.
- Industripanel (Modbus TCP, 10 års livslängd) — riktmärket för "dum", men ritas i tillverkarens verktyg.
- Plattan hämtar scen direkt från Katapult-servern ("Hämta ny version") — överflödigt om scenen bor i Optan.
