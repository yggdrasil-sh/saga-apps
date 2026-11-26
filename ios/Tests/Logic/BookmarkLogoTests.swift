import Foundation

// Bugs-DB 2460: eigenes Logo im Blatt "Lesezeichen bearbeiten" (App/BookmarkLogoLogic.swift)
func bookmarkLogoTests() {
    typealias L = BookmarkLogoLogic
    let logo = "/files/0123456789abcdef01234567"
    check(L.isLogo(logo), "Logo erkannt")
    check(!L.isLogo("/files/0123456789ABCDEF01234567"), "nur Kleinbuchstaben wie LOGO_RE")
    check(!L.isLogo("/files/0123"), "zu kurz")
    check(!L.isLogo(logo + "0"), "zu lang")
    check(!L.isLogo("jellyfin") && !L.isLogo("") && !L.isLogo(nil), "Symbolname/leer ist kein Logo")
    let s = L.split(logo)
    check(s.logo == logo && s.symbol == "", "Logo nie als Pfad im Feld")
    check(L.split("jellyfin").logo == "" && L.split("jellyfin").symbol == "jellyfin" && L.split(nil).symbol == "", "Symbolname bleibt im Feld")
    check(L.iconToSave(logo: s.logo, symbol: s.symbol) == logo, "unveraendert: icon wie gekommen")
    check(L.iconToSave(logo: "", symbol: "") == "", "Logo entfernt: automatisch")
    check(L.iconToSave(logo: "", symbol: "openwrt") == "openwrt", "Logo entfernt, Symbol getippt")
    check(L.iconToSave(logo: "kaputt", symbol: "x") == "x", "kein gueltiges Logo: Symbolfeld")
}
