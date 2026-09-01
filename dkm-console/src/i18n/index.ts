/**
 * Translations for the console.
 *
 * <p>Hand-rolled and dependency-free: the whole surface is a couple of hundred
 * strings, and an i18n framework would add a build step, a loader and a set of
 * conventions to solve a problem this size does not have.
 *
 * <p>English is the reference. A key missing from Turkish falls back to it, so
 * an incomplete translation degrades into a readable interface rather than a
 * broken one, and says so once in the console during development.
 *
 * <p>House style for both languages: say what the control does, in one line.
 * No dashes standing in for punctuation, no sentences that explain the
 * architecture. Anything that needs a paragraph belongs in the documentation,
 * not in a tooltip.
 */

export type Lang = 'en' | 'tr'

export const LANGUAGES: { code: Lang; label: string }[] = [
    { code: 'en', label: 'EN' },
    { code: 'tr', label: 'TR' },
]

const en = {
    // ---- shell
    'app.brand': 'DKM',
    'app.brandSub': 'SIMULATOR',
    'app.loading': 'Loading the interface schema',
    'app.unreachableTitle': 'Cannot reach the gateway',
    'app.unreachableHint':
        'Start it with "mvn quarkus:dev" in dkm-gateway, or set DKM_GATEWAY to another host.',
    'app.retry': 'Retry',
    'app.footerTypes': '{count} message types, size_t {bytes} B, {order}',
    'app.footerNote': 'The DKM connects out once at its own startup and does not retry.',
    'app.interface': 'Interface',

    // ---- settings
    'settings.theme': 'Theme',
    'settings.theme.system': 'System',
    'settings.theme.light': 'Light',
    'settings.theme.dark': 'Dark',
    'settings.language': 'Language',
    'settings.help': 'Replay the introduction',

    // ---- links
    'link.CONNECTED': 'The DKM is attached.',
    'link.LISTENING': 'Bound and waiting. Start the DKM now; it connects once and does not retry.',
    'link.CLOSED': 'The DKM connected, then disconnected.',
    'link.FAILED': 'Could not bind the port, or the stream lost framing.',
    'link.DOWN': 'Not bound.',
    'link.none': 'No links configured',

    // ---- transport
    'transport.start': 'Start',
    'transport.resume': 'Resume',
    'transport.pause': 'Pause',
    'transport.stop': 'Stop',
    'transport.startTitle': 'Send the stimulus set to the DKM.',
    'transport.resumeTitle': 'Continue. Send times are recalculated, so edits made while paused take effect.',
    'transport.pauseTitle': 'Stop sending. Connections stay open and pending messages become editable.',
    'transport.stopTitle': 'Stop and rewind to the start. The whole set becomes editable again.',
    'transport.speed': 'Speed',
    'transport.speedTitle': 'Applies immediately. The replay position does not jump, only the rate changes.',
    'transport.timed': 'Timed',
    'transport.maxRate': 'Full rate',
    'transport.modeTitle':
        'Timed: every link is paced from one shared replay clock.\nFull rate: recorded timing is ignored and messages go out as fast as the DKM accepts them.',
    'transport.progress': '{sent} of {planned}',
    'transport.lag': 'Lag {lag} ms',
    'transport.lagTitle': 'How far behind the recorded timeline the replay is. Zero means this speed is being reproduced exactly.',
    'transport.rateIn': 'Total rate coming back from the DKM',
    'transport.rateOut': 'Total stimulus rate going to the DKM',
    'transport.events': 'Events',
    'transport.viz': 'Plan',
    'transport.eventsTitle': 'Control event stream',
    'transport.vizTitle': 'Plan view data stream',

    // ---- files
    'file.load': 'Open .bin',
    'file.loadTitle': 'Open an existing input binary.',
    'file.saveInput': 'Save input',
    'file.saveInputTitle': 'Save the edited set in the original binary format.',
    'file.saveOutput': 'Save output',
    'file.saveOutputTitle': 'Save captured DKM output in the existing output format.',
    'file.loaded': 'Loaded {count} messages from {name}.',
    'file.loadedWithProblems':
        'Loaded {count} messages from {name}. {problems} of them cannot be sent; see the list.',
    'file.summary': '{source} · {input} in · {output} out',

    // ---- banners
    'banner.paused':
        'Paused. Messages that have not been sent yet are editable, and resuming recalculates their send times. Messages already sent stay as history.',

    // ---- message list
    'list.stimulus': 'Stimulus',
    'list.capture': 'Capture',
    'list.trace': 'Timeline',
    'list.counts': '{filtered} of {total}',
    'list.allLinks': 'All links',
    'list.allTypes': 'All types',
    'list.statusAll': 'All',
    'list.statusPending': 'Pending',
    'list.statusSent': 'Sent',
    'list.statusProblem': 'Blocked',
    'list.follow': 'Follow',
    'list.followTitle': 'Keep the newest messages in view.',
    'list.sort': 'Sort',
    'list.sort.sequence': 'File order',
    'list.sort.timestamp': 'Timestamp',
    'list.sort.type': 'Type',
    'list.sort.link': 'Link',
    'list.sort.length': 'Size',
    'list.sort.wallclock': 'Time sent',
    'list.sortAsc': 'Ascending. Click for descending.',
    'list.sortDesc': 'Descending. Click for ascending.',
    'list.previous': 'Previous',
    'list.next': 'Next',
    'list.range': '{from} to {to}',
    'list.emptyStimulus': 'No stimulus loaded. Open a .bin file, or build a message in the inspector.',
    'list.emptyCapture': 'Nothing captured yet. Output appears here as the DKM sends it.',
    'list.emptyTrace': 'Nothing has crossed the wire yet.',
    'list.overflow':
        'Capture buffer full. {count} messages were not kept. Save and clear the capture, or raise dkm.capture.max-messages.',
    'dialog.cancel': 'Cancel',
    'dialog.clearTitle': 'Clear the stimulus set',
    'dialog.clearBody': 'This removes every loaded message. The capture and the library are untouched.',
    'dialog.clearDirty': 'This set has unsaved edits. Save the input first if you want to keep them.',
    'dialog.clearConfirm': 'Clear',
    'dialog.deleteTitle': 'Delete this message',
    'dialog.deleteBody': 'Message {index} of the set, {type}, will be removed.',
    'dialog.deleteConfirm': 'Delete',
    'dialog.problemsTitle': 'Some messages cannot be sent',
    'dialog.problemsBody':
        '{count} message(s) failed to decode against the current schema. They stay in the list and are saved byte for byte, but the run will skip them.',
    'dialog.problemsConfirm': 'Start anyway',
    'dialog.problemsReview': 'Review them first',
    'list.reset': 'Reset filters',
    'list.resetTitle': 'Clears the filters and sorting only. The messages are untouched.',
    'list.clear': 'Clear all',
    'list.clearTitle': 'Removes every loaded message from the set.',
    'list.timeCumulative': 'From the start',
    'list.timeDelta': 'Since previous',
    'list.timeTitle': 'Whether the first column counts from the start of the recording or from the message above it',
    'list.pageOf': 'Page {page} / {pages}',
    'list.first': 'First',
    'list.last': 'Last',
    'list.reorderHint': 'Drag a pending message to change what goes out first. Ctrl+C then Ctrl+V copies one, Delete removes it.',
    'list.moved': 'Moved to position {index}.',
    'list.copied': 'Copied {type}. Ctrl+V inserts it after the selected message.',
    'list.pasted': 'Inserted a copy of {type} at position {index}, +{offset} ms.',
    'list.pasteEmpty': 'Nothing copied yet.',
    'list.cleared': 'Stimulus set cleared.',
    'new.placement': 'Insert at',
    'new.placeAfter': 'After the selected',
    'new.placeEnd': 'End of list',
    'new.placeAfterTitle': 'Goes in immediately after the message selected in the list',
    'new.placeEndTitle': 'Goes to the end of the stimulus list',
    'new.noSelection': 'No message selected, so this goes to the end.',
    'app.credit': 'Built by Ahmet ATAR · ASELSAN Summer Internship · 2026',
    'dialog.startTitle': 'Start from the beginning',
    'dialog.startBody':
        'The run will send the whole set, starting at the first message and following its recorded timing.',
    'dialog.startHint':
        'To begin somewhere else, cancel, select the message you want to start from in the list, and press Start again.',
    'dialog.startConfirm': 'Start',
    'dialog.startBack': 'Go back',
    'dialog.startFromTitle': 'Start from the selected message',
    'dialog.startFromBody':
        'The run will begin at message {index}, {type}. Everything before it is treated as already sent and stays untouched.',
    'dialog.startFromHint': 'To send the whole set instead, cancel and clear the selection first.',
    'dialog.startFromConfirm': 'Start here',
    'dialog.revertTitle': 'Go back to the file as loaded',
    'dialog.revertBody':
        'Every edit, insertion, deletion and reorder made since {name} was loaded will be dropped, and the set will read exactly as the file does.',
    'dialog.revertDetail': 'Save the input first if you want to keep the current version.',
    'dialog.revertConfirm': 'Revert',
    'list.revert': 'Revert to file',
    'list.revertTitle': 'Put the set back exactly as the file was loaded',
    'list.reverted': 'Back to {name} as loaded.',
    'list.followRun': 'Follow the run',
    'list.followRunTitle': 'Keep the message being sent in view, turning the page when the run passes the end of this one',
    'list.showing': '{from}–{to} of {total} messages',
    'dialog.stopTitle': 'Stop the run',
    'dialog.stopBody':
        '{sent} of {planned} messages have gone out. Stopping rewinds to the start, so a new run would send them again from the top.',
    'dialog.stopHint': 'To hold where it is and edit what is left, use Pause instead.',
    'dialog.stopConfirm': 'Stop and rewind',
    'file.rate': '{ms} ms · {rate}/s',
    'list.undo': 'Undo',
    'list.redo': 'Redo',
    'list.undoTitle': 'Undo the last change to the set',
    'list.redoTitle': 'Redo the change that was just undone',
    'list.undoWhat': 'Undo: {what}',
    'list.redoWhat': 'Redo: {what}',
    'list.undone': 'Undone: {what}',
    'list.redone': 'Redone: {what}',
    'viz.zoomIn': 'Zoom in',
    'viz.zoomOut': 'Zoom out',
    'viz.zoomInTitle': 'Zoom in about the centre of the view',
    'viz.zoomOutTitle': 'Zoom out about the centre of the view',
    'dialog.placeTitle': 'This will not land where you asked',
    'dialog.placeBody':
        'The list is always in time order, so a message goes where its timestamp puts it. At +{offset} ms after message {index} this one lands at {timestamp} ms, which is past the message that currently follows it ({next} ms).',
    'dialog.placeHint':
        'Insert it anyway and it goes to its place in time, or go back and use an offset under {max} ms to put it immediately after.',
    'dialog.placeConfirm': 'Insert in time order',
    'dialog.negativeTitle': 'A gap cannot run backwards',
    'dialog.negativeBody':
        'An offset of {offset} ms would place this message before the one it is meant to follow. Stimulus is always in ascending time, so the gap has to be zero or more.',
    'dialog.negativeConfirm': 'Understood',
    'inspector.retimeLabel': 'Timestamp',
    'inspector.retimeHint': 'The list stays in time order, so the message moves to where this puts it.',
    'inspector.retimeNaN': 'That is not a number.',
    'inspector.retimeNegative': 'A timestamp cannot be negative.',
    'library.nameLabel': 'Name',
    'library.nameRequired': 'Give it a name you will recognise later.',
    'log.session.loaded': 'Loaded {count} messages from {name} ({bytes} bytes).',
    'log.session.reverted': 'Reverted to {name} as loaded; every edit since was dropped.',
    'log.session.inserted': 'Inserted {type} at position {index}, t = {timestamp} ms ({offset} ms after the one before it).',
    'log.session.undone': 'Undone: {what}.',
    'log.session.redone': 'Redone: {what}.',
    'log.playback.stoppedRewound': 'Stopped and rewound to the start. The whole set is editable again.',
    'log.playback.speed': 'Speed set to {speed}x.',
    'log.playback.mode': 'Pacing mode set to {mode}.',
    'log.capture.cleared': 'Capture cleared.',
    'transport.maxRateWarning': 'Full rate ignores the recorded timing. Use it to measure throughput, not to run a scenario.',
    'transport.step': 'Step',
    'transport.stepFromTitle': 'Send message {index} and wait, then step on from there. Everything before it counts as already sent.',
    'transport.stepTitle': 'Send the next message and wait. For a DKM that needs time on one of them: the clock does not move on while you watch what it did.',
    'log.playback.stepped': 'Stepped {count} message(s); {total} sent so far.',
    'row.sent': 'Sent',
    'row.pending': 'Pending',
    'row.blocked': 'Blocked',
    'row.out': 'Sent to the DKM',
    'row.in': 'Received from the DKM',
    'trace.hint': 'Both directions in the order they happened. The third column is the time since the line above.',

    // ---- inspector
    'inspector.message': 'Message',
    'inspector.new': 'New',
    'inspector.library': 'Library',
    'inspector.selectHint':
        'Select a message to see its fields. Stimulus that has not been sent is editable while the run is paused or stopped. Captured output is always read only.',
    'inspector.fromDkm': 'From DKM',
    'inspector.apply': 'Apply',
    'inspector.revert': 'Revert',
    'inspector.retime': 'Retime',
    'inspector.retimeTitle': 'Change when this message is sent, relative to the rest of the timeline.',
    'inspector.toLibrary': 'To library',
    'inspector.toLibraryTitle': 'Save this message, as edited, for use in other runs.',
    'inspector.delete': 'Delete',
    'inspector.wireHeader': 'Wire header',
    'inspector.link': '{link} link',
    'inspector.bytes': '{count} bytes',
    'inspector.sentAt': 'sent {time}',
    'inspector.receivedAt': 'received {time}',
    'inspector.readOnlyCapture': 'This is a record of what the DKM sent. It cannot be edited.',
    'inspector.readOnlyUndecodable': 'This message does not match the current schema.',
    'inspector.readOnlySent': 'Already sent in this run.',
    'inspector.readOnlyRunning': 'The run is playing. Pause it to edit pending messages.',
    'inspector.updated': '{type} #{id} updated.',
    'inspector.savePrompt': 'Save to library as:',
    'inspector.saved': 'Saved "{name}" to the library.',
    'inspector.retimePrompt': 'Timestamp in milliseconds:',

    // ---- new message
    'new.type': 'Message type',
    'new.index': 'Order',
    'new.offset': 'Offset',
    'new.offsetHint':
        'Milliseconds after the message it follows. Timing is set explicitly rather than inherited from where it is placed.',
    'new.insert': 'Insert',
    'new.pauseFirst': 'Pause the run to change what it sends.',
    'new.inserted': 'Inserted {type} at position {index}, t = {timestamp} ms.',

    // ---- field editor
    'field.headerOnly': 'This message carries only a header.',
    'field.notNamed': '{value}, not named in the schema',
    'field.inactiveSlot': 'Beyond {count}. Still sent, but the DKM ignores it.',
    'field.reduceCount': 'Remove an element',
    'field.addElement': 'Add an element',
    'field.trackId': 'track id',

    // ---- library
    'library.search': 'Search by name, tag or type',
    'library.empty':
        'Nothing saved yet. Select a stimulus message and use "To library" to reuse it across runs.',
    'library.insert': 'Insert',
    'library.delete': 'Delete',
    'library.stale': 'Outdated',
    'library.staleTitle': 'Saved against interface {version}',
    'library.staleConfirm':
        '"{name}" was saved against interface {version}, which is not the one loaded now. Its byte layout may no longer be correct.\n\nInsert it anyway?',
    'library.offsetPrompt': 'Offset in milliseconds from the message it follows:',
    'library.deleteConfirm': 'Delete "{name}" from the library?',
    'library.inserted': 'Inserted "{name}" at t = {timestamp} ms.',
    'library.insertedStale':
        'Inserted "{name}" at t = {timestamp} ms. It was saved against an older interface, so check its fields.',
    'library.stored': 'Stored as JSON files in {directory}.',
    'library.pauseFirst': 'Pause the run to insert into it.',

    // ---- log
    'log.title': 'Session log',
    'log.warnings': 'Warnings',
    'log.warningsTitle': 'Show only warnings and errors.',
    'log.follow': 'Follow',
    'log.empty': 'Nothing logged yet.',
    'log.stalls': '{count} stalls',
    'log.stallsTitle': '{sent} sent, {received} received.\n{stalls} write stalls: the DKM applied backpressure.',
    'log.vizFrames': '{frames} frames',
    'log.vizSkipped': '{count} skipped',
    'log.vizDropped': '{count} dropped',
    'log.vizThinned': '{count} thinned',
    'log.vizThinnedTitle': 'Stimulus outran the display budget. Detail was reduced; no data was lost.',

    // ---- plan view
    'viz.title': 'Plan view',
    'viz.fit': 'Fit',
    'viz.fitTitle': 'Fit everything currently drawn.',
    'viz.live': 'Live',
    'viz.frozen': 'Frozen',
    'viz.heldSteppingTitle': 'Held while you step: marks stay put however long the DKM takes. Press to let the picture age again.',
    'viz.freezeTitle': 'Stop accepting new samples so the current picture can be studied. The gateway keeps receiving.',
    'viz.clear': 'Clear',
    'viz.clearTitle': 'Clear the picture. Captured messages are not affected.',
    'viz.ring': 'Ring {range}',
    'viz.scale': '{value} m/px',
    'viz.convention': 'x = d·cos(h), y = d·sin(h), heading in radians',
    'viz.marks': '{marks} marks, {tracks} tracks',
    'viz.areas': '{gate} gate, {reporting} reporting, {rays} rays',
    'viz.latency': '{ms} ms to screen, {frame} ms per frame',
    'viz.droppedUpstream': '{count} samples dropped',
    'viz.failedTitle': 'The plan view could not start.',
    'viz.failedHint': 'The message lists and playback controls still work.',

    // ---- plan view legend, keyed by message type
    'viz.label.RSP/DetectionReport': 'Detection',
    'viz.label.RSP/JammerReport': 'Jammer strobe',
    'viz.label.RSM/BeamReport': 'Beam',
    'viz.label.RSM/GateAreaMsg': 'Gate area',
    'viz.label.RSM/ReportingAreaMsg': 'Reporting area',
    'viz.label.RSM/MeasurementReport': 'Measurement',
    'viz.label.CRM/Prediction': 'Track',

    'viz.filter.title': 'Show',
    'viz.filter.all': 'all',
    'viz.filter.allTitle': 'Draw every message type again.',
    'viz.filter.none': 'none',
    'viz.filter.noneTitle': 'Hide every type, then switch back on only what you are looking at.',
    'viz.filter.hide': 'Hide {type} on the display. It keeps arriving and stays in the counts.',
    'viz.filter.show': 'Show {type} again, including what arrived while it was hidden.',
    'viz.kind.POINT': 'point',
    'viz.kind.TRACK': 'track',
    'viz.kind.RAY': 'ray',
    'viz.kind.LINE': 'line',
    'viz.kind.CIRCULAR_AREA': 'sector',
    'viz.kind.RECT_AREA': 'rectangle',
    'viz.kind.NONE': 'not drawn',

    // ---- plan view tooltip
    'tip.stimulus': 'Sent',
    'tip.output': 'Received',
    'tip.range': 'Range',
    'tip.bearing': 'Bearing',
    'tip.position': 'Position',
    'tip.velocity': 'Velocity',
    'tip.speed': 'Speed',
    'tip.track': 'Track',
    'tip.points': 'Points',
    'tip.rangeBand': 'Range band',
    'tip.bearingBand': 'Bearing band',
    'tip.width': 'Width',
    'tip.height': 'Height',
    'tip.area': 'Area',
    'tip.age': 'Age',
    'tip.seconds': '{value} s ago',
    'tip.excludes': 'Measurements inside this sector are suppressed.',
    'tip.includes': 'With any reporting area defined, a measurement must fall inside one.',
    'tip.trackHint': 'Observations sharing this id are one object over time.',

    // ---- introduction
    'tour.skip': 'Skip',
    'tour.back': 'Back',
    'tour.next': 'Next',
    'tour.done': 'Get started',
    'tour.step': '{current} of {total}',
    'tour.welcome.title': 'The DKM is connected',
    'tour.welcome.body':
        'All three links are up, so this console is now driving a live DKM. Here is the layout in under a minute. You can skip and reopen it from the ? button at any time.',
    'tour.links.title': 'Connection state',
    'tour.links.body':
        'The RSP, RSM and CRM links. The DKM connects out once at its own startup and never retries, so the gateway has to be listening first. A grey chip here explains a silent run faster than anything else on screen.',
    'tour.transport.title': 'Start, pause, edit, resume',
    'tour.transport.body':
        'Pause stops sending but keeps the connections. Anything not yet sent becomes editable, and resuming recalculates its timing. Speed applies immediately without the position jumping.',
    'tour.list.title': 'Three views of the messages',
    'tour.list.body':
        'Stimulus is what you send, Capture is what came back, and Timeline interleaves both in the order they happened. Filter by link, type and status; sorting applies to the whole set, not just the visible page.',
    'tour.inspector.title': 'Fields, not bytes',
    'tour.inspector.body':
        'Every field of the selected message, with its type, unit and valid range, generated from the interface schema. Adding a message type to the schema gives it a working editor immediately.',
    'tour.viz.title': 'The live picture',
    'tour.viz.body':
        'Areas, beams, detections and tracks exactly as the DKM computes them. Scroll to zoom, drag to pan, and hover anything to see its values.',
    'tour.log.title': 'What happened, and how fast',
    'tour.log.body':
        'Connection events, playback changes and errors, with live throughput per link. Anything that goes wrong is reported here rather than failing quietly.',
} as const

export type TranslationKey = keyof typeof en

const tr: Partial<Record<TranslationKey, string>> = {
    'app.brandSub': 'SİMÜLATÖR',
    'app.loading': 'Arayüz şeması yükleniyor',
    'app.unreachableTitle': 'Gateway’e ulaşılamıyor',
    'app.unreachableHint':
        'dkm-gateway dizininde "mvn quarkus:dev" ile başlatın ya da DKM_GATEWAY ile başka bir sunucu belirtin.',
    'app.retry': 'Tekrar dene',
    'app.footerTypes': '{count} mesaj tipi, size_t {bytes} B, {order}',
    'app.footerNote': 'DKM kendi açılışında bir kez bağlanır, tekrar denemez.',
    'app.interface': 'Arayüz',

    'settings.theme': 'Tema',
    'settings.theme.system': 'Sistem',
    'settings.theme.light': 'Açık',
    'settings.theme.dark': 'Koyu',
    'settings.language': 'Dil',
    'settings.help': 'Tanıtımı yeniden göster',

    'link.CONNECTED': 'DKM bağlı.',
    'link.LISTENING': 'Port dinlemede. DKM’yi şimdi başlatın; bir kez bağlanır, tekrar denemez.',
    'link.CLOSED': 'DKM bağlandı, sonra ayrıldı.',
    'link.FAILED': 'Port açılamadı ya da akışın çerçevelemesi bozuldu.',
    'link.DOWN': 'Port açılmadı.',
    'link.none': 'Tanımlı link yok',

    'transport.start': 'Başlat',
    'transport.resume': 'Devam et',
    'transport.pause': 'Duraklat',
    'transport.stop': 'Durdur',
    'transport.startTitle': 'Uyarım setini DKM’ye gönder.',
    'transport.resumeTitle':
        'Devam et. Gönderim zamanları yeniden hesaplanır, duraklamada yapılan değişiklikler geçerli olur.',
    'transport.pauseTitle': 'Gönderimi durdur. Bağlantılar açık kalır, bekleyen mesajlar düzenlenebilir olur.',
    'transport.stopTitle': 'Durdur ve başa sar. Tüm set yeniden düzenlenebilir hale gelir.',
    'transport.speed': 'Hız',
    'transport.speedTitle': 'Anında uygulanır. Bulunulan an değişmez, yalnızca hız değişir.',
    'transport.timed': 'Zamanlı',
    'transport.maxRate': 'Tam hız',
    'transport.modeTitle':
        'Zamanlı: her link tek bir ortak replay saatinden yürür.\nTam hız: kayıtlı zamanlama yok sayılır, mesajlar DKM kabul ettiği hızda gider.',
    'transport.progress': '{sent} / {planned}',
    'transport.lag': 'Gecikme {lag} ms',
    'transport.lagTitle':
        'Replay’in kayıtlı zaman çizgisine göre ne kadar geride olduğu. Sıfır, bu hızın birebir yeniden üretildiği anlamına gelir.',
    'transport.rateIn': 'DKM’den gelen toplam hız',
    'transport.rateOut': 'DKM’ye giden toplam uyarım hızı',
    'transport.events': 'Olay',
    'transport.viz': 'Plan',
    'transport.eventsTitle': 'Kontrol olayı akışı',
    'transport.vizTitle': 'Plan görünümü veri akışı',

    'file.load': '.bin aç',
    'file.loadTitle': 'Mevcut bir girdi binary dosyası aç.',
    'file.saveInput': 'Girdiyi kaydet',
    'file.saveInputTitle': 'Düzenlenmiş seti orijinal binary formatında kaydet.',
    'file.saveOutput': 'Çıktıyı kaydet',
    'file.saveOutputTitle': 'Yakalanan DKM çıktısını mevcut çıktı formatında kaydet.',
    'file.loaded': '{name} dosyasından {count} mesaj yüklendi.',
    'file.loadedWithProblems':
        '{name} dosyasından {count} mesaj yüklendi. Bunların {problems} tanesi gönderilemiyor, listeye bakın.',
    'file.summary': '{source} · {input} giriş · {output} çıkış',

    'banner.paused':
        'Duraklatıldı. Henüz gönderilmemiş mesajlar düzenlenebilir; devam edildiğinde gönderim zamanları yeniden hesaplanır. Gönderilmiş mesajlar geçmiş olarak kalır.',

    'list.stimulus': 'Uyarım',
    'list.capture': 'Yakalama',
    'list.trace': 'Zaman çizgisi',
    'list.counts': '{total} içinden {filtered}',
    'list.allLinks': 'Tüm linkler',
    'list.allTypes': 'Tüm tipler',
    'list.statusAll': 'Hepsi',
    'list.statusPending': 'Bekleyen',
    'list.statusSent': 'Gönderilen',
    'list.statusProblem': 'Engelli',
    'list.follow': 'Takip',
    'list.followTitle': 'En yeni mesajları görünür tut.',
    'list.sort': 'Sırala',
    'list.sort.sequence': 'Dosya sırası',
    'list.sort.timestamp': 'Zaman damgası',
    'list.sort.type': 'Tip',
    'list.sort.link': 'Link',
    'list.sort.length': 'Boyut',
    'list.sort.wallclock': 'Gönderim anı',
    'list.sortAsc': 'Artan. Azalan için tıklayın.',
    'list.sortDesc': 'Azalan. Artan için tıklayın.',
    'list.previous': 'Önceki',
    'list.next': 'Sonraki',
    'list.range': '{from} ile {to} arası',
    'list.emptyStimulus': 'Yüklü uyarım yok. Bir .bin dosyası açın ya da denetçiden mesaj oluşturun.',
    'list.emptyCapture': 'Henüz yakalanan yok. DKM gönderdikçe çıktı burada görünür.',
    'list.emptyTrace': 'Telden henüz hiçbir şey geçmedi.',
    'list.overflow':
        'Yakalama arabelleği doldu. {count} mesaj saklanamadı. Yakalamayı kaydedip temizleyin ya da dkm.capture.max-messages değerini artırın.',
    'dialog.cancel': 'Vazgeç',
    'dialog.clearTitle': 'Uyarım setini temizle',
    'dialog.clearBody': 'Yüklü bütün mesajlar kaldırılır. Yakalama ve kütüphane etkilenmez.',
    'dialog.clearDirty': 'Bu sette kaydedilmemiş düzenlemeler var. Korumak istiyorsanız önce girdiyi kaydedin.',
    'dialog.clearConfirm': 'Temizle',
    'dialog.deleteTitle': 'Bu mesajı sil',
    'dialog.deleteBody': 'Setteki {index}. mesaj, {type}, kaldırılacak.',
    'dialog.deleteConfirm': 'Sil',
    'dialog.problemsTitle': 'Gönderilemeyecek mesajlar var',
    'dialog.problemsBody':
        '{count} mesaj mevcut şemaya göre çözülemedi. Listede kalır ve bayt bayt saklanır, ama koşu onları atlar.',
    'dialog.problemsConfirm': 'Yine de başlat',
    'dialog.problemsReview': 'Önce inceleyeyim',
    'list.reset': 'Filtreyi sıfırla',
    'list.resetTitle': 'Yalnızca filtreleri ve sıralamayı temizler. Mesajlara dokunmaz.',
    'list.clear': 'Listeyi boşalt',
    'list.clearTitle': 'Yüklü bütün mesajları setten kaldırır.',
    'list.timeCumulative': 'Baştan',
    'list.timeDelta': 'Öncekinden',
    'list.timeTitle': 'İlk sütun kaydın başından mı, yoksa bir üstteki mesajdan mı saysın',
    'list.pageOf': 'Sayfa {page} / {pages}',
    'list.first': 'İlk',
    'list.last': 'Son',
    'list.reorderHint': 'Bekleyen bir mesajı sürükleyerek sırasını değiştirin. Ctrl+C, Ctrl+V kopyalar; Delete siler.',
    'list.moved': '{index}. sıraya taşındı.',
    'list.copied': '{type} kopyalandı. Ctrl+V seçili mesajdan sonra ekler.',
    'list.pasted': '{type} kopyası {index}. sıraya eklendi, +{offset} ms.',
    'list.pasteEmpty': 'Henüz bir şey kopyalanmadı.',
    'list.cleared': 'Uyarım seti temizlendi.',
    'new.placement': 'Ekleme yeri',
    'new.placeAfter': 'Seçilinin ardına',
    'new.placeEnd': 'Listenin sonuna',
    'new.placeAfterTitle': 'Listede seçili olan mesajın hemen ardına girer',
    'new.placeEndTitle': 'Uyarım listesinin sonuna gider',
    'new.noSelection': 'Seçili mesaj yok, bu yüzden sona eklenecek.',
    'app.credit': 'Ahmet ATAR · ASELSAN A Yetenek Yaz Stajı · 2026',
    'dialog.startTitle': 'Baştan başlat',
    'dialog.startBody':
        'Koşu setin tamamını gönderecek: ilk mesajdan başlayacak ve kayıttaki zamanlamayı izleyecek.',
    'dialog.startHint':
        'Başka bir yerden başlamak isterseniz vazgeçip listeden başlangıç mesajını seçin, sonra Başlat’a yeniden basın.',
    'dialog.startConfirm': 'Başlat',
    'dialog.startBack': 'Geri dön',
    'dialog.startFromTitle': 'Seçili mesajdan başlat',
    'dialog.startFromBody':
        'Koşu {index}. mesajdan, {type}, başlayacak. Öncesindeki her şey gönderilmiş sayılacak ve olduğu gibi kalacak.',
    'dialog.startFromHint': 'Bunun yerine setin tamamını göndermek için vazgeçip seçimi kaldırın.',
    'dialog.startFromConfirm': 'Buradan başlat',
    'dialog.revertTitle': 'Dosyanın ilk haline dön',
    'dialog.revertBody':
        '{name} yüklendiğinden beri yapılan bütün düzenlemeler, eklemeler, silmeler ve sıra değişiklikleri geri alınacak; set dosyadaki haliyle aynı olacak.',
    'dialog.revertDetail': 'Şu anki hali korumak istiyorsanız önce girdiyi kaydedin.',
    'dialog.revertConfirm': 'Geri dön',
    'list.revert': 'Dosyaya dön',
    'list.revertTitle': 'Seti, dosya yüklendiği andaki haline geri getir',
    'list.reverted': '{name} dosyasının ilk haline dönüldü.',
    'list.followRun': 'Koşuyu izle',
    'list.followRunTitle': 'Gönderilmekte olan mesajı görüş alanında tut; koşu sayfanın sonunu geçtiğinde sayfayı da çevir',
    'list.showing': '{total} kayıttan {from}–{to} arası',
    'dialog.stopTitle': 'Koşuyu durdur',
    'dialog.stopBody':
        '{planned} mesajın {sent} tanesi gönderildi. Durdurmak başa sarar; yeni bir koşu bunları baştan yeniden gönderir.',
    'dialog.stopHint': 'Bulunduğu yerde tutup kalanları düzenlemek için Durdur yerine Duraklat’ı kullanın.',
    'dialog.stopConfirm': 'Durdur ve başa sar',
    'file.rate': '{ms} ms · {rate}/s',
    'list.undo': 'Geri al',
    'list.redo': 'Yinele',
    'list.undoTitle': 'Sette yapılan son değişikliği geri al',
    'list.redoTitle': 'Az önce geri alınan değişikliği yeniden uygula',
    'list.undoWhat': 'Geri al: {what}',
    'list.redoWhat': 'Yinele: {what}',
    'list.undone': 'Geri alındı: {what}',
    'list.redone': 'Yeniden uygulandı: {what}',
    'viz.zoomIn': 'Yakınlaştır',
    'viz.zoomOut': 'Uzaklaştır',
    'viz.zoomInTitle': 'Görüntünün merkezine göre yakınlaştır',
    'viz.zoomOutTitle': 'Görüntünün merkezine göre uzaklaştır',
    'dialog.placeTitle': 'Bu, istediğiniz yere gelmeyecek',
    'dialog.placeBody':
        'Liste her zaman zaman sırasındadır; bir mesaj zaman damgasının koyduğu yere gider. {index}. mesajdan +{offset} ms sonra dediğinizde bu mesaj {timestamp} ms olur, ki bu şu an onu izleyen mesajın ({next} ms) ötesindedir.',
    'dialog.placeHint':
        'Yine de eklerseniz zamansal olarak ait olduğu yere gider; ya da geri dönüp hemen ardına koymak için {max} ms altında bir fark verin.',
    'dialog.placeConfirm': 'Zaman sırasına ekle',
    'dialog.negativeTitle': 'Zaman farkı geriye işleyemez',
    'dialog.negativeBody':
        '{offset} ms’lik bir fark, bu mesajı ardına geleceği mesajdan önceye koyar. Uyarım her zaman artan zamandadır; fark sıfır veya daha büyük olmalıdır.',
    'dialog.negativeConfirm': 'Anladım',
    'inspector.retimeLabel': 'Zaman damgası',
    'inspector.retimeHint': 'Liste zaman sırasında kalır; mesaj bu değerin koyduğu yere taşınır.',
    'inspector.retimeNaN': 'Bu bir sayı değil.',
    'inspector.retimeNegative': 'Zaman damgası negatif olamaz.',
    'library.nameLabel': 'Ad',
    'library.nameRequired': 'Sonradan tanıyacağınız bir ad verin.',
    'log.session.loaded': '{name} dosyasından {count} mesaj yüklendi ({bytes} bayt).',
    'log.session.reverted': '{name} dosyasının yüklendiği hale dönüldü; sonraki bütün düzenlemeler bırakıldı.',
    'log.session.inserted': '{type} {index}. sıraya eklendi, t = {timestamp} ms (bir öncekinden {offset} ms sonra).',
    'log.session.undone': 'Geri alındı: {what}.',
    'log.session.redone': 'Yeniden uygulandı: {what}.',
    'log.playback.stoppedRewound': 'Durduruldu ve başa sarıldı. Setin tamamı yeniden düzenlenebilir.',
    'log.playback.speed': 'Hız {speed}x yapıldı.',
    'log.playback.mode': 'Zamanlama kipi {mode} yapıldı.',
    'log.capture.cleared': 'Yakalama temizlendi.',
    'transport.maxRateWarning': 'Tam hız, kayıttaki zamanlamayı yok sayar. Bunu senaryo koşturmak için değil, throughput ölçmek için kullanın.',
    'transport.step': 'Adım',
    'transport.stepFromTitle': '{index}. mesajı gönder ve bekle, oradan itibaren adımla. Öncesindekiler gönderilmiş sayılır.',
    'transport.stepTitle': 'Sıradaki mesajı gönder ve bekle. DKM’nin uzun sürdüğü bir mesajı izlemek için: siz bakarken saat ilerlemez.',
    'log.playback.stepped': '{count} mesaj adımlandı; şimdiye kadar {total} gönderildi.',
    'row.sent': 'Gönderildi',
    'row.pending': 'Bekliyor',
    'row.blocked': 'Engelli',
    'row.out': 'DKM’ye gönderildi',
    'row.in': 'DKM’den alındı',
    'trace.hint': 'İki yön, gerçekleşme sırasına göre. Üçüncü sütun bir üstteki satırdan bu yana geçen süre.',

    'inspector.message': 'Mesaj',
    'inspector.new': 'Yeni',
    'inspector.library': 'Kütüphane',
    'inspector.selectHint':
        'Alanlarını görmek için bir mesaj seçin. Henüz gönderilmemiş uyarım mesajları, koşu duraklatıldığında ya da durduğunda düzenlenebilir. Yakalanan çıktı her zaman salt okunurdur.',
    'inspector.fromDkm': 'DKM’den',
    'inspector.apply': 'Uygula',
    'inspector.revert': 'Geri al',
    'inspector.retime': 'Zamanı değiştir',
    'inspector.retimeTitle': 'Bu mesajın zaman çizgisi içindeki gönderim anını değiştir.',
    'inspector.toLibrary': 'Kütüphaneye ekle',
    'inspector.toLibraryTitle': 'Bu mesajı düzenlenmiş haliyle, başka koşularda kullanmak üzere kaydet.',
    'inspector.delete': 'Sil',
    'inspector.wireHeader': 'Mesaj başlığı',
    'inspector.link': '{link} linki',
    'inspector.bytes': '{count} byte',
    'inspector.sentAt': 'gönderildi {time}',
    'inspector.receivedAt': 'alındı {time}',
    'inspector.readOnlyCapture': 'Bu, DKM’nin gönderdiğinin kaydıdır. Düzenlenemez.',
    'inspector.readOnlyUndecodable': 'Bu mesaj mevcut şemaya uymuyor.',
    'inspector.readOnlySent': 'Bu koşuda zaten gönderildi.',
    'inspector.readOnlyRunning': 'Koşu devam ediyor. Bekleyen mesajları düzenlemek için duraklatın.',
    'inspector.updated': '{type} #{id} güncellendi.',
    'inspector.savePrompt': 'Kütüphaneye kaydedilecek ad:',
    'inspector.saved': '“{name}” kütüphaneye kaydedildi.',
    'inspector.retimePrompt': 'Zaman damgası (milisaniye):',

    'new.type': 'Mesaj tipi',
    'new.index': 'Sıra',
    'new.offset': 'Zaman farkı',
    'new.offsetHint':
        'Kendisinden önce gelen mesajdan sonraki milisaniye. Zamanlama, bırakıldığı yerden miras alınmaz; açıkça belirlenir.',
    'new.insert': 'Ekle',
    'new.pauseFirst': 'Gönderilecekleri değiştirmek için koşuyu duraklatın.',
    'new.inserted': '{type}, {index} konumuna eklendi. t = {timestamp} ms.',

    'field.headerOnly': 'Bu mesaj yalnızca başlık taşır.',
    'field.notNamed': '{value}, şemada adlandırılmamış',
    'field.inactiveSlot': '{count} sınırının dışında. Yine gönderilir, ama DKM dikkate almaz.',
    'field.reduceCount': 'Bir eleman çıkar',
    'field.addElement': 'Bir eleman ekle',
    'field.trackId': 'iz numarası',

    'library.search': 'Ada, etikete veya tipe göre ara',
    'library.empty':
        'Henüz kayıt yok. Bir uyarım mesajı seçip “Kütüphaneye ekle” ile koşular arasında yeniden kullanın.',
    'library.insert': 'Ekle',
    'library.delete': 'Sil',
    'library.stale': 'Eski',
    'library.staleTitle': '{version} arayüzüne göre kaydedilmiş',
    'library.staleConfirm':
        '“{name}” {version} arayüzüne göre kaydedilmiş, şu an yüklü olan bu değil. Byte yerleşimi artık doğru olmayabilir.\n\nYine de eklensin mi?',
    'library.offsetPrompt': 'Kendisinden önce gelen mesajdan sonraki milisaniye:',
    'library.deleteConfirm': '“{name}” kütüphaneden silinsin mi?',
    'library.inserted': '“{name}” eklendi. t = {timestamp} ms.',
    'library.insertedStale':
        '“{name}” eklendi. t = {timestamp} ms. Eski bir arayüze göre kaydedilmiş, alanlarını kontrol edin.',
    'library.stored': '{directory} içinde JSON dosyaları olarak saklanır.',
    'library.pauseFirst': 'Koşuya ekleme yapmak için duraklatın.',

    'log.title': 'Oturum kaydı',
    'log.warnings': 'Uyarılar',
    'log.warningsTitle': 'Yalnızca uyarı ve hataları göster.',
    'log.follow': 'Takip',
    'log.empty': 'Henüz kayıt yok.',
    'log.stalls': '{count} bekleme',
    'log.stallsTitle': '{sent} gönderildi, {received} alındı.\n{stalls} yazma beklemesi: DKM geri basınç uyguladı.',
    'log.vizFrames': '{frames} kare',
    'log.vizSkipped': '{count} atlandı',
    'log.vizDropped': '{count} düştü',
    'log.vizThinned': '{count} seyreltildi',
    'log.vizThinnedTitle': 'Uyarım ekran bütçesini aştı. Ayrıntı azaltıldı, veri kaybı olmadı.',

    'viz.title': 'Plan görünümü',
    'viz.fit': 'Sığdır',
    'viz.fitTitle': 'Çizili olan her şeyi ekrana sığdır.',
    'viz.live': 'Canlı',
    'viz.frozen': 'Donduruldu',
    'viz.heldSteppingTitle': 'Adımlarken tutuluyor: DKM ne kadar sürerse sürsün işaretler yerinde kalır. Görüntünün yeniden eskimesi için basın.',
    'viz.freezeTitle':
        'Mevcut görüntüyü incelemek için yeni örnek almayı durdur. Gateway almaya devam eder.',
    'viz.clear': 'Temizle',
    'viz.clearTitle': 'Görüntüyü temizle. Yakalanan mesajlar etkilenmez.',
    'viz.ring': 'Halka {range}',
    'viz.scale': '{value} m/px',
    'viz.convention': 'x = d·cos(h), y = d·sin(h), açı radyan',
    'viz.marks': '{marks} işaret, {tracks} iz',
    'viz.areas': '{gate} kapı, {reporting} raporlama, {rays} ışın',
    'viz.latency': 'Ekrana {ms} ms, kare {frame} ms',
    'viz.droppedUpstream': '{count} örnek düştü',
    'viz.failedTitle': 'Plan görünümü başlatılamadı.',
    'viz.failedHint': 'Mesaj listeleri ve playback kontrolleri çalışmaya devam ediyor.',

    'viz.label.RSP/DetectionReport': 'Tespit',
    'viz.label.RSP/JammerReport': 'Karıştırıcı çizgisi',
    'viz.label.RSM/BeamReport': 'Hüzme',
    'viz.label.RSM/GateAreaMsg': 'Kapı alanı',
    'viz.label.RSM/ReportingAreaMsg': 'Raporlama alanı',
    'viz.label.RSM/MeasurementReport': 'Ölçüm',
    'viz.label.CRM/Prediction': 'İz',

    'viz.filter.title': 'Göster',
    'viz.filter.all': 'tümü',
    'viz.filter.allTitle': 'Bütün mesaj türlerini yeniden çiz.',
    'viz.filter.none': 'hiçbiri',
    'viz.filter.noneTitle': 'Hepsini gizle, sonra yalnızca incelediğini geri aç.',
    'viz.filter.hide': '{type} türünü ekranda gizle. Mesaj akmaya ve sayaçlara girmeye devam eder.',
    'viz.filter.show': '{type} türünü yeniden göster; gizliyken gelenler de görünür.',
    'viz.kind.POINT': 'nokta',
    'viz.kind.TRACK': 'iz',
    'viz.kind.RAY': 'ışın',
    'viz.kind.LINE': 'çizgi',
    'viz.kind.CIRCULAR_AREA': 'dilim',
    'viz.kind.RECT_AREA': 'dikdörtgen',
    'viz.kind.NONE': 'çizilmiyor',

    'tip.stimulus': 'Gönderildi',
    'tip.output': 'Alındı',
    'tip.range': 'Menzil',
    'tip.bearing': 'Açı',
    'tip.position': 'Konum',
    'tip.velocity': 'Hız vektörü',
    'tip.speed': 'Hız',
    'tip.track': 'İz',
    'tip.points': 'Nokta',
    'tip.rangeBand': 'Menzil aralığı',
    'tip.bearingBand': 'Açı aralığı',
    'tip.width': 'Genişlik',
    'tip.height': 'Yükseklik',
    'tip.area': 'Alan',
    'tip.age': 'Yaş',
    'tip.seconds': '{value} sn önce',
    'tip.excludes': 'Bu dilimin içine düşen ölçümler bastırılır.',
    'tip.includes': 'Tanımlı raporlama alanı varsa, ölçüm bunlardan birinin içinde olmalıdır.',
    'tip.trackHint': 'Aynı numarayı taşıyan gözlemler, zaman içindeki tek bir nesnedir.',

    'tour.skip': 'Atla',
    'tour.back': 'Geri',
    'tour.next': 'İleri',
    'tour.done': 'Başla',
    'tour.step': '{current} / {total}',
    'tour.welcome.title': 'DKM bağlandı',
    'tour.welcome.body':
        'Üç link de ayakta, yani bu konsol artık canlı bir DKM’yi sürüyor. Arayüzün düzenini bir dakikadan kısa sürede görelim. İstediğiniz zaman atlayabilir, üstteki ? düğmesinden tekrar açabilirsiniz.',
    'tour.links.title': 'Bağlantı durumu',
    'tour.links.body':
        'RSP, RSM ve CRM linkleri. DKM kendi açılışında bir kez bağlanır ve tekrar denemez, bu yüzden gateway önce dinlemede olmalıdır. Buradaki gri bir rozet, sessiz geçen bir koşuyu ekrandaki her şeyden daha hızlı açıklar.',
    'tour.transport.title': 'Başlat, duraklat, düzenle, devam et',
    'tour.transport.body':
        'Duraklat gönderimi durdurur ama bağlantıları korur. Henüz gönderilmemiş her şey düzenlenebilir hale gelir ve devam edildiğinde zamanlaması yeniden hesaplanır. Hız anında uygulanır, bulunulan an değişmez.',
    'tour.list.title': 'Mesajların üç görünümü',
    'tour.list.body':
        'Uyarım gönderdikleriniz, Yakalama geri gelenler, Zaman çizgisi ise ikisini gerçekleşme sırasına göre birlikte gösterir. Link, tip ve duruma göre süzün; sıralama görünen sayfayı değil tüm seti kapsar.',
    'tour.inspector.title': 'Byte değil, alan',
    'tour.inspector.body':
        'Seçili mesajın her alanı; tipi, birimi ve geçerli aralığıyla birlikte, arayüz şemasından üretilerek. Şemaya eklenen yeni bir mesaj tipi anında çalışan bir editöre kavuşur.',
    'tour.viz.title': 'Canlı görüntü',
    'tour.viz.body':
        'Alanlar, hüzmeler, tespitler ve izler; DKM nasıl hesaplıyorsa öyle. Yakınlaşmak için tekerlek, kaydırmak için sürükleme, değerleri görmek için üzerine gelin.',
    'tour.log.title': 'Ne oldu, ne kadar hızlı',
    'tour.log.body':
        'Bağlantı olayları, playback değişiklikleri ve hatalar; link başına canlı hız bilgisiyle. Ters giden her şey sessizce başarısız olmak yerine burada bildirilir.',
}

const dictionaries: Record<Lang, Partial<Record<TranslationKey, string>>> = { en, tr }

const warned = new Set<string>()

export function translate(
    lang: Lang,
    key: TranslationKey,
    vars?: Record<string, string | number>,
): string {
    let text: string | undefined = dictionaries[lang][key]
    if (text === undefined) {
        text = en[key]
        if (import.meta.env.DEV && lang !== 'en' && !warned.has(`${lang}:${key}`)) {
            warned.add(`${lang}:${key}`)
            console.warn(`[i18n] missing ${lang} translation for "${key}"`)
        }
    }
    if (text === undefined) {
        return key
    }
    if (!vars) {
        return text
    }
    return text.replace(/\{(\w+)\}/g, (match, name) =>
        name in vars ? String(vars[name]) : match)
}

/** True when the key exists in English, so callers can fall back to server-supplied text. */
export function hasTranslation(key: string): key is TranslationKey {
    return key in en
}

/** Best-effort guess from the browser, used only before a choice is stored. */
export function detectLanguage(): Lang {
    const preferred = typeof navigator !== 'undefined'
        ? navigator.languages ?? [navigator.language]
        : []
    for (const tag of preferred) {
        if (tag?.toLowerCase().startsWith('tr')) {
            return 'tr'
        }
    }
    return 'en'
}
