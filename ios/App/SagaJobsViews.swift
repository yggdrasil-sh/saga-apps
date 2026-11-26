import SwiftUI

// Auftraege im Ort "Saga" (2.6, Bugs-DB 2267 K-B, Konzept K1/K2/K3, UX R3/R4/R6, §0 B3-B5): Auftrag geben, Plan-Karte
// (einziges Freigabe-Muster "Passt, los · Aendern · Lieber nicht"), Rueckmeldung (Ergebnis zuerst + Rueckgaengig) und
// Liste in 4 Gruppen mit Detail (genaues Zustandswort, Protokoll). Zustandswoerter, Klassen, "Ich lese dafuer: …" und
// Saetze kommen als Systemtext vom Server und werden unveraendert gezeigt. Neue Karten nehmen nie den Fokus.

/// Bereich "Auftraege": Eingabe, Buehne (Karte nach "Auftrag geben"), Liste - bzw. das Detail (iPhone, inlineDetail)
struct SagaJobsPane: View {
    @EnvironmentObject var store: Store
    @ObservedObject var m: SagaModel
    /// iPhone: Detail ersetzt die Liste (mit "‹ Aufträge"); iPad: Detail steht rechts
    var inlineDetail: Bool
    @State private var text = ""

    var body: some View {
        if inlineDetail && m.openJob != nil {
            SagaJobDetailView(m: m, closeLabel: false)
        } else {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        if let s = m.stage { stage(s) }
                        list
                    }
                    .padding()
                }
                .refreshable { await m.loadJobs() }
                Divider()
                input
            }
        }
    }

    private var input: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField(String(localized: "Sag \(store.sagaName), was du brauchst …"), text: $text, axis: .vertical)
                .lineLimit(1...4).textFieldStyle(.roundedBorder)
                .accessibilityLabel(Text("Auftrag an \(store.sagaName)"))
            if m.planning {
                Button("Stopp") { m.stopPlanning() }.buttonStyle(.bordered).controlSize(.large)
            } else {
                Button("Auftrag geben") { let t = text; text = ""; m.give(t) }
                    .prominentGold().controlSize(.large)
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !store.reachable)
            }
        }
        .padding(.horizontal).padding(.vertical, 10)
    }

    @ViewBuilder private func stage(_ s: SagaModel.Stage) -> some View {
        switch s {
        case .thinking(let t):
            VStack(alignment: .leading, spacing: 8) {
                SagaHead()
                HStack(spacing: 8) { ProgressView().controlSize(.small); Text(verbatim: t).foregroundStyle(.secondary) }.accessibilityElement(children: .combine)
            }.sagaCard()
        case .plan(let p):
            SagaPlanCard(m: m, plan: p).id("plan-\(p.job)-\(p.planV)")
        case .ask(let q, let choices, let asked):
            VStack(alignment: .leading, spacing: 10) {
                SagaHead()
                Text(verbatim: q).fixedSize(horizontal: false, vertical: true)
                ForEach(choices, id: \.self) { c in
                    Button { m.choose(c, text: asked) } label: { Text(verbatim: c).frame(maxWidth: .infinity, minHeight: 30, alignment: .leading) }
                        .buttonStyle(.bordered).controlSize(.large)
                }
            }.sagaCard()
        case .refuse(let t, let alt, let asked):
            VStack(alignment: .leading, spacing: 10) {
                SagaHead()
                Text(verbatim: t).fixedSize(horizontal: false, vertical: true)
                if let alt {
                    Button { m.alternative(alt, text: asked) } label: {
                        Text(verbatim: alt.label ?? String(localized: "Als Aufgabe anlegen")).frame(maxWidth: .infinity, minHeight: 30)
                    }.prominentGold().controlSize(.large)
                }
                Button { text = asked; m.stage = nil } label: { Text("Anders formulieren").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.borderless)
            }.sagaCard()
        case .done(let job, let st, let t):
            VStack(alignment: .leading, spacing: 8) {
                SagaHead(extra: st)
                if !t.isEmpty { Text(verbatim: t).fixedSize(horizontal: false, vertical: true) }
                Button("Ansehen") { Task { await m.open(job) } }.buttonStyle(.bordered).frame(minHeight: 44)
            }.sagaCard()
        case .feedback(let d):
            SagaFeedbackCard(m: m, detail: d, onOpen: { Task { await m.open(d.id) } })
        case .error(let e):
            VStack(alignment: .leading, spacing: 8) {
                SagaHead()
                Label(e, systemImage: "exclamationmark.circle").foregroundStyle(.orange)
                Button("Schließen") { m.stage = nil }.buttonStyle(.bordered).frame(minHeight: 44)
            }.sagaCard()
        }
    }

    @ViewBuilder private var list: some View {
        if m.jobsLoaded && m.jobs.total == 0 {
            VStack(alignment: .leading, spacing: 10) {
                Text("Noch keine Aufträge. Gib mir einen, z. B. ‚Erinnere mich Freitag an den Pavillon‘.").foregroundStyle(.secondary)
                let ex = String(localized: "Erinnere mich Freitag an den Pavillon")
                Button { m.give(ex) } label: {
                    Text(verbatim: ex).padding(.horizontal, 14).frame(minHeight: 44)
                        .background(Color.sagaTint, in: Capsule()).overlay(Capsule().strokeBorder(Color.sagaEdge.opacity(0.5)))
                }.buttonStyle(.plain).disabled(m.planning)
            }
        }
        ForEach(m.jobs.visible) { g in
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Text(verbatim: g.label).font(.headline)
                    Text(verbatim: "\(g.items.count)").font(.caption.bold()).padding(.horizontal, 7).padding(.vertical, 2)
                        .background(Color.secondary.opacity(0.15), in: Capsule())
                }
                .accessibilityElement(children: .combine).accessibilityAddTraits(.isHeader)
                ForEach(g.items) { it in row(it, group: g.key) }
            }
        }
    }

    private func row(_ it: SagaJobItem, group: String) -> some View {
        Button { Task { await m.open(it.id) } } label: {
            HStack(spacing: 12) {
                Image(systemName: SagaLogic.groupSymbol(group))
                    .foregroundStyle(group == "wartet_auf_dich" ? Color.gold : group == "nicht_geklappt" ? Color.red : Color.secondary)
                    .font(group == "wartet_auf_dich" ? .caption : .body).frame(width: 22).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: it.title.isEmpty ? String(localized: "Auftrag") : it.title).foregroundStyle(.primary).multilineTextAlignment(.leading)
                    Text(verbatim: it.subline).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary).accessibilityHidden(true)
            }
            .padding(.vertical, 8).padding(.horizontal, 10).frame(minHeight: 44).contentShape(Rectangle())
            .background(m.openJob == it.id ? Color.gold.opacity(0.14) : Color.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

/// Felder, die "Ändern" anbietet (steps[].edit) - Beschriftung wie im Web
enum SagaFieldNames {
    static func label(_ k: String) -> String {
        switch k {
        case "text": return String(localized: "Text")
        case "due": return String(localized: "Fällig am")
        case "markdown": return String(localized: "Wortlaut")
        case "summary", "title": return String(localized: "Titel")
        case "start": return String(localized: "Beginn")
        case "end": return String(localized: "Ende")
        case "location": return String(localized: "Ort")
        case "allday": return String(localized: "ganztägig")
        default: return k
        }
    }
}

/// B3 Plan-Karte: "Wartet auf dein OK" · "So würde ich es machen:" · Schritte (Symbol + Text + Klasse als Wort) ·
/// Wortlaut · "Ich lese dafür: …" · Passt, los (volle Breite) · Ändern · Lieber nicht (Textknopf mit Abstand)
struct SagaPlanCard: View {
    @EnvironmentObject var store: Store
    @ObservedObject var m: SagaModel
    let plan: SagaPlan
    @State private var editing = false
    @State private var values: [Int: [String: SagaField]] = [:]
    @State private var err: String?
    @State private var working = false
    @State private var reporting: SagaReportItem?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SagaHead(extra: String(localized: "Wartet auf dein OK"))
            if plan.edited { Label("geändert – bitte neu freigeben", systemImage: "pencil").font(.subheadline).foregroundStyle(.secondary) }
            Text(verbatim: plan.say ?? String(localized: "So würde ich es machen:")).fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(plan.steps.enumerated()), id: \.element.id) { i, s in step(s, i: i) }
            }
            if !plan.readText.isEmpty { Text(verbatim: plan.readText).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
            if let err { Label(err, systemImage: "exclamationmark.circle").font(.subheadline).foregroundStyle(.orange) }
            if !store.reachable && !editing {
                Text("Offline – gespeicherter Stand. Freigeben geht, sobald du wieder verbunden bist.").font(.footnote).foregroundStyle(.secondary)
            }
            actions
            Button("Melden …") { reporting = SagaReportItem(where: "job", job: plan.job, text: SagaLogic.planText(plan)) }
                .font(.subheadline).buttonStyle(.borderless).foregroundStyle(.secondary).frame(minHeight: 44)
        }
        .sagaCard()
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Plan: \(plan.title)"))
        .sheet(item: $reporting) { r in SagaReportSheet(m: m, item: r) }
    }

    @ViewBuilder private func step(_ s: SagaStep, i: Int) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(verbatim: s.symbol).font(.body.weight(.semibold)).frame(width: 20).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: s.text).fixedSize(horizontal: false, vertical: true)
                    Text(verbatim: s.classText).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("Schritt \(i + 1) von \(plan.steps.count), \(s.kindText), \(s.classText): \(s.text)"))
            if editing && !s.edit.isEmpty {
                VStack(alignment: .leading, spacing: 8) { ForEach(s.edit, id: \.self) { k in field(s, k) } }
                    .padding(.leading, 28)
            } else if let w = s.wording, !w.isEmpty {
                AnswerText(text: w, sources: [], openPage: { _ in })
                    .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
                    .padding(.leading, 28)
            }
        }
    }

    private func binding(_ s: SagaStep, _ k: String) -> Binding<SagaField> {
        Binding(get: { values[s.n]?[k] ?? s.initial(k) },
                set: { v in var x = values[s.n] ?? [:]; x[k] = v; values[s.n] = x })
    }

    @ViewBuilder private func field(_ s: SagaStep, _ k: String) -> some View {
        let b = binding(s, k)
        let name = SagaFieldNames.label(k)
        if k == "allday" {
            Toggle(name, isOn: Binding(get: { b.wrappedValue.bool }, set: { b.wrappedValue = .flag($0) })).frame(minHeight: 44)
        } else if let kind = SagaLogic.dateKind(field: k, value: b.wrappedValue.string) {
            let cur = SagaLogic.date(b.wrappedValue.string, kind: kind)
            HStack {
                if let cur {
                    DatePicker(name, selection: Binding(get: { cur }, set: { b.wrappedValue = .text(SagaLogic.string($0, kind: kind)) }),
                               displayedComponents: kind == .day ? [.date] : [.date, .hourAndMinute])
                    if k != "start" {
                        Button { b.wrappedValue = .text("") } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                            .buttonStyle(.borderless).frame(minWidth: 44, minHeight: 44).accessibilityLabel(Text("\(name) entfernen"))
                    }
                } else {
                    Text(verbatim: name)
                    Spacer()
                    Button("Datum wählen") { b.wrappedValue = .text(SagaLogic.string(Date(), kind: kind)) }.buttonStyle(.bordered).frame(minHeight: 44)
                }
            }
        } else {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: name).font(.subheadline).foregroundStyle(.secondary)
                TextField(name, text: Binding(get: { b.wrappedValue.string }, set: { b.wrappedValue = .text($0) }), axis: .vertical)
                    .lineLimit(k == "markdown" ? 3...8 : 1...3).textFieldStyle(.roundedBorder)
            }
        }
    }

    @ViewBuilder private var actions: some View {
        if editing {
            VStack(spacing: 10) {
                Button { Task { await save() } } label: { Text("Speichern").frame(maxWidth: .infinity, minHeight: 30) }
                    .prominentGold().controlSize(.large).disabled(working)
                Button { editing = false; values = [:]; err = nil } label: { Text("Abbrechen").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.borderless)
            }
        } else {
            VStack(spacing: 10) {
                // Enter gibt nie frei: kein Tastenkuerzel auf "Passt, los"
                Button { Task { await approve() } } label: { Text("Passt, los").frame(maxWidth: .infinity, minHeight: 30) }
                    .prominentGold().controlSize(.large).disabled(working || !store.reachable)
                if plan.editable {
                    Button { editing = true; err = nil } label: { Text("Ändern").frame(maxWidth: .infinity, minHeight: 30) }
                        .buttonStyle(.bordered).controlSize(.large).disabled(working)
                }
                Button { Task { await cancel() } } label: { Text("Lieber nicht").frame(maxWidth: .infinity, minHeight: 44) }
                    .buttonStyle(.borderless).padding(.top, 6).disabled(working)
            }
        }
    }

    private func approve() async { working = true; err = await m.approve(plan); working = false }
    private func cancel() async { working = true; err = await m.cancel(plan.job); working = false }
    private func save() async {
        working = true
        let (np, e) = await m.saveEdit(plan, values: values)
        working = false
        if np != nil { editing = false; values = [:]; err = nil } else { err = e }
    }
}

/// B4 Rueckmeldung: Ergebnis zuerst, je Aenderung eine Zeile mit Knopf "Rückgängig" (>= 44 pt)
struct SagaFeedbackCard: View {
    @EnvironmentObject var store: Store
    @ObservedObject var m: SagaModel
    let detail: SagaJobDetail
    var onOpen: (() -> Void)?
    @State private var changed: [Int: String] = [:]                 // Aenderung -> alte-Fassung-Pfad ("" = ohne)
    @State private var askDelete: Int?
    @State private var err: String?
    @State private var working: Int?
    @State private var old: SagaOldVersion?
    @State private var reporting: SagaReportItem?

    var body: some View {
        let rep = detail.feedback ?? SagaReport(text: detail.reasonText ?? detail.stateText)
        VStack(alignment: .leading, spacing: 10) {
            SagaHead(extra: detail.stateText)
            AnswerText(text: rep.text, sources: [], openPage: { _ in })
            ForEach(rep.lines) { ln in line(ln) }
            if let err { Label(err, systemImage: "exclamationmark.circle").font(.subheadline).foregroundStyle(.orange) }
            if let onOpen { Button("Ansehen", action: onOpen).buttonStyle(.bordered).frame(minHeight: 44) }
            if !rep.text.isEmpty {
                Button("Melden …") { reporting = SagaReportItem(where: "job", job: detail.id, text: rep.text) }
                    .font(.subheadline).buttonStyle(.borderless).foregroundStyle(.secondary).frame(minHeight: 44)
            }
        }
        .sagaCard()
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(verbatim: detail.stateText))
        .confirmationDialog("Wieder löschen?", isPresented: Binding(get: { askDelete != nil }, set: { if !$0 { askDelete = nil } }), titleVisibility: .visible) {
            Button("Löschen", role: .destructive) { if let c = askDelete { Task { await undo(c) } } }
            Button("Behalten", role: .cancel) {}
        } message: { Text("Saga hat das neu angelegt – Rückgängig löscht es wieder.") }
        .sheet(item: $old) { o in SagaOldVersionSheet(old: o) }
        .sheet(item: $reporting) { r in SagaReportSheet(m: m, item: r) }
    }

    @ViewBuilder private func line(_ ln: SagaReport.Line) -> some View {
        let ch = detail.change(ln.change)
        VStack(alignment: .leading, spacing: 6) {
            Text(verbatim: ln.text).fixedSize(horizontal: false, vertical: true)
            if ln.undone || ch?.undone == true {
                Text("Zurückgenommen").font(.subheadline).foregroundStyle(.secondary)
            } else if let path = changed[ln.change] ?? (ch?.why == "changed" ? (ch?.versionURL ?? "") : nil) {
                changedNote(path)
            } else if ln.canUndo {
                Button {
                    if ch?.undoClass == "delete_only" { askDelete = ln.change } else { Task { await undo(ln.change) } }
                } label: { Text("Rückgängig").frame(minWidth: 120, minHeight: 30) }
                .buttonStyle(.bordered).controlSize(.large).disabled(working != nil)
                .accessibilityLabel(Text("Rückgängig: \(ln.text)"))
            }
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder private func changedNote(_ path: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Das wurde seitdem geändert.").font(.subheadline).foregroundStyle(.secondary)
            if !path.isEmpty {
                Button("Alte Fassung ansehen") {
                    Task { if let (t, x) = await m.before(path) { old = SagaOldVersion(title: t, text: x) } else { err = String(localized: "Das hat nicht geklappt.") } }
                }.buttonStyle(.bordered).frame(minHeight: 44)
            }
        }
    }

    private func undo(_ c: Int) async {
        working = c; err = nil
        switch await m.undo(job: detail.id, change: c) {
        case .ok: break
        case .changed(let u): changed[c] = u ?? ""
        case .failed(let e): err = e
        }
        working = nil
    }
}

struct SagaOldVersion: Identifiable { let id = UUID(); var title: String; var text: String }

struct SagaOldVersionSheet: View {
    let old: SagaOldVersion
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if !old.title.isEmpty { Text(verbatim: old.title).font(.headline) }
                    Text(verbatim: old.text).font(.body.monospaced()).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                }.padding().frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle("Alte Fassung")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
        .macFrame(minWidth: 420, idealWidth: 520, minHeight: 360)
    }
}

/// Auftrags-Detail: genaues Zustandswort + Grund, Plan-Karte (solange "Wartet auf dein OK") bzw. Rueckmeldung und Plan,
/// "Was ich gelesen habe", Protokoll, "Auftrag abbrechen"
struct SagaJobDetailView: View {
    @EnvironmentObject var store: Store
    @ObservedObject var m: SagaModel
    /// iPad: "Schließen" (zurueck zum Gespraech); iPhone: "‹ Aufträge"
    var closeLabel: Bool
    @State private var err: String?
    @AccessibilityFocusState private var titleFocus: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Button { m.close() } label: {
                    if closeLabel { Label("Schließen", systemImage: "xmark") } else { Label("Aufträge", systemImage: "chevron.left") }
                }
                .buttonStyle(.borderless).frame(minHeight: 44)
                if let d = m.detail, d.id == m.openJob {
                    content(d)
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .padding()
            .frame(maxWidth: 760, alignment: .leading).frame(maxWidth: .infinity)
        }
        .refreshable { if let id = m.openJob { await m.open(id) } }
    }

    @ViewBuilder private func content(_ d: SagaJobDetail) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: d.title.isEmpty ? String(localized: "Auftrag") : d.title).font(.title3.weight(.semibold))
                .accessibilityAddTraits(.isHeader).accessibilityFocused($titleFocus)
            (Text(verbatim: d.stateText).bold() + Text(verbatim: d.reasonText.map { " – " + $0 } ?? ""))
                .fixedSize(horizontal: false, vertical: true)
        }
        .onAppear { titleFocus = true }                          // eigener Schritt (antippen): Fokus auf den Titel
        if d.awaitingOK, let p = d.plan {
            SagaPlanCard(m: m, plan: p).id("dplan-\(p.job)-\(p.planV)")
        } else {
            if d.feedback != nil { SagaFeedbackCard(m: m, detail: d) }
            if !d.planSteps.isEmpty {
                section("Plan") {
                    ForEach(d.planSteps) { s in
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text(verbatim: s.symbol).frame(width: 20).accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: s.text).fixedSize(horizontal: false, vertical: true)
                                Text(verbatim: s.classText).font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                        .accessibilityElement(children: .combine)
                    }
                    if let at = d.approvedAt {
                        Text(verbatim: String(localized: "Freigegeben: \(Self.when(at))") + (d.approvedDevice.map { " · " + $0 } ?? ""))
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            }
        }
        if let labels = d.plan?.readLabels, !labels.isEmpty {
            section("Was ich gelesen habe") { Text(verbatim: labels.joined(separator: ", ")).fixedSize(horizontal: false, vertical: true) }
        }
        if !d.events.isEmpty {
            section("Protokoll") {
                ForEach(d.events) { e in
                    (Text(verbatim: Self.when(e.at)).font(.subheadline.monospacedDigit()).foregroundStyle(.secondary) + Text(verbatim: "  " + e.text))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        if let err { Label(err, systemImage: "exclamationmark.circle").foregroundStyle(.orange) }
        if d.cancellable {
            Button(role: .destructive) { Task { err = await m.cancel(d.id) } } label: { Text("Auftrag abbrechen").frame(minHeight: 30) }
                .buttonStyle(.bordered).controlSize(.large)
        }
    }

    private func section<C: View>(_ title: LocalizedStringKey, @ViewBuilder _ c: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline).accessibilityAddTraits(.isHeader)
            c()
        }
    }
    static func when(_ ts: Double) -> String { Date(timeIntervalSince1970: ts).formatted(date: .abbreviated, time: .shortened) }
}
