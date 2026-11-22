# DeviceBridge

Két eszköz (PC ↔ Android) összekapcsolása **Wi-Fi/LAN, Bluetooth PAN, internet, Bluetooth (soros port) és USB kábel** felett.
Mindkét eszközön fut a program (akár a háttérben), a kapcsolat végponttól végpontig titkosított.

## Funkciók

| Funkció | PC → telefon | Telefon → PC |
|---|---|---|
| Távoli asztal (saját, RDP-szerű protokoll) | telefon képernyőjének nézése + vezérlése egérrel/billentyűzettel | PC képernyőjének nézése + vezérlése érintéssel |
| Fájlátvitel (SHA-256 ellenőrzéssel) | ✔ (húzd-és-ejtsd is) | ✔ (fájlválasztó + „Megosztás” menü) |
| SSH + SFTP (beépített szerver, **csak LAN/PAN/USB**) | rendszer `ssh` terminál indítása | beépített terminál |
| Vágólap küldése | ✔ | ✔ |
| Link megnyitása a másik eszközön | ✔ | ✔ |
| Üzenet / értesítés | ✔ | ✔ |
| Csörgetés (eszköz keresése) | ✔ | ✔ |
| Zárolás | ✔ | ✔ |
| Médiavezérlés (lejátszás, előző/következő, hangerő) | ✔ | ✔ |
| Állapot (akku, modell, OS) | ✔ | ✔ |

**Android:** a lehúzható értesítési sáv **Gyorsbeállítások** csempéjével be-/kikapcsolható (szerkesztés → „DeviceBridge” csempe behúzása), előtérszolgáltatásként a háttérben fut, opcionálisan bekapcsoláskor indul.
**PC:** tálcaikonnal a háttérben fut, opcionálisan a Windows-zal indul.

## Felépítés

```
common/       közös Java könyvtár (mindkét platform ugyanezt futtatja)
  crypto/     ECDH P-256 + HKDF + AES-256-GCM csatorna, ECDSA eszközazonosító, párosított eszközök
  transport/  TCP és általános folyam alapú (Bluetooth / soros port) átvitel
  feature/    vezérlés, fájlátvitel, távoli asztal (csempés, csak változásokat küld)
  BridgeNode  kapcsolatkezelés, szerver, kézfogás, üzenetirányítás, életjel
  Discovery   UDP broadcast eszközkeresés a helyi hálózaton
desktop/      Swing (FlatLaf) PC alkalmazás: Robot alapú képernyő/bemenet, MINA SSHD, ADB, jSerialComm
android-app/  Android alkalmazás: előtérszolgáltatás, QS csempe, MediaProjection,
              akadálymentesítési szolgáltatás (érintés), RFCOMM szerver, MINA SSHD
```

Portok: `47801/tcp` (DeviceBridge), `47800/udp` (keresés), `47822/tcp` (SSH, csak helyi hálózat).

## Biztonság

* Első kapcsolódáskor mindkét eszköz **ugyanazt a 6 jegyű kódot** mutatja; mindkét oldalon jóvá kell hagyni (MITM elleni védelem, mint a Bluetooth „numeric comparison”). Ezután az eszköz a párosított listába kerül, és legközelebb automatikusan kapcsolódik.
* Minden forgalom AES-256-GCM-mel titkosított, irányonként külön kulccsal, visszajátszás ellen számlálós nonce-szal.
* Az SSH szerver csak helyi címekről (LAN, link-local, PAN, USB/loopback) fogad kapcsolatot; a jelszót a párosított eszköz a titkosított csatornán kapja meg. Alapból **ki van kapcsolva**.
* A távoli asztal a beállításokban letiltható. Androidon a képernyőmegosztást a rendszer minden alkalommal megerősítteti; a vezérléshez az akadálymentesítési szolgáltatást kézzel kell bekapcsolni.

## Fordítás

Szükséges: JDK 17+ (21 ajánlott), Android SDK (platform 35). A `local.properties` fájlban add meg az SDK helyét (`sdk.dir=...`).

```powershell
.\gradlew.bat :desktop:fatJar             # desktop\build\libs\DeviceBridge-desktop.jar
.\gradlew.bat :android-app:assembleDebug  # android-app\build\outputs\apk\debug\android-app-debug.apk
.\gradlew.bat :common:test                # tesztek (kézfogás, párosítás, fájlátvitel, távoli asztal)
```

Indítás PC-n: `java -jar DeviceBridge-desktop.jar` (vagy `javaw`, `--minimized` kapcsolóval tálcára).
Telepítés telefonra: `adb install -r android-app-debug.apk`.

> Windows első indításkor rákérdez a tűzfal-engedélyre – engedélyezd a **magánhálózatokon**, különben a telefon nem tud a PC-hez kapcsolódni (a PC → telefon irány ettől működik).

## Kapcsolódás

* **Wi-Fi / LAN / Bluetooth PAN / USB-tethering:** bármelyik oldalon „Keresés”, majd „Kapcsolódás”. (Bluetooth PAN: Windowsban a telefon → „Csatlakozás eszközhöz: Hozzáférési pont”, a telefonon Bluetooth-megosztás.)
* **Internet:** add meg a másik eszköz címét (pl. VPN-en, mint a Tailscale, vagy porttovábbítással a 47801-es portra).
* **USB kábel:** a telefonon Fejlesztői beállítások → USB-hibakeresés. A PC-n „USB kábel” → Kapcsolódás. Az ADB port-továbbítást a program intézi (SSH-t is).
* **Bluetooth (soros port):** párosítsd a telefont a Windows-zal, a telefonon fusson a DeviceBridge (Bluetooth engedéllyel). A PC-n válaszd a Bluetooth COM portot. Ha nincs ilyen: Bluetooth-beállítások → További beállítások → COM-portok → Hozzáadás → Kimenő → telefon. Lassabb, de internet nélkül is működik (a távoli asztal a „Gyors” minőséggel használható).

## Ismert korlátok

* A Bluetooth soros kapcsolat Windowson a virtuális COM porton keresztül működik (macOS/Linux alatt a megfelelő `/dev/rfcomm*` / `/dev/tty.*` port választható).
* Androidon a távoli húzás a felengedéskor hajtódik végre (egy gesztusként); a gépelés a fókuszban lévő szövegmezőbe ír.
* Telefon ↔ telefon kapcsolat nem cél, de a protokoll támogatná.
