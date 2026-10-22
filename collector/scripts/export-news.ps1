# Esporta l'estrazione Google News in un file Excel (xlsx).
# Uso (da collector/): powershell -ExecutionPolicy Bypass -File scripts\export-news.ps1
# Prima lancia scripts\export-news.ts con tsx (serve Node e le chiavi in supabase.env,
# poi monta il workbook con Excel COM e cancella il JSON intermedio.
$ErrorActionPreference = 'Stop'
$collector = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $collector

& node node_modules\tsx\dist\cli.mjs scripts\export-news.ts
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$jsonPath = Join-Path $collector 'export\google-news-latest.json'
if (-not (Test-Path -LiteralPath $jsonPath)) { Write-Error 'JSON intermedio non trovato'; exit 1 }
$data = Get-Content -LiteralPath $jsonPath -Raw -Encoding UTF8 | ConvertFrom-Json

$stamp = Get-Date -Format 'yyyy-MM-dd-HHmm'
$outPath = Join-Path (Split-Path -Parent $jsonPath) "google-news-$stamp.xlsx"

$sheets = @(
  @{ name = 'Notizie';     headers = @('titolo','testata','link','pubblicato','corpoNelFeed','percorso','esito'); rows = @($data.notizie) },
  @{ name = 'Estrazioni';  headers = @('fonte','percorso','titolo','url','prezzo','valuta','dataUscita','descrizione','gameId','imageUrl','externalId'); rows = @($data.estrazioni) },
  @{ name = 'Log';         headers = @('riga'); rows = @(@($data.log) | ForEach-Object { @{ riga = $_ } }) }
)

$excel = New-Object -ComObject Excel.Application
$excel.Visible = $false
$excel.DisplayAlerts = $false
try {
  $wb = $excel.Workbooks.Add()
  while ($wb.Worksheets.Count -lt 3) {
    $null = $wb.Worksheets.Add([System.Reflection.Missing]::Value, $wb.Worksheets.Item($wb.Worksheets.Count))
  }
  while ($wb.Worksheets.Count -gt 3) { $wb.Worksheets.Item($wb.Worksheets.Count).Delete() }

  for ($s = 0; $s -lt $sheets.Count; $s++) {
    $sh = $wb.Worksheets.Item($s + 1)
    $sh.Name = $sheets[$s].name
    $headers = $sheets[$s].headers
    for ($c = 0; $c -lt $headers.Count; $c++) { $sh.Cells.Item(1, $c + 1).Value2 = $headers[$c] }
    $r = 2
    foreach ($row in $sheets[$s].rows) {
      for ($c = 0; $c -lt $headers.Count; $c++) {
        $v = $row.($headers[$c])
        if ($null -ne $v) { $sh.Cells.Item($r, $c + 1).Value2 = [string]$v }
      }
      $r++
    }
    $sh.Range($sh.Cells.Item(1, 1), $sh.Cells.Item(1, $headers.Count)).Font.Bold = $true
    $sh.Range('A1').CurrentRegion.Columns.AutoFit() | Out-Null
  }

  $wb.SaveAs($outPath, 51)
  $wb.Close($false)
} finally {
  $excel.Quit()
  [void][Runtime.InteropServices.Marshal]::ReleaseComObject($excel)
}

Remove-Item -LiteralPath $jsonPath
$riepilogo = $data.riepilogo
Write-Output ("Riepilogo: {0} titoli, {1} candidati, {2} prodotti ({3} da articoli)" -f $riepilogo.titoliScaricati, $riepilogo.candidatiArticolo, $riepilogo.prodottiEstratti, $riepilogo.prodottiDaArticoli)
if ($riepilogo.errore) { Write-Output "ERRORE: $($riepilogo.errore)" }
Write-Output "XLSX: $outPath"
