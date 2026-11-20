# Regole obbligatorie per gli agenti AI

Queste istruzioni hanno **priorità assoluta** su qualsiasi comportamento predefinito dell'agente AI durante il lavoro su questo progetto.

## 1. Proporre sempre la soluzione PRIMA di applicarla

- **Prima** di creare, modificare o eliminare qualsiasi file, o di eseguire comandi che alterano lo stato del progetto, devi:
  1. Analizzare il codice in **sola lettura** per individuare con precisione la causa del problema o i requisiti della richiesta.
  2. Esporre chiaramente all'utente la **diagnosi** e la **soluzione proposta** (indicando file coinvolti, modifiche previste ed eventuali alternative o rischi).
  3. **Fermarti e attendere l'approvazione esplicita dell'utente** prima di scrivere o modificare codice.
- **Non applicare mai modifiche direttamente** nella prima risposta, anche se la richiesta sembra un comando diretto, a meno che l'utente non specifichi esplicitamente nel prompt di procedere subito senza conferma.

## 2. Commit e push obbligatori su GitHub

- Ogni volta che completi una modifica approvata dall'utente:
  1. Verifica che il progetto compili e che i test passino (es. `:app:testDebugUnitTest :app:assembleDebug`).
  2. Controlla `git status` e `git diff` per assicurarti di includere solo i file pertinenti alla modifica ed evitare file sensibili o segreti (es. `local.properties`, `.env`, chiavi JSON/Firebase).
  3. Esegui **sempre** `git add` (sui file pertinenti), `git commit` con un messaggio chiaro e descrittivo, e `git push` sul repository GitHub (`origin`).
  4. Riporta all'utente l'hash del commit e l'esito del push.
- **Non eseguire mai** `git push --force` o riscritture della cronologia senza consenso esplicito dell'utente. Se il push fallisce o ci sono modifiche non correlate nella working tree, avvisa l'utente e chiedi come procedere.

## Contesto rapido del progetto

- **Modulo Android:** `android/` (root del progetto Android Studio, unico modulo `:app`).
- **Root repository Git:** directory padre (`..`, contenente `android/` e `collector/`).
- **Verifica standard:** `:app:testDebugUnitTest :app:assembleDebug`.
