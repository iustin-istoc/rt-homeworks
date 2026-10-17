# Tema 01 – soluție (istoc-iustin)

`STUDENT = "istoc-iustin"` în ambele `Config.java` → topic-uri `ssatr/istoc-iustin/devices/<id>/…`.

## Rulare

```bash
cd start/business-service && mvn -q compile exec:java
cd start/device-simulator  && mvn -q compile exec:java -Dexec.args="device-1"   # alt terminal: device-2
```

Comenzi în consola serviciului: `show`, `device-1 SET_INTERVAL 1000`, `device-1 SET_THRESHOLD 25`,
`device-1 RESET`, `device-1 STATUS`, `help`, `exit`.

## Decizii de proiectare

**TODO 1 – periodic.** Grilă cu așteptare absolută pe `System.nanoTime()` (monoton): `release += T`,
apoi `LockSupport.parkNanos` până la `release` (în buclă, pentru treziri false). Eroarea nu se acumulează:
lateness măsurat ≈ 0.1 ms, constant. `periodMs` se citește la fiecare job, deci `SET_INTERVAL` are efect
de la eliberarea următoare. La depășire (job terminat după eliberarea următoare) joburile pierdute se sar
și taskul rămâne aliniat la grilă, în loc să le execute în rafală.

**TODO 2 – sporadic, alegerea pentru prag.** Coada transportă un `AlarmEvent(temperatură, prag, sosire)`
în loc de `Double`. Motive:
1. *consistență* – pragul se poate schimba (`SET_THRESHOLD`) între momentul depășirii și tratarea alarmei;
   publicăm exact pragul care a fost depășit, nu cel curent;
2. *timp corect* – intervalul minim între sosiri și R se măsoară de la **sosirea** evenimentului, nu de la
   `take()`; dacă taskul sporadic întârzie, măsurarea de la `take()` ar accepta greșit evenimente prea dese.

Prima alarmă este acceptată mereu. Se verifică și R ≤ D (D = 500 ms, ales de noi; măsurat R ≈ 3 ms).

**TODO 3 – aperiodic.** FIFO din `BlockingQueue`; orice eroare (comandă necunoscută, parametru lipsă sau
nenumeric, interval în afara [100, 60000] ms) devine `ERROR <motiv>` pe `status`, iar taskul continuă.
Limita inferioară protejează brokerul partajat.

**TODO 4 – procesare.** Mesajul se salvează brut în CSV *înainte* de interpretare (rămâne urma și pentru
mesaje invalide); excepțiile de parsare sunt prinse per mesaj, thread-ul nu se oprește. Alarmele și
confirmările se afișează imediat; telemetria nu (ar acoperi consola).

**TODO 5 – dashboard.** `TreeMap<deviceId, DeviceState>` cu metode `synchronized` pe același obiect, ca
`show` să vadă o stare consistentă. Vechimea se calculează din momentul **recepției** (ceasul serviciului),
nu din timestamp-ul device-ului – ceasurile pot fi desincronizate.

**TODO 6 – consolă.** `split("\\s+", 2)` → deviceId + restul comenzii. Se resping ID-urile cu `/ + #`
(ar schimba topic-ul); pentru un device necunoscut doar se avertizează. Validarea comenzii o face device-ul.

## Testare

Testat local, cap-coadă (broker local, 2 device-uri): grila periodică fără derivă, schimbare `SET_INTERVAL`
2000 → 1000, alarme acceptate la ≥ 5000 ms și respinse sub, `ERROR` pentru `FOO`, `abc`, `10`,
CSV-urile `data/*.csv` scrise corect, `show` cu tabelul complet.

## Diagrame (`diagrame/`)

| Fișier | Sursă |
|---|---|
| `diagrama-de-componente.png` | `diagrama-de-componente.puml` (PlantUML) |
| `diagrama-de-secventa.png` | `diagrama-de-secventa.puml` (PlantUML, ambele scenarii) |
| `diagrama-de-timp.png` | `diagrama-de-timp.drawio` (draw.io), generate de `genereaza_diagrama_de_timp.py` |
