package io.github.yggdrasil_sh.saga.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import io.github.yggdrasil_sh.saga.R

/**
 * Saga-Liniensymbole (A11Y-16): eine Quelle fuer alle Symbole der App, gleiche Symbolsprache wie das Web.
 *
 * Die Ressourcen `res/drawable/si_*.xml` erzeugt `tools/icons-android.py` aus `static/icons.js` (Saga-Server) plus
 * `tools/icons-android-extra.json` (Android-Ergaenzungen im selben Stil). 24er-Raster, Strich 1,75, runde Enden.
 * Die Striche sind weiss, die Farbe kommt beim Zeichnen: [SagaIcon] bzw. `Icon(painterResource(...))` faerbt mit
 * `LocalContentColor`. Mit `tint = Color.Unspecified` bliebe das Symbol weiss.
 *
 * Die Eigenschaften unten erzeugt `python3 tools/icons-android.py <icons.js> --print-kotlin`; nach dem Lauf des Skripts
 * hier einfuegen. `SagaIconsTest` prueft, dass Liste und Dateien zusammenpassen.
 */
@Suppress("unused")
object SagaIcons {
    @DrawableRes val alignCenter = R.drawable.si_align_center
    @DrawableRes val archive = R.drawable.si_archive
    @DrawableRes val archiveOut = R.drawable.si_archive_out
    @DrawableRes val arrowDown = R.drawable.si_arrow_down
    @DrawableRes val arrowLeft = R.drawable.si_arrow_left
    @DrawableRes val arrowRight = R.drawable.si_arrow_right
    @DrawableRes val arrowUp = R.drawable.si_arrow_up
    @DrawableRes val arrowUpCircle = R.drawable.si_arrow_up_circle
    @DrawableRes val at = R.drawable.si_at
    @DrawableRes val ban = R.drawable.si_ban
    @DrawableRes val bell = R.drawable.si_bell
    @DrawableRes val bellOff = R.drawable.si_bell_off
    @DrawableRes val board = R.drawable.si_board
    @DrawableRes val bold = R.drawable.si_bold
    @DrawableRes val bulb = R.drawable.si_bulb
    @DrawableRes val calendar = R.drawable.si_calendar
    @DrawableRes val camera = R.drawable.si_camera
    @DrawableRes val cameraSwitch = R.drawable.si_camera_switch
    @DrawableRes val caretDown = R.drawable.si_caret_down
    @DrawableRes val chart = R.drawable.si_chart
    @DrawableRes val chats = R.drawable.si_chats
    @DrawableRes val check = R.drawable.si_check
    @DrawableRes val checkbox = R.drawable.si_checkbox
    @DrawableRes val chevronDown = R.drawable.si_chevron_down
    @DrawableRes val chevronLeft = R.drawable.si_chevron_left
    @DrawableRes val chevronRight = R.drawable.si_chevron_right
    @DrawableRes val chevronUp = R.drawable.si_chevron_up
    @DrawableRes val circle = R.drawable.si_circle
    @DrawableRes val clipboard = R.drawable.si_clipboard
    @DrawableRes val clock = R.drawable.si_clock
    @DrawableRes val cloud = R.drawable.si_cloud
    @DrawableRes val cloudOff = R.drawable.si_cloud_off
    @DrawableRes val cloudUpload = R.drawable.si_cloud_upload
    @DrawableRes val code = R.drawable.si_code
    @DrawableRes val colLeft = R.drawable.si_col_left
    @DrawableRes val colRight = R.drawable.si_col_right
    @DrawableRes val colsEven = R.drawable.si_cols_even
    @DrawableRes val columns = R.drawable.si_columns
    @DrawableRes val comment = R.drawable.si_comment
    @DrawableRes val copy = R.drawable.si_copy
    @DrawableRes val database = R.drawable.si_database
    @DrawableRes val devices = R.drawable.si_devices
    @DrawableRes val dot = R.drawable.si_dot
    @DrawableRes val download = R.drawable.si_download
    @DrawableRes val droplet = R.drawable.si_droplet
    @DrawableRes val edit = R.drawable.si_edit
    @DrawableRes val euro = R.drawable.si_euro
    @DrawableRes val external = R.drawable.si_external
    @DrawableRes val eye = R.drawable.si_eye
    @DrawableRes val eyeOff = R.drawable.si_eye_off
    @DrawableRes val file = R.drawable.si_file
    @DrawableRes val folder = R.drawable.si_folder
    @DrawableRes val filePlus = R.drawable.si_file_plus
    @DrawableRes val fileZip = R.drawable.si_file_zip
    @DrawableRes val filter = R.drawable.si_filter
    @DrawableRes val flag = R.drawable.si_flag
    @DrawableRes val focus = R.drawable.si_focus
    @DrawableRes val forward = R.drawable.si_forward
    @DrawableRes val function = R.drawable.si_function
    @DrawableRes val gallery = R.drawable.si_gallery
    @DrawableRes val globe = R.drawable.si_globe
    @DrawableRes val graph = R.drawable.si_graph
    @DrawableRes val hash = R.drawable.si_hash
    @DrawableRes val help = R.drawable.si_help
    @DrawableRes val history = R.drawable.si_history
    @DrawableRes val home = R.drawable.si_home
    @DrawableRes val image = R.drawable.si_image
    @DrawableRes val info = R.drawable.si_info
    @DrawableRes val italic = R.drawable.si_italic
    @DrawableRes val keyboard = R.drawable.si_keyboard
    @DrawableRes val link = R.drawable.si_link
    @DrawableRes val list = R.drawable.si_list
    @DrawableRes val listOrdered = R.drawable.si_list_ordered
    @DrawableRes val lock = R.drawable.si_lock
    @DrawableRes val logout = R.drawable.si_logout
    @DrawableRes val mail = R.drawable.si_mail
    @DrawableRes val maximize = R.drawable.si_maximize
    @DrawableRes val menu = R.drawable.si_menu
    @DrawableRes val merge = R.drawable.si_merge
    @DrawableRes val message = R.drawable.si_message
    @DrawableRes val mic = R.drawable.si_mic
    @DrawableRes val micOff = R.drawable.si_mic_off
    @DrawableRes val minus = R.drawable.si_minus
    @DrawableRes val monitor = R.drawable.si_monitor
    @DrawableRes val moon = R.drawable.si_moon
    @DrawableRes val moreHoriz = R.drawable.si_more_horiz
    @DrawableRes val moreVert = R.drawable.si_more_vert
    @DrawableRes val move = R.drawable.si_move
    @DrawableRes val paperclip = R.drawable.si_paperclip
    @DrawableRes val pause = R.drawable.si_pause
    @DrawableRes val percent = R.drawable.si_percent
    @DrawableRes val phone = R.drawable.si_phone
    @DrawableRes val phoneIn = R.drawable.si_phone_in
    @DrawableRes val phoneMissed = R.drawable.si_phone_missed
    @DrawableRes val phoneOff = R.drawable.si_phone_off
    @DrawableRes val phoneOut = R.drawable.si_phone_out
    @DrawableRes val pie = R.drawable.si_pie
    @DrawableRes val pilcrow = R.drawable.si_pilcrow
    @DrawableRes val pin = R.drawable.si_pin
    @DrawableRes val play = R.drawable.si_play
    @DrawableRes val plus = R.drawable.si_plus
    @DrawableRes val progress = R.drawable.si_progress
    @DrawableRes val quote = R.drawable.si_quote
    @DrawableRes val range = R.drawable.si_range
    @DrawableRes val refresh = R.drawable.si_refresh
    @DrawableRes val relation = R.drawable.si_relation
    @DrawableRes val repeat = R.drawable.si_repeat
    @DrawableRes val reply = R.drawable.si_reply
    @DrawableRes val rollup = R.drawable.si_rollup
    @DrawableRes val rowAbove = R.drawable.si_row_above
    @DrawableRes val rowBelow = R.drawable.si_row_below
    @DrawableRes val rows = R.drawable.si_rows
    @DrawableRes val screenShare = R.drawable.si_screen_share
    @DrawableRes val search = R.drawable.si_search
    @DrawableRes val select = R.drawable.si_select
    @DrawableRes val send = R.drawable.si_send
    @DrawableRes val server = R.drawable.si_server
    @DrawableRes val settings = R.drawable.si_settings
    @DrawableRes val share = R.drawable.si_share
    @DrawableRes val shield = R.drawable.si_shield
    @DrawableRes val sigma = R.drawable.si_sigma
    @DrawableRes val smartphone = R.drawable.si_smartphone
    @DrawableRes val smile = R.drawable.si_smile
    @DrawableRes val sort = R.drawable.si_sort
    @DrawableRes val sparkles = R.drawable.si_sparkles
    @DrawableRes val star = R.drawable.si_star
    @DrawableRes val status = R.drawable.si_status
    @DrawableRes val stop = R.drawable.si_stop
    @DrawableRes val strikethrough = R.drawable.si_strikethrough
    @DrawableRes val sun = R.drawable.si_sun
    @DrawableRes val swap = R.drawable.si_swap
    @DrawableRes val table = R.drawable.si_table
    @DrawableRes val tableHead = R.drawable.si_table_head
    @DrawableRes val tableHeadcol = R.drawable.si_table_headcol
    @DrawableRes val tag = R.drawable.si_tag
    @DrawableRes val tasks = R.drawable.si_tasks
    @DrawableRes val template = R.drawable.si_template
    @DrawableRes val text = R.drawable.si_text
    @DrawableRes val timeline = R.drawable.si_timeline
    @DrawableRes val timer = R.drawable.si_timer
    @DrawableRes val today = R.drawable.si_today
    @DrawableRes val trash = R.drawable.si_trash
    @DrawableRes val type = R.drawable.si_type
    @DrawableRes val typeSmall = R.drawable.si_type_small
    @DrawableRes val underline = R.drawable.si_underline
    @DrawableRes val unlock = R.drawable.si_unlock
    @DrawableRes val upload = R.drawable.si_upload
    @DrawableRes val user = R.drawable.si_user
    @DrawableRes val users = R.drawable.si_users
    @DrawableRes val video = R.drawable.si_video
    @DrawableRes val videoOff = R.drawable.si_video_off
    @DrawableRes val volume = R.drawable.si_volume
    @DrawableRes val volumeOff = R.drawable.si_volume_off
    @DrawableRes val warning = R.drawable.si_warning
    @DrawableRes val width = R.drawable.si_width
    @DrawableRes val x = R.drawable.si_x
    @DrawableRes val zoomIn = R.drawable.si_zoom_in
    @DrawableRes val zoomOut = R.drawable.si_zoom_out

    val byName: Map<String, Int> = mapOf(
        "align-center" to alignCenter,
        "archive" to archive,
        "archive-out" to archiveOut,
        "arrow-down" to arrowDown,
        "arrow-left" to arrowLeft,
        "arrow-right" to arrowRight,
        "arrow-up" to arrowUp,
        "arrow-up-circle" to arrowUpCircle,
        "at" to at,
        "ban" to ban,
        "bell" to bell,
        "bell-off" to bellOff,
        "board" to board,
        "bold" to bold,
        "bulb" to bulb,
        "calendar" to calendar,
        "camera" to camera,
        "camera-switch" to cameraSwitch,
        "caret-down" to caretDown,
        "chart" to chart,
        "chats" to chats,
        "check" to check,
        "checkbox" to checkbox,
        "chevron-down" to chevronDown,
        "chevron-left" to chevronLeft,
        "chevron-right" to chevronRight,
        "chevron-up" to chevronUp,
        "circle" to circle,
        "clipboard" to clipboard,
        "clock" to clock,
        "cloud" to cloud,
        "cloud-off" to cloudOff,
        "cloud-upload" to cloudUpload,
        "code" to code,
        "col-left" to colLeft,
        "col-right" to colRight,
        "cols-even" to colsEven,
        "columns" to columns,
        "comment" to comment,
        "copy" to copy,
        "database" to database,
        "devices" to devices,
        "dot" to dot,
        "download" to download,
        "droplet" to droplet,
        "edit" to edit,
        "euro" to euro,
        "external" to external,
        "eye" to eye,
        "eye-off" to eyeOff,
        "file" to file,
        "folder" to folder,
        "file-plus" to filePlus,
        "file-zip" to fileZip,
        "filter" to filter,
        "flag" to flag,
        "focus" to focus,
        "forward" to forward,
        "function" to function,
        "gallery" to gallery,
        "globe" to globe,
        "graph" to graph,
        "hash" to hash,
        "help" to help,
        "history" to history,
        "home" to home,
        "image" to image,
        "info" to info,
        "italic" to italic,
        "keyboard" to keyboard,
        "link" to link,
        "list" to list,
        "list-ordered" to listOrdered,
        "lock" to lock,
        "logout" to logout,
        "mail" to mail,
        "maximize" to maximize,
        "menu" to menu,
        "merge" to merge,
        "message" to message,
        "mic" to mic,
        "mic-off" to micOff,
        "minus" to minus,
        "monitor" to monitor,
        "moon" to moon,
        "more-horiz" to moreHoriz,
        "more-vert" to moreVert,
        "move" to move,
        "paperclip" to paperclip,
        "pause" to pause,
        "percent" to percent,
        "phone" to phone,
        "phone-in" to phoneIn,
        "phone-missed" to phoneMissed,
        "phone-off" to phoneOff,
        "phone-out" to phoneOut,
        "pie" to pie,
        "pilcrow" to pilcrow,
        "pin" to pin,
        "play" to play,
        "plus" to plus,
        "progress" to progress,
        "quote" to quote,
        "range" to range,
        "refresh" to refresh,
        "relation" to relation,
        "repeat" to repeat,
        "reply" to reply,
        "rollup" to rollup,
        "row-above" to rowAbove,
        "row-below" to rowBelow,
        "rows" to rows,
        "screen-share" to screenShare,
        "search" to search,
        "select" to select,
        "send" to send,
        "server" to server,
        "settings" to settings,
        "share" to share,
        "shield" to shield,
        "sigma" to sigma,
        "smartphone" to smartphone,
        "smile" to smile,
        "sort" to sort,
        "sparkles" to sparkles,
        "star" to star,
        "status" to status,
        "stop" to stop,
        "strikethrough" to strikethrough,
        "sun" to sun,
        "swap" to swap,
        "table" to table,
        "table-head" to tableHead,
        "table-headcol" to tableHeadcol,
        "tag" to tag,
        "tasks" to tasks,
        "template" to template,
        "text" to text,
        "timeline" to timeline,
        "timer" to timer,
        "today" to today,
        "trash" to trash,
        "type" to type,
        "type-small" to typeSmall,
        "underline" to underline,
        "unlock" to unlock,
        "upload" to upload,
        "user" to user,
        "users" to users,
        "video" to video,
        "video-off" to videoOff,
        "volume" to volume,
        "volume-off" to volumeOff,
        "warning" to warning,
        "width" to width,
        "x" to x,
        "zoom-in" to zoomIn,
        "zoom-out" to zoomOut,
    )

    /** Ressource zu einem Web-Namen ("chevron-right"), `null` bei unbekanntem Namen (wie HK.icon). */
    @DrawableRes
    fun res(name: String): Int? = byName[name]

    /** Painter zu einer Ressource aus [SagaIcons]. */
    @Composable
    fun painter(@DrawableRes icon: Int): Painter = painterResource(icon)
}

/** Saga-Liniensymbol, Groesse wie Material-`Icon` (24 dp, ueber [modifier] aenderbar). */
@Composable
fun SagaIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(icon), contentDescription, modifier, tint)
}

/** Wie oben, mit Web-Namen ("calendar", "chevron-right"); unbekannte Namen zeichnen nichts. */
@Composable
fun SagaIcon(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val res = SagaIcons.res(name) ?: return
    SagaIcon(res, contentDescription, modifier, tint)
}
