package io.github.yggdrasil_sh.saga.blocks

/**
 * "/"-Menue und "+"-Menue des Editors (Saga 2.4): dieselben Blocktypen wie Web und iOS (Editor.swift slashItems).
 * id = Typ fuer die Auswahl (Sondertypen: image, file, page, db_new, db_link, link, immich_image).
 */
object SlashLogic {
    /** Was braucht ein Eintrag (sonst ausgeblendet)? */
    enum class Need { NONE, UPLOAD, OWNER, OWN_SERVER, NEXTCLOUD, IMMICH }

    data class Item(val id: String, val level: Int = 0, val keywords: String, val need: Need = Need.NONE) {
        /** stabiler Schluessel (Ueberschriften je Ebene) */
        val key: String get() = if (level > 0) "$id$level" else id
    }

    /** Reihenfolge wie im iOS-"/"-Menue; Suchwoerter in de/en/es/ru/th */
    val ITEMS: List<Item> = listOf(
        Item("paragraph", 0, "text absatz paragraph texto parrafo текст абзац ข้อความ"),
        Item("header", 1, "h1 ueberschrift überschrift heading titel titulo encabezado заголовок หัวข้อ"),
        Item("header", 2, "h2 ueberschrift überschrift heading titulo encabezado заголовок หัวข้อ"),
        Item("header", 3, "h3 ueberschrift überschrift heading titulo encabezado заголовок หัวข้อ"),
        Item("header", 4, "h4 ueberschrift überschrift heading titulo encabezado заголовок หัวข้อ"),
        Item("checklist", 0, "todo checkliste checklist aufgabe check casilla tareas чеклист список задач รายการตรวจสอบ"),
        Item("unordered", 0, "liste aufzaehlung aufzählung bullet list punkt lista vinetas viñetas список маркированный รายการ"),
        Item("ordered", 0, "nummeriert numbered liste zahl ordered numerada нумерованный список ลำดับเลข"),
        Item("quote", 0, "zitat quote cita цитата คำพูด"),
        Item("callout", 0, "hinweis callout info aviso nota примечание выноска หมายเหตุ"),
        Item("toggle", 0, "toggle aufklappen ausklappbar desplegable раскрывающийся สลับ พับ"),
        Item("code", 0, "code codigo código код โค้ด"),
        Item("delimiter", 0, "trenner linie divider separator separador linea línea разделитель เส้นคั่น"),
        Item("image", 0, "bild foto image screenshot imagen kamera camera изображение фото รูปภาพ", Need.UPLOAD),
        Item("immich_image", 0, "immich bild foto album imagen фото รูปภาพ", Need.IMMICH),
        Item("table", 0, "tabelle table raster zellen tabla celdas таблица ตาราง"),
        Item("columns", 2, "spalten columns nebeneinander columnas колонки столбцы คอลัมน์ 2"),
        Item("columns", 3, "spalten columns nebeneinander columnas колонки столбцы คอลัมน์ 3"),
        Item("file", 0, "datei anhang file attachment pdf upload archivo adjunto файл вложение ไฟล์", Need.UPLOAD),
        Item("embed", 0, "einbetten embed video youtube link lesezeichen bookmark karte insertar incrustar marcador видео закладка встроить วิดีโอ ฝัง บุ๊กมาร์ก"),
        Item("link", 0, "verweis link seite page reference enlace referencia ссылка страница ลิงก์"),
        Item("page", 0, "seite unterseite page subpage pagina página subpagina страница подстраница หน้า หน้าย่อย", Need.OWN_SERVER),
        Item("db_new", 0, "datenbank database tabelle board base de datos база данных ฐานข้อมูล", Need.OWN_SERVER),
        Item("db_link", 0, "verknuepfte verknüpfte datenbank linked database view vinculada связанная база ฐานข้อมูลที่ลิงก์", Need.OWN_SERVER),
        Item("tasks", 0, "aufgaben tasks todo tareas задачи งาน", Need.OWNER),
        Item("calendar", 0, "kalender termine calendar calendario eventos календарь события ปฏิทิน", Need.OWNER),
        Item("nextcloud", 0, "nextcloud cloud datei ordner file folder archivo carpeta файл папка ไฟล์ โฟลเดอร์", Need.NEXTCLOUD),
    )

    /** Text eines Absatzes (Klartext) -> Suchbegriff, wenn er ein "/"-Befehl ist ("/tab" -> "tab"), sonst null */
    fun query(plain: String): String? {
        if (!plain.startsWith("/") || plain.length > 21) return null
        if (plain.any { it.isWhitespace() }) return null
        return plain.substring(1).lowercase()
    }

    /** Eintraege fuer den Suchbegriff; label = angezeigter (uebersetzter) Name. available = erfuellte Voraussetzungen */
    fun filter(query: String, available: Set<Need>, label: (Item) -> String): List<Item> {
        val q = fold(query.trim())
        return ITEMS.filter { it.need == Need.NONE || it.need in available }
            .filter { q.isEmpty() || fold(label(it)).contains(q) || fold(it.keywords).contains(q) }
    }

    /** Gross/klein und Umlaute egal ("uber" findet "Überschrift") */
    fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Typen, die direkt (ohne Dialog) aus dem Absatz entstehen - Umwandeln wie BlockDoc.newBlock */
    val TEXT_TYPES = setOf("paragraph", "header", "checklist", "unordered", "ordered", "quote", "callout", "code", "toggle", "delimiter")
}
