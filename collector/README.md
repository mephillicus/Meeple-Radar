# meeple-radar-collector

## Dati BGG: estrazione del CSV dei ranks

Il file `boardgames_ranks.csv` (export BGG, ~11 MB) non è versionato
(`boardgames_ranks*.csv` e `collector/data/` sono in `.gitignore`). Per
estrarlo dalla root del repo, dentro `collector/`:

```powershell
Expand-Archive -Path ..\boardgames_ranks_2026-10-19.zip -DestinationPath .
Move-Item -Path .\boardgames_ranks.csv -Destination .\data\boardgames_ranks.csv
```

(creare `data/` se non esiste; lo zip è l'export ufficiale di BGG.)

### Path candidati del loader

`loadRanksIndex(candidates)` legge il primo path esistente, in ordine. La
run da `collector/` prova:

1. `data/boardgames_ranks.csv` (consigliato, estratto qui sopra)
2. `boardgames_ranks.csv` (root di `collector/`)
3. `../boardgames_ranks.csv` (root del repo)

Nessun path esiste → il loader ritorna `null` e la passata di
arricchimento BGG è saltata: la run resta `ok`, il salto è solo
segnalato nel report.

## Passata di arricchimento BGG (best-effort)

Collega i prodotti non linkati a un gioco BGG, prima dal CSV locale
(nessuna rete, tutti i prodotti non linkati a ogni run) e poi online per
i restanti. È best-effort: non fallisce mai la run, degrada a silenzioso
se la rete o BGG non rispondono.

Budget lato online per run:

- max **10 prodotti** con richieste online a run;
- max **3 candidati** verificati per prodotto;
- ~2 s di spazio fra richieste; su 429 un retry dopo ~30 s, poi il lato
  online si interrompe per quella run;
- dopo **3 tentativi** online falliti per lo stesso prodotto, il lato
  online lo salta nelle run successive (stato in
  `data/bgg-online-skipped.json`, non versionato; azzerabile a mano).
