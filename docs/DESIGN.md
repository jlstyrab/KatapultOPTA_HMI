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
8. **Fjärrinsyn är ett krav, inte ett tillval**: en anläggning som faller ifrån
   ska fångas, som Katapult-noderna fångas av larmcentralen.

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
   (uppkopplad via platta) LTE → HTTPS-push av status/heartbeat till Katapult/lyssnare

[Opta] ──(uppkopplad via router: WG till navet)──► heartbeat / central poller ──► larmcentralen
```

**Delar och var de bor**

| Del | Repo | Innehåll |
|---|---|---|
| OptaClient + statiskt paket | `katapult-scada` (runtime) | Klientlager mot Optans JSON i stället för Katapults API/WS; paket = dist + `project.yaml` + `scenes/*.json` |
| APK | detta repo | WebView-skal på några hundra rader Kotlin, runtimen som assets, blandat innehåll tillåtet, UDP-upptäckt, lock task, skärm alltid på, inloggningsdialogen återanvänd, nätbindning (Ethernet mot Optan, LTE mot servern), push av status |
| Firmware | `optaModbusRemoteIO` | inloggning, scenblob, nätlägesval, heartbeat (tillval), DHCP-server (tillval) |
| Byggläge | `webbstyr-energy-platform` + `katapult-scada` | "Skicka till Optan" (poller-action som `opta_map`), ev. CORS på scada-containern om plattan ska hämta scen från servern |
| Push-mottagning | `webbstyr-energy-platform` / `katapult-stack` | endpoint som tar emot plattans status-push och gör Optan till en enhet med offline-larm; matar larmcentralen |

## Beslutslogg

| # | Datum | Beslut | Varför |
|---|---|---|---|
| D1 | 2026-10-16 | Runtimen (klienten) bor på paddan, inte på Optan | Optan slipper blob-area, gzip-servering och cache-headers för 132 KB; OptaClient är samma kod oavsett värd, så Opta-hostad bundle kan läggas till senare |
| D2 | 2026-10-16 | Scenen bor i Optan som en liten blob | Utbytt platta får kortet automatiskt; Optan äger projektet; scenen är tiotals KB, inte hundratals |
| D3 | 2026-10-16 | Egen WebView-APK, inte kioskwebbläsare | Inga köpta beroenden; egen WebView får tillåta blandat innehåll (HTTPS-app → HTTP-Opta) och fetch från lokala assets, vilket Chrome blockerar. Sedan LTE-idén också: bara en egen app kan binda trafiken till rätt nätverk |
| D4 | 2026-10-16 | **Spikat (Johan):** ingen inloggning för att titta — HMI:t startar i visa-läge. Först när man trycker för att ändra något (börvärde, tidkanal, hand) kommer inloggningsrutan upp. Därefter hålls inloggningen en stund så man slipper logga in per fält. I Optan: läsning fri, skrivning kräver token | Skydd mot oavsiktlig ändring, inte kryptering (ren HTTP på kabel). Gäller teknikersidan och JSON-API:t lika. Exakt så fungerar scadans lösenläge redan (`scada_control_auth=password`: `onWrite` öppnar `LoginDialog` vid första skrivning, token cachas med `expiresAt`), så runtimen behöver ingen ny logik — bara att OptaClient.login pekar på Optan |
| D5 | 2026-10-16 | Panelläge i firmwaren (lätt sida med stora siffror) parkerat som fallback | Täcker "ändra börvärde" billigt om driftkortsvägen drar ut på tiden |
| D6 | 2026-10-16 | **Förslag, ej spikat:** Ethernet för både padda och uppkoppling; USB avvisas | Se analysen nedan |
| D7 | 2026-10-16 | **Förslag, ej spikat:** nätläge väljs vid driftsättning i Optans HMI: *Fristående* (direktkabel, fallback-IP) eller *Uppkopplad* | Ett val, inte två produkter |
| D8 | — | **Öppet:** Opta-ping, se avsnittet Övervakning | |
| D9 | 2026-10-16 | **Förslag, ej spikat:** *Uppkopplad* finns i två varianter — **via platta** (LTE-platta pushar status över HTTPS, ingen firmware) eller **via router** (4G-router med WireGuard till navet, Optan nåbar som en nod) — valet görs per anläggning | Plattan är billigast och räcker för fjärrinsyn; routern vinner där täckningen i skåpet är dålig eller Optan ska nås direkt. Se analysen "LTE-platta som upplänk" |

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
- Kandidatplatta **Lenovo Tab One 8,7" LTE** (NetOnNet art. 1055915, MPN
  ZAF10175SE, 2 290 kr 2026-10-16, laddare ingår inte): Android 14 eller
  senare, MediaTek Helio G85, 4 GB RAM, 64 GB eMMC, 8,7" IPS 1340×800 480 nits,
  5100 mAh, 15 W laddning, **1 × USB-C 2.0** (enda porten), nano-SIM 4G LTE,
  WiFi ac, Bluetooth 5.3, "Voice Call" förinstallerat (SIM med samtal/SMS
  fungerar), 320 g. Inget om laddgräns eller OTG-laddning på produktsidan.

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

**Rättelse samma dag (se LTE-analysen):** strömproblemet med en enda USB-C-port
gäller även en Ethernet-adapter. Direktkabel kräver alltså antingen en platta
som laddar i värdläge genom en PD-adapter, eller en platta med annan laddväg
(pogo-pinnar, trådlöst). Öppen fråga, bänktest.

## Analys: LTE-platta som upplänk (2026-10-16)

Idé (Johan): köp en platta med inbyggd LTE, så är plattan både HMI och
upplänk till navet. Fjärrinsyn utan router.

**Vad den ger.** Med egen APK (D3) blir plattan en rapportör: den pollar ändå
Optan och kan pusha status-JSON, larmbitar och "Opta nåbar" över HTTPS till en
Katapult-server eller lyssnare. Det är väg B i Opta-pingen, utan en rad
firmware, och rikare än en heartbeat: Optans taggar kan bli en enhet i
Katapult med dashboard, larm, röstlarm och Lill-Bert. Fjärrstyrning
(kommandokö som appen hämtar) är möjlig på samma kanal men ligger utanför.

**Vad som måste lösas**

- **Två nätverk på Android.** LTE har internet, Ethernet-kabeln till Optan har
  det inte. Android lägger standardrutten på det validerade nätet (LTE) och
  markerar Ethernet "utan internet". Appen måste **binda** Opta-trafiken till
  Ethernet-nätverket och pushen till LTE (ConnectivityManager.requestNetwork
  med TRANSPORT_ETHERNET respektive TRANSPORT_CELLULAR). Bara en egen app kan
  det; en kioskwebbläsare hade fallit här. Bänktest: att Android inte stänger
  Ethernet "utan internet" över tid.
- **En USB-C-port.** Ethernet-adaptern tar den enda porten; plattan ska ändå
  laddas dygnet runt. Kräver en adapter med PD-genomgång OCH att plattan
  laddar i värdläge. Okänt för kandidatplattan. Alternativ: WiFi till Optan
  (kräver en accesspunkt på plats, Optan har ingen), eller platta med annan
  laddväg.
- **LTE i ett apparatskåp.** Plattans antenn sitter i plattan; plåtskåp och
  pannrum i källare dämpar. En router har antennkontakt och kan sätta antennen
  utanför skåpet. Täckningen måste mätas på plats innan plattan väljs som
  upplänk.
- **Konsumentklass som upplänk.** SIM-abonnemang att hålla vid liv, Android som
  startar om, 5100 mAh på 100 % i åratal utan känd laddgräns.
- **Mottagare saknas.** Katapults API-sensorer *hämtar* från en URL; en push
  från plattan behöver en mottagande endpoint (HTTP POST med nyckel, eller
  MQTT över TLS) som skapar/uppdaterar enheten och ger offline-larm vid
  tystnad. Liten, men ny.

**Alternativet: 4G-router på DIN-skena** (Teltonika RUT-klass, 24 V,
antennkontakt, WireGuard inbyggt, ca 1 500–2 500 kr). Optan blir nåbar från
navet som en Katapult-nod (central poller eller heartbeat i firmwaren),
plattan blir vilken WiFi-platta som helst utan port- och laddproblem, och
larmcentralens mönster används rakt av. En låda till, men en industriell och
"dum" sådan.

**SMS-spåret (ur "Voice Call" i specen).** En platta med samtals-SIM kan
skicka SMS från appen: larm vid händelse och svar på "status?" utan server,
lyssnare eller TLS. Täcker inte TYST (frånvaro kan inte upptäckas av
avsändaren) men är den dummaste tänkbara larmvägen. Kräver SIM med SMS
(IoT-datakort saknar det ofta). Parkerad som tillval i väg B.

**Förslag (D9):** *Uppkopplad* i två varianter, valt per anläggning:
via platta (LTE-push, ingen firmware, billigast) eller via router (WG till
navet, Optan nåbar, bäst täckning). Fristående oförändrat.

## Övervakning: "Opta-ping"

Behov: en Opta som faller ifrån ska fångas som en Katapult-nod fångas av
vakten + larmcentralen. Mönstret är givet: gles rapport, TYST efter två
uteblivna, ALDRIG HÖRD tills första.

Vägar:

| | A: heartbeat i firmwaren | B: APK:n som gateway | C: platsens Katapult-nod pollar |
|---|---|---|---|
| Vem rapporterar | Optan själv | plattan, om Optan svarar | modbus-pollern (finns redan) |
| Firmware | HTTP-POST eller UDP-paket, HMAC-signerat med nyckel i persist, **ingen TLS** (TLS på Optan = certunderhåll) | inget | inget |
| Transport | ren HTTP/UDP ut genom platsens router | HTTPS gratis från Android, över plattans LTE eller platsens WiFi | WireGuard |
| Innehåll | uptime, scan OK, firmware, summalarm (HR 99/228, DI 164–179) | hela status-JSON:en + "Opta nåbar" | allt Katapult redan ser |
| Faller om | — | plattan dör (men då är HMI:t nere, vilket också ska larma) | kräver nod eller router på plats |
| Lyssnare | behövs: publik, matar larmcentralen | Katapult-server med push-endpoint (offline-larm finns per enhet) | finns |

Rekommendation: **B först** när plattan har LTE (ingen firmware, rikast
data), **A** när anläggningen har router men ingen platta som rapportör,
**C** när Optan sitter bakom en WG-router eller nod. Takt som vakten: två
gånger per dygn + vid boot för heartbeat; status-push oftare om Katapult ska
visa värden (glesa bakgrundskontroller enligt Johan, inte minutvis).

Öppet: var lyssnaren/push-endpointen bor. Navet har bara WireGuard-adress;
demo-servern är publik. Push från plattan går över HTTPS och kan träffa
Katapults publika API direkt; heartbeat från Optan (A) utan TLS kräver HMAC
och rate limit.

## Driftsättning (skiss)

I Optans HMI, sidan Nätverk, ett val **Fristående / Uppkopplad**:
- *Fristående*: DHCP av (fallback 192.168.200.1), ingen heartbeat, klockan
  sätts för hand.
- *Uppkopplad via router*: DHCP på, heartbeat-mål + nyckel, NTP.
- *Uppkopplad via platta*: som fristående i Optan; appen får server-URL +
  nyckel och pushar.

På plattan: installera APK → appen hittar Optan (UDP) eller IP knappas in →
logga in en gång → (uppkopplad via platta: server + nyckel) → lås appen.
Scenen hämtas från Optan.

## Öppna frågor

- [ ] Fast IP på Androids Ethernet-gränssnitt: vilka tillverkare (Samsung ja?). Avgör plattval, eller DHCP-server i Optan.
- [ ] **Laddning i USB-värdläge** med Ethernet-adapter (PD-genomgång) — gäller varje platta med en enda USB-C-port, även kandidaten. Bänktest före köp i volym.
- [ ] Android håller Ethernet "utan internet" uppe bredvid LTE över dygn? Bänktest med nätbindning i appen.
- [ ] LTE-täckning i apparatskåp/pannrum — mät på plats; annars router med extern antenn.
- [ ] Laddgräns på Lenovo Tab One (batteriskydd)? Inte på produktsidan.
- [ ] Auto-MDIX i Optans PHY — rak kabel direkt till plattan? Bänktest.
- [ ] Klockan vid strömavbrott utan NTP (fristående anläggning med tidkanaler). Uppkopplad via platta: appen kan sätta Optans klocka från plattans.
- [ ] Flashutrymme för scenblob i bank 2 — verifiera mot linkerscript; sektorstorlek 128 KB.
- [ ] Token i `Authorization`-huvud (kräver OPTIONS) eller som formulärfält?
- [ ] Hur länge inloggningen hålls efter sista tryck (förslag: 5–10 min inaktivitet, så en lämnad panel inte står armerad; scadan styr i dag med `ui_session_minutes`).
- [ ] Lägsta WebView-version vi stöder; sänka byggmålet från ES2022?
- [ ] Push-endpoint i Katapult: HTTP POST med nyckel eller MQTT över TLS? Enhetstyp "opta via platta" med offline-larm.
- [ ] Var Opta-ping-lyssnaren bor (nav bakom WG vs demo-servern).
- [ ] Vilken Opta-variant i projekten (RS485/WiFi) — påverkar bara WiFi-AP-idén.
- [ ] Hur "Skicka till Optan" hanterar scener som binder till taggar Optan saknar (entitetsvalidering som Lill-Berts ritverktyg har).
- [ ] SIM: datakort eller med SMS (för SMS-spåret)?

## Etapper (preliminära, inget spikat)

| Etapp | Innehåll | Repo |
|---|---|---|
| E0 | Bänktest: direktkabel padda–Opta med fast IP; laddning i värdläge; lokalt serverad sida pollar och skriver mot bänk-Optan från annan origin; Ethernet + LTE samtidigt | — |
| E1 | OptaClient i runtimen + statiskt paket; scen bunden till Optans taggnamn (samma konvention som Katapults import: `pid1_sp`) | katapult-scada |
| E2 | Inloggning i firmwaren (lösenord i persist, `POST /api/login` → kortlivad token, skrivning kräver token, teknikersidan får inloggning) | optaModbusRemoteIO |
| E3 | Scenblob i flash + `GET/POST /api/hmi/scene`; "Skicka till Optan" i byggläget via pollern | optaModbusRemoteIO + webbstyr-energy-platform + katapult-scada |
| E4 | APK: WebView-skal, assets, UDP-upptäckt, lock task, skärm på, inloggning, nätbindning | detta repo |
| E5 | Uppkopplad via platta: status-push över HTTPS + push-endpoint i Katapult med offline-larm (väg B) | detta repo + webbstyr-energy-platform |
| E6 | Uppkopplad via router: nätläge i Optans HMI + heartbeat (A) + lyssnare | optaModbusRemoteIO + katapult-stack (nav) |
| E7 | Tillval: DHCP-server i Optan (noll konfig på plattan), Opta-hostad bundle, SMS-larm från appen | optaModbusRemoteIO / detta repo |

## Parkerade idéer

- Panelläge i firmwaren (D5) — fallback.
- Bundle hostad på Optan — tillval när E3:s blob-mekanik finns.
- WiFi-AP på Opta WiFi — firmwaren är Ethernet-only; radio på PLC.
- USB-transport — se analysen.
- Industripanel (Modbus TCP, 10 års livslängd) — riktmärket för "dum", men ritas i tillverkarens verktyg.
- Plattan hämtar scen direkt från Katapult-servern ("Hämta ny version") — överflödigt om scenen bor i Optan.
- SMS-larm/SMS-status från appen — tillval i väg B, kräver SIM med SMS.
- Fjärrstyrning via plattans push-kanal (kommandokö) — möjlig senare, inte i scope.
