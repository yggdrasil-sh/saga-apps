Update-Daten der Apps - automatisch von .github/workflows/release.yml erzeugt. Nicht von Hand aendern.

- appcast.xml: Selbst-Update der Mac-App (Sparkle, EdDSA-signiert), neueste Version von main
- testing.json: Update-Hinweis aller Apps (tools/update-info.py), neueste Version von main (Testversion oder Release)
- stable.json: nur Releases - fuer Apps bis 2.5.0, die noch nach Kanal lesen
- appcast-rc.xml: nicht mehr benutzt (ein Kanal, Bugs-DB 2333)
