# KatapultOPTA_HMI

Operatörs-HMI för Arduino Opta på en läsplatta: ett driftkort ritat i
Katapults byggläge, kört av en egen Android-APK som pollar Optan direkt.
Målet är en "dum" HMI utan beroenden eller underhåll på plattan.

Idéfas. Allt som är tänkt och beslutat står i **[docs/DESIGN.md](docs/DESIGN.md)**.

Syskonrepon: `optaModbusRemoteIO` (firmware), `katapult-scada` (runtime och
byggläge), `webbstyr-energy-platform` (Katapult), `katapult-stack` (nav och
larmcentral).
