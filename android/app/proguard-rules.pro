# Meeple Radar — ProGuard / R8 rules
# Le librerie kotlinx.serialization, OkHttp e Coil includono già le proprie consumer rules.
# Qui manteniamo solo le regole esplicite per i modelli e le risorse di sicurezza locali.

# Mantieni gli attributi utili per stacktrace leggibili in crash report
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
