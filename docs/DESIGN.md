# KatapultOPTA_HMI — designlogg

**Status:** E1 (OptaClient), E2 (inloggning + MANUAL-relä i firmwaren) och E3
(driftkortspaketet i Optans flash + "Skicka till Optan") byggda och verifierade
2026-10-16, E3 end-to-end via Pi5:s byggläge → plattform → poller → bänk-Opta →
runtime. Kvar: E4 APK, E5/E6 uppkoppling + Opta-ping. Senast uppdaterad 2026-10-16.
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
| D10 | 2026-10-16 | Taggnamn direkt mot Optan = Katapults importkonvention: `slug(blocknamn)_pv/_out/_status/_alarm` + parameternycklarna ur `/api/modbus-map`, kurvpunkter `_x1…_y6` + `_npts`, reläer `do{n}_status`, tidkanaler `tidkanal{i}_status` (Optans index 0–7), övrig I/O = Optans namn i gemener (`ai_1`, `pt1000`, `ext_ai_1`, `vai_2`, `di_1`, `ao_1`) | En scen ritad mot plattformens Opta-enhet körs oförändrad direkt mot PLC:n; enhetsnamnet är konfigurerbart (`?device=`, `connection.optaDevice`) så det kan vara samma som plattformens device_id |
| D11 | 2026-10-16 | Styr-token skickas som formulärfält `token` på skrivningar, inte i `Authorization`-huvud | Formulär-POST är en "simple request" utan CORS-preflight; firmwaren slipper OPTIONS. Stänger den öppna frågan |
| D12 | 2026-10-16 | Reläer styrs INTE via HTTP förrän firmwaren har en väg för MANUAL-källan (samma semantik som Modbus-coil 0–3). OptaClient.switchDevice avvisar med tydligt fel | Enda HTTP-vägen i dag är hand-läget, som trumfar skydd — fel verktyg för en operatörspanel. Firmwaretillägg i E2 |
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
- **RST-frekvensen i praktiken (bänk, 2026-10-16):** med Pi5:s poller igång
  mot samma Opta och runtimen pollande 2–3 anrop var 3:e sekund från Chrome
  fick ungefär vart fjärde anrop `ERR_CONNECTION_RESET`. Omförsöken i
  OptaClient (3 försök, 400 ms-stegrad paus) döljer det helt; ett enskilt
  anrop tar 50–180 ms. Misstänkt bidrag: Chromes reservanslutning (backup
  connect job) som tar en extra socket. Mät igen från APK:ns WebView och utan
  plattformens poller.
- Scenkoordinater `x`/`y` är objektets **centrum**, inte hörnet.
- **Bygglägets knapp verifierad 2026-10-16** från Pi5:s mux
  (`http://192.168.1.109:10000/editor.html?scene=hyst-test`): enhetslista med
  Opta testbänk förvald (ur scenens bindningar), andra enheter märkta "saknar
  lösenord", Skicka → "Klart: 789 B (2319 B okomprimerat) i slot B, seq 4,
  3,3 s". Claudes webbläsarpanel blockerar anrop från :8085 till :8000
  (ERR_BLOCKED_BY_CLIENT, panelens egen policy) — testa byggläget via muxen
  (:10000, samma origin) eller i vanlig Chrome.
- Scenen `hyst-test` på Pi5 binder `opta-testbank.reg1_*` som inte finns i
  OptaClientens namn (blocket heter `rad1_regulator`) — faceplaten visar blankt
  för de raderna; `kurva1_*` fungerar. Rita om kortet mot rätt prefix när det
  blir skarpt.
- **Faceplate-kortet håller raden efter Skriv** tills readbacken kommit (max
  20 s) och ignorerar nya klick under tiden; simulerad `input`-event räcker
  inte alltid. Två "misslyckade" återställningar under E2-testet var det, inte
  auth-vägen — instrumenterad fetch visade identisk POST med `200 {ok:true}`.
  Testa skrivflöden med riktiga klick på paddan.
- **Teknikersidan öppen samtidigt** som runtimen gav hängande POST:ar (en
  ledig socket) — bekräftar principen "en klient i taget".
- Flashning fungerar från den här PC:n: `pio run -t upload --upload-port COM4`
  (Opta = USB VID 2341:0164), ca 11 s via DFU. persist (bank 2) överlever.
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

## Förslag E2: inloggning + MANUAL-väg i firmwaren (2026-10-16, för diskussion)

Underlag ur `optaModbusRemoteIO` (verifierat): mbed-kärnan har `mbedtls/sha256.h`
och hårdvaru-RNG (`DEVICE_TRNG=1`, `hal/trng_api.h`); persist tar värden upp
till 64 byte; teknikersidans ALLA anrop går genom en enda kö `optaFetch`
(serialiserad, ett omförsök efter 400 ms — lwip har en enda ledig socket för
inkommande); Modbus-coil 0–3 läses varje varv i `loop()` till
`pd.manualCoils` → `outputRequest(DO, SourceId::MANUAL)` (`network.cpp:63`),
och webbservern kör i samma `loop()`-tråd. `paramInt/paramStr` matchar
nyckeln var som helst i bodyn (`"en="` träffar `"token="`) — måste rättas.

### A. Inloggning

| # | Del | Förslag |
|---|---|---|
| A1 | Lagring | `auth.pw` i persist = salt (8 B, TRNG) + SHA-256(salt‖lösenord) = 40 B. Inget lösenord satt → `auth:false` i `/api/status` → fritt läge som i dag. Satt → `auth:true` |
| A2 | `POST /api/login` | `pass=…` (användarnamn ignoreras) → `{ok:true, token:<32 hex ur TRNG>, expires_in:600}`. Sessioner i RAM, max 4 (padda, tekniker-PC, reserv), äldsta kastas. Glidande livslängd: varje giltig skrivning förlänger; 10 min utan aktivitet → ogiltig. `POST /api/logout` |
| A3 | Gate | När `auth` är på kräver ALLA POST utom `/api/login` giltigt `token` (formulärfält, D11). GET fri (D4). Fel → `401 {ok:false, err:"inloggning krävs"}` → klienten öppnar inloggningsrutan och gör om anropet. Modbus TCP berörs inte |
| A4 | Spärr | Efter 3 misslyckade inloggningar 5 s spärr som dubblas upp till 60 s; brödsmula i svarta lådan |
| A5 | Sätta/byta | `POST /api/password` `new=…` (+token när auth är på; tomt `new` tar bort). Teknikersidan får ett kort **Säkerhet** (sätt/byt/ta bort, visar 🔒/🔓) |
| A6 | Glömt lösenord | Fysisk åtkomst = förtroende: fickpanelen (USB-C) får `losen <nytt>` / `losen av`; verktygsläget (USER 5 s, 192.168.200.1) kör UTAN auth; fabriksåterställning nollar |
| A7 | Teknikersidan | `optaFetch` lägger på `token` ur `sessionStorage` på alla formulär-POST:ar; 401 → inloggningsruta → anropet görs om. Visa-läge fritt (D4) |
| A8 | OptaClient | `fetchControlAuth` ← `status.auth`; `login` → `/api/login`; token går redan som formulärfält. Runtimens `LoginDialog` har användarnamnsfält — ignoreras |
| A9 | Klonfil | Lösenordshashen följer INTE med klonfilen (som nätverksinställningen) |
| A10 | Parsern | `paramInt/paramStr/paramFloat` matchar nyckel bara i bodyns början eller efter `&` |

Lösenordet går i klartext över HTTP på kabeln: D4 säger skydd mot oavsiktlig
ändring, inte kryptering. Challenge-response är inte värt det — `crypto.subtle`
saknas i webbläsare på `http://192.168…` (osäker kontext).

### B. MANUAL-väg för reläer

| # | Del | Förslag |
|---|---|---|
| B1 | `POST /api/relay` | `i=0..3&state=0|1` → `modbusTCPServer.coilWrite(i, state)` från webbhandlern (samma tråd). Nästa Modbus-synk läser coilen → `manualCoils` → arbiterns MANUAL-källa. **Exakt Katapults coil-semantik**: OR-källa under skydd, nollas av safe state, hand trumfar. Katapult ser samma coil över Modbus — en sanning, två vägar |
| B2 | `/api/status` | nytt fält `manual:[bool×4]` (coil-begäran) bredvid `relays` (faktiskt läge). Switch-symbolen binder `do{n}_status` (faktiskt) som i dag; `manual` blir taggen `do{n}_manual` i OptaClient |
| B3 | Auth | kräver token som alla POST när auth är på |
| B4 | Persistens | ingen, som coils (safe state nollar) |
| B5 | OptaClient | `switchDevice(deviceId, kanal, state)` → `POST /api/relay i=kanal−1&state=` (kanal 1-baserad som Katapult). D12 stängs |
| B6 | HMI-text | kolumnen "Drivs av" säger i dag "Modbus-coil från Katapult" för MANUAL — döps om till "manuell (HMI/Katapult)" |
| B7 | Utanför | AO via MANUAL-källan (analog prioritet) — inte nu; hand-läget finns för AO |

### C. Ordning och test

1. **Steg 1 (liten, utan auth):** A10 parserfix + B1/B2 `/api/relay` + `manual` i status. Bänktest: relä via OptaClient från paddan, Katapults coil-skrivning oförändrad, safe state nollar.
2. **Steg 2:** auth-modul (`auth.h/.cpp`: hash, token, sessioner, spärr, persist), gate i routern, `/api/login`, `/api/logout`, `/api/password`, `status.auth`, CLI `losen`, verktygsläge utan auth.
3. **Steg 3:** teknikersidans inloggning (`optaFetch` + ruta + Säkerhet-kortet).
4. **Steg 4:** OptaClient (A8, B5) + D4-flödet från paddan: visa fritt, ruta vid första Skriv, token hålls.

Uppskattning: ~250 rader C++ (auth) + ~60 (relay/parser) + ~80 rader JS i HMI:t
+ ~30 rader i OptaClient. SHA-256 ur mbedTLS kostar några KB flash.

### Frågor till Johan — besvarade 2026-10-16

- [x] F1 Sessionslängd: 10 min glidande, förlängs vid varje skrivning. **Ja.**
- [x] F2 Nivåer: **tre nivåer med standardlösenord** (Johan): 1 = Operatör `1111`, 2 = Tekniker `2222`, 3 = Service `9999`. Inloggningen är därmed ALLTID på — standardlösenorden gäller tills de byts (status varnar när ett standardlösenord är kvar). Se D13 för vad varje nivå får göra.
- [x] F3 Klonfil: "se ovan" — tolkat som att lösenorden INTE följer med klonfilen: en klonad enhet startar med standardlösenorden. Säg till om det ska vara tvärtom.
- [x] F4 Verktygsläget utan inloggning som återställningsväg. **OK.**
- [x] F5 Flashning: USB-C från laptopen + PlatformIO (`pio run -t upload`). Går det inte kan kabeln behöva bytas.

### D13 — nivåmodellen (spikad 2026-10-16)

| Nivå | Standard | Får göra (POST) |
|---|---|---|
| 1 Operatör | `1111` | börvärden/blockparametrar (`/api/fb` p), kurvpunkter (`/api/curves`), VAI/VDI (`/api/service type=vai|vdi`), reläer (`/api/relay`), tidkanaler (`/api/channel`), klocka (`/api/time`), eget lösenord |
| 2 Tekniker | `2222` | allt ovan + hand/sim (`/api/service` do/ao/ai/di), IO-typer/etiketter/offsets (`/api/io`), block skapa/radera/reset (`/api/fb/create|delete`, `reset=1`), makron, RTU (`/api/rtu/*`), `system/reset-max`, eget lösenord |
| 3 Service | `9999` | allt + nätverk (`/api/network`), fabriksåterställning, verktygsläge via API, alla lösenord (`/api/password level=1|2|3`) |

GET är alltid fri (D4). Modbus TCP berörs inte. Token bär nivån; `/api/login`
matchar lösenordet mot nivå 3 → 2 → 1 (första träff ger nivån). Fel nivå →
`403 {ok:false, err:"kräver nivå 2 (tekniker)"}`; saknad/utgången token →
`401`. Fickpanelen: `losen <nivå> <nytt>` / `losen <nivå> standard`.
Verktygsläget (USER 5 s) kör utan gate.

## Förslag E3: scenen i Optans flash + "Skicka till Optan" (2026-10-16, för diskussion)

Underlag (verifierat): bank 2 = 0x08100000–0x081FFFFF, 8 sektorer à 128 KB;
M7-appen får inte passera `CM4_BINARY_START` (bank 1 slutar 0x08100000), en
ev. M4-binär laddas från bankens BÖRJAN, persist tar de sista 256 KB
(0x081C0000). FlashIAP finns (`program`/`erase`, sida 32 B, read-while-write
över bankgräns). Heap: 431 KB total, 42 KB max använt. Storlekar: scener
0,3–15 KB, bakgrunder ≤ 12 KB, gzip ≈ 4×. Plattformens API hämtar redan
scener ur scada-containern (`KATAPULT_SCADA_URL`: `/scenes.json`,
`/scenes/<id>.scene.json`, Lill-Berts ritverktyg) — `/backgrounds/<fil>`
finns på samma server. Byggläget (`runtime/src/editor/host.ts`) har
bootstrap-JWT mot plattformen.

### A. Flashen (firmware: `hmi_store.h/.cpp`)

| # | Förslag |
|---|---|
| A1 | **Ett paket, 256 KB** = bank 2 sektor 4–5 (0x08180000–0x081BFFFF), vägg i vägg med persist. 512 KB kvar i bankens början för en framtida M4-binär. Typiskt paket 10–20 KB gzippat → 10× marginal |
| A2 | **Header sist = commit.** Första 256 B: magic `KHMI`, version, längd, CRC32, flaggor (gzip), namn (32 tecken), enhetsnamn (32), tidsstämpel. Data från +256 B. Headern skrivs efter CRC-kontroll av det som ligger i flashen, så en avbruten uppladdning lämnar "inget paket", aldrig ett halvt |
| A3 | Ett slot i v1. A/B-slot (2 × 256 KB) om vi vill att ett misslyckat byte lämnar det gamla kortet kvar — plats finns |
| A4 | Erase av 2 sektorer blockerar nätverkstråden ~2–4 s (Modbus-servern tyst så länge, scan-tråden opåverkad). Katapults poller kan logga en miss |

### B. Optans API

| # | Väg | Nivå | Innehåll |
|---|---|---|---|
| B1 | `GET /api/hmi/scene` | fri | paketet som det ligger: `Content-Type: application/json`, `Content-Encoding: gzip` när flaggan är satt, `Content-Length`; 404 utan paket |
| B2 | `GET /api/hmi/info` | fri | `{present, bytes, crc, name, device, ts, gzip}`; samma fält som `hmi` i `/api/status` |
| B3 | `POST /api/hmi/begin?token=&size=&crc=&gzip=1&name=&device=` | 2 | validerar storlek ≤ 256 KB−256, raderar sektorerna, går i läget "laddar" |
| B4 | `POST /api/hmi/chunk?off=N&token=` | 2 | **rå binär body** ≤ 4096 B (inte String — nollbytes), programmeras i 32-B-sidor med svansbuffert; off måste vara nästa väntade |
| B5 | `POST /api/hmi/end?token=` | 2 | CRC32 över flashen = begärd → header skrivs → `{ok, bytes, crc}`; annars `{ok:false, err:"crc"}` och inget paket |
| B6 | `POST /api/hmi/clear?token=` | 2 | raderar headern (kortet borta) |
| B7 | Routern delar `path` vid `?`; token läses ur body ELLER query (chunk-anrop har binär body). Gaten oförändrad i övrigt |

Uppladdning av 20 KB = 5 chunkar ≈ 1 s + erase. 256 KB = 64 chunkar ≈ 10 s.

### C. Paketformatet (gemensamt för Opta-lagring och APK)

```json
{ "schema": "katapult/opta-hmi/package@1",
  "name": "Pannrum A", "device": "opta-testbank", "created": "2026-10-16T18:00:00Z",
  "project": { "title": "Pannrum A", "scenes": [ { "id": "panna", "title": "Panna", "file": "scenes/panna.scene.json", "home": true } ] },
  "files": { "scenes/panna.scene.json": "<text>", "backgrounds/panna.svg": "<text>" } }
```

Scenfilerna lagras oförändrade (bindningar, bakgrundssökväg relativ som i
dag). Avsändaren gzippar; Optan lagrar bytes och sätter flaggan.

### D. Runtimen (katapult-scada)

| # | Förslag |
|---|---|
| D1 | Alla filhämtningar (`project.yaml`, `scenes/*`, bakgrunder) går via en **källa**: i Opta-läge hämtas `GET /api/hmi/scene` EN gång och filerna serveras ur paketet; `connection.optaDevice` = paketets `device`. Utan paket (404) → lokala filer som i dag (dev) eller meddelandet "Inget driftkort i Optan — skicka från byggläget" (APK) |
| D2 | Omladdning (⟳ eller var 5:e min) jämför `info.crc` — nytt paket → ladda om scenen utan omstart |
| D3 | `?opta=<värd>` räcker som URL: enhetsnamnet kommer ur paketet |

### E. Plattformen (webbstyr-energy-platform) + pollern

| # | Förslag |
|---|---|
| E1 | `POST /tenants/{t}/modbus/poll-devices/{id}/opta-hmi` `{scene_ids:[…], name?, password}`: hämtar `/scenes.json`, scenfilerna och deras bakgrunder ur scada-containern (samma väg som Lill-Berts ritverktyg), bygger paketet, gzip + base64 → Redis `opta_hmi_push` → väntar (≤ 60 s) → `{ok, bytes, crc}`. Loggas i `agent_commands` som `opta_hmi_push` |
| E2 | Pollern (host-net): `opta_hmi_push` loggar in på Optan med teknikerlösenordet (nivå 2), kör begin/chunk/end, loggar ut; `opta_hmi_info` = GET info. Samma mönster som `opta_map` |
| E3 | **Lösenordet lagras inte** i Katapult i v1 — det skrivs i dialogen vid varje sändning (teknikerhandling). Kolumn på enhetsraden kan komma senare |
| E4 | `GET …/opta-hmi` → info (vad ligger i Optan nu) |

### F. Byggläget

| # | Förslag |
|---|---|
| F1 | Knapp **"Skicka till Optan…"** i byggläget (editor/media/main.js + host.ts-meddelande, plattforms-JWT finns): dialog med Opta-enhet (plattformens poll-enheter med model ≈ opta, förvald = den enhet scenens bindningar pekar på), scenlista med kryss (scener som binder mot enheten förkryssade, home/ordning följer project.yaml), paketnamn, teknikerlösenord → anrop → kvitto "12 384 B, crc 1a2b3c4d" |
| F2 | Samma knapp som "Skicka driftkort till Optan…" i Modbus-vyn (Enheter → Modbus) bredvid "Hämta från Optan" — billig att lägga till, hjälper utan byggläge |

### Ordning

1. Firmware A+B (hmi_store, endpoints, routerns query-split) → test med curl/python: begin/chunk/end/CRC/404.
2. Runtime D → dev-servern laddar paketet från bänk-Optan.
3. Plattform E (API + poller) → `curl` mot API:t skickar bänkscenen.
4. Byggläge F1 (+ F2).

### Frågor till Johan — besvarade 2026-10-16 (D14)

- [x] G1 **A/B-slot**: två slot à 256 KB (bank 2 sektor 2–3 = 0x08140000 och 4–5 = 0x08180000), 256 KB kvar i bankens början för M4. Headern bär ett sekvensnummer; aktivt slot = giltig header med högst nummer; uppladdning går alltid till det inaktiva slottet. Ett misslyckat byte lämnar det gamla kortet orört.
- [x] G2 **Lösenordet lagras ordentligt från början**: kolumn på Opta-enhetens rad i `modbus_poll_devices`, sätts i Enheter → Modbus (maskerat, visas aldrig igen), krypterat i vila med plattformens hemlighet, används bara av pollern. Dialogen i byggläget frågar inte efter det.
- [x] G3 Knappen **bara i byggläget**.
- [x] G4 **Båda**: runtimen kollar `/api/hmi/info` var 5:e minut och laddar om vid ny CRC, och manuell omladdning fungerar som vanligt.

## Förslag E4: APK:n (2026-10-16, för diskussion)

Inventering av PC:n 2026-10-16: **ingen Android-verktygskedja** — inget SDK,
inget JDK, ingen Android Studio, inget adb, inget Gradle. `winget` finns.
Kandidatplattan (Lenovo Tab One, Android 14) har WebView ≥ 2021 → runtimens
ES2022-bundle går. Runtimens dist = `index.html` + `assets/*.js|css` med
absoluta `/assets/…`-sökvägar.

### A. Byggväg — två alternativ

| | A1 Lokalt på PC:n | A2 GitHub Actions |
|---|---|---|
| Kräver | JDK 17 (Temurin) + Android cmdline-tools, platform-tools, build-tools, platform 34 via `sdkmanager` — ca 1 GB nedladdning, 10–15 min, kan göras headless med winget/zip | repot `KatapultOPTA_HMI` pushat till GitHub (org + synlighet ej valt än) |
| Bygget | `gradlew assembleDebug` på PC:n, ~1 min efter första gången | ubuntu-runnern har SDK förinstallerat; workflow bygger vid push och lägger APK:n som artefakt/release, ~3 min |
| Plattan hämtar | via fil/adb från PC:n | **direkt från GitHub Releases** — "ladda ner appen från min github" (Johans ord) |
| Underhåll | verktygskedja att hålla vid liv på PC:n | inget lokalt; bygget är reproducerbart |

Förslag: **A2 som huvudväg** (kräver push), A1 som tillval om Johan vill bygga
offline. Båda kan finnas: samma Gradle-projekt.

### B. Appen (Kotlin, inga bibliotek)

| # | Del | Förslag |
|---|---|---|
| B1 | Skal | en `Activity` med `WebView`, **ingen AndroidX** (principen "inga bibliotek"). Runtimen ligger som assets |
| B2 | Servering | en **lokal HTTP-server** i appen (ServerSocket på 127.0.0.1, slumpad port, ~80 rader) serverar assets. Origin blir `http://127.0.0.1:port` → absoluta `/assets`-sökvägar fungerar, inget blandat innehåll (Optan är http), `localStorage` (token-cachen) består. Inga avvecklade `file://`-flaggor, ingen `WebViewAssetLoader` |
| B3 | Start-URL | `http://127.0.0.1:port/?opta=<ip>` — resten (paket, enhetsnamn, scener) kommer ur Optan (E3). Utan paket visar runtimen "Inget driftkort i Optan" |
| B4 | UDP-upptäckt | broadcast `KATAPULT_DISCOVER_v1` till port 5000 på varje nätverk (Ethernet först, sedan Wi-Fi), multicast-lås, svar = JSON med `id`, `name`, `ip`. En träff → använd; flera → nativ lista; ingen → IP-dialog förifylld med senaste eller 192.168.200.1 (fallback). Sparas i SharedPreferences |
| B5 | Nätbindning | upptäckten körs per nätverk (`Network.bindSocket`), och appen binder processen (`bindProcessToNetwork`) till nätverket där Optan svarade, så WebView-trafiken går dit även när Android lagt standardrutten på LTE/Wi-Fi. Nätverkslyssnare: tappas nätet → ny upptäckt |
| B6 | Kiosk | `startLockTask()` (app-fästning; med device owner via `adb shell dpm set-device-owner` blir det äkta kiosk utan utväg), skärmen alltid på, immersive helskärm, `BOOT_COMPLETED` → autostart efter strömavbrott, HOME-kategori så appen kan väljas som startskärm |
| B7 | Inställningar | nativ dialog (Opta-IP, Sök igen, orientering, kiosk av/på, version): öppnas med **volym ned hållen 3 s** (fungerar även fäst) |
| B8 | Robusthet | sidfel i WebView → omladdning efter 5 s; runtimen sköter själv Optans RST och omladdning vid nytt paket (E3). Inga uppdateringar i drift — ny APK = ny version |
| B9 | Behörigheter | INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, RECEIVE_BOOT_COMPLETED. minSdk 26, target 34 |
| B10 | Signering | debug-nyckel för testerna; release-keystore skapas av Johan och läggs som hemlighet i GitHub (A2) eller lokalt (A1) |

### C. Repo-layout (KatapultOPTA_HMI)

```
apk/                 Gradle-projekt (settings, app/, wrapper)
apk/app/src/main/assets/www/   runtimens dist (synkas av tools/sync-runtime.sh ur ~/katapult-scada/runtime/dist)
.github/workflows/apk.yml      bygger assembleDebug (och release vid tagg) → artefakt/release
tools/sync-runtime.sh          vite build i scada-repot + kopiera index.html + assets/
```

Runtimens commit skrivs in i appens version (visas i inställningsdialogen).

### D. Test

1. Bygg APK (A1 eller A2) → installera på Lenovo-paddan ("okända källor") via nedladdning från GitHub/PC.
2. Paddan på samma nät som bänk-Optan: upptäckt hittar 192.168.1.117 → hyst-test ur Optans flash → D4-flödet (Skriv → 1111).
3. Direktkabel (E0): padda + USB-C-Ethernet-adapter, fast IP, Optan på 192.168.200.1 — upptäckt + bindning.
4. Fäst appen, starta om paddan → autostart.

### Frågor till Johan

- [ ] H1 Byggväg: GitHub Actions (kräver push — vart: Luddetrutt eller jlstyrab-org, privat/publikt?) eller lokal verktygskedja (jag installerar JDK + SDK, ca 1 GB)?
- [ ] H2 Inställningsdialogen via volym ned 3 s — OK? (Back-knappen är blockerad i fäst läge.)
- [ ] H3 Orientering: följ plattan, eller låst liggande som standard?
- [ ] H4 Installation på paddan under test: nedladdning från en länk (enklast) eller adb över Wi-Fi (kräver utvecklarläge + parning)?

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
- [x] Token i `Authorization`-huvud eller som formulärfält? → formulärfält (D11, 2026-10-16).
- [ ] Enheter på analoga ingångar: `/api/io` ger `itype` men ingen enhet; OptaClient sätter bara °C på PT1000. Mappa itype → enhet när typtabellen är känd.
- [ ] Larmöverlägg (symbolblink) direkt mot Optan: blocklarm och systemflaggor finns som taggar, men AlarmRegister matas inte. Syntetiska alarm-frames ur pollningen, eller låta symbolerna binda `_alarm`-taggen.
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
| E1 | **KLAR 2026-10-16** (katapult-scada: `runtime/src/client/OptaClient.ts` + `ScadaClient.ts`, val i `main.ts`, `connection.opta` i project.yaml). Bänkverifierat i webbläsare mot 192.168.1.117: kortet live, börvärde 21,5 skrivet via faceplatens Skriv och läst tillbaka ur `/api/fb`, kurvpunkt skriven via kurvkortet och läst ur `/api/curves`, tidkanalsymbolen visar kanal 0:s schema. Testscen: `scenes-test/` i detta repo; dev-server = launch-posten `scada-opta` (SCENES_DIR → scenes-test, `--host 0.0.0.0`, port 5279). Kvar i E1: statiskt paket (vite build + mapp) i stället för dev-server | katapult-scada |
| E2 | **KLAR 2026-10-16** (optaModbusRemoteIO: `auth.h/.cpp`, gate + `/api/login|logout|password|relay` i `web_server.cpp`, `modbusSetManualCoil`, CLI `losen`, parserfix; teknikersidan: token i `optaFetch`, inloggningsruta, Säkerhet-kort, 🔒/🔓-pill; docs + CLAUDE.md). Flashad på bänk-Optan (498 500 B). 27 API-kontroller gröna (`scratchpad/e2_test.py`): nivåer, 401/403/429, relä via MANUAL (R3 slog fysiskt), parserfix, lösenordsbyte, spärr. Tokentest: 7 skrivningar/2 min på en token, 34–131 ms. Runtime: `switchDevice` → `/api/relay`, Opta-fel visas i klartext, cachad token släpps vid "inloggning krävs"; D4-flödet verifierat i webbläsare (ruta vid Skriv → 1111 → skrivning → token 10 min). D12 stängd | optaModbusRemoteIO + katapult-scada |
| E3 | **BYGGD 2026-10-16**, fyra lager: (1) firmware `hmi_store.h/.cpp` (A/B-slot à 256 KB, header sist, CRC32) + `GET /api/hmi/scene|info`, `POST begin/chunk/end/clear` (nivå 2, binär chunk-body, path/query-split) — bänktest `scratchpad/e3_upload.py`: upp/läs tillbaka byte för byte, A/B-byte seq+1, CRC-fel lämnar gamla, 401, clear → 404 (optaModbusRemoteIO 41cf27a); (2) runtime: paketkälla (`resolveProject`/`readFile`), Optans paket vinner över lokala filer, `?pkg=local`, omladdning vid ny CRC var 5:e min — verifierat: dev-servern renderar kortet ur Optans flash (katapult-scada 94741e7); (3) plattform: `api/opta_hmi.py` bygger paketet ur scada-containern, `POST/GET …/poll-devices/{id}/opta-hmi`, pollern `opta_hmi_push` (login nivå 2, begin/chunk/end med omförsök + offset-skydd), migration 047 `http_password` krypterat (`api/secretbox.py`), 🔑 i Modbus-vyn (2acfe09); (4) byggläget: "⇪ Skicka till Optan…" med dialog (host.ts + editor/media/main.js). **E2E KLAR 2026-10-16 via Pi5:** lösenord satt på opta-testbank via API:t (PATCH http_password), `POST …/opta-hmi {scene_ids:["hyst-test"]}` → paket 2305 B rå / 797 B gz, upp på 2,9 s, slot A seq 3, audit-rad `opta_hmi_push` status sent; runtimen med bara `?opta=192.168.1.117` laddade "Hysteres-test (Opta)" ur Optan med kurvkortet live. Deploy: plattform 2acfe09 + migration 047 på Pi5 (api, poller, frontend), scada via bundle scada-20261016a | alla tre + KatapultOPTA_HMI |
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
