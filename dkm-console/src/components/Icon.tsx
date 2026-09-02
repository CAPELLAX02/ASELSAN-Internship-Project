/**
 * The interface's icon set.
 *
 * <p>Written here rather than installed. Icons are the one place where a
 * dependency looks obviously right and mostly is not: what a set buys is a
 * consistent drawing language, and that is a property of the geometry, not of
 * the package. These are all on one 24-unit grid with one stroke width, one cap
 * style and one join, which is the whole of what makes a set look like a set.
 * Twenty of them cost about two kilobytes and nothing at install time.
 *
 * <p>Stroke rather than fill, and `currentColor` throughout, so an icon takes
 * the colour of the control it sits in and needs no per-theme handling at all.
 */

export type IconName =
    | 'play' | 'pause' | 'stop' | 'step'
    | 'undo' | 'redo'
    | 'zoomIn' | 'zoomOut' | 'fit'
    | 'freeze' | 'live'
    | 'open' | 'saveIn' | 'saveOut'
    | 'trash' | 'revert' | 'filterOff'
    | 'eye' | 'eyeOff'
    | 'search' | 'plus' | 'bookmark' | 'copy' | 'clock'
    | 'grip' | 'check' | 'close' | 'warning' | 'info'
    | 'sun' | 'moon' | 'monitor' | 'help' | 'link'
    | 'chevron' | 'layers' | 'drag' | 'layout'

/** Path data on a 24x24 grid. One entry per icon, stroked not filled. */
const PATHS: Record<IconName, React.ReactNode> = {
    play: <path d="M7 4.5 19 12 7 19.5z" />,
    pause: <><path d="M9 5v14" /><path d="M15 5v14" /></>,
    stop: <rect x="6" y="6" width="12" height="12" />,
    // One notch forward, then a wall: exactly what the control does.
    step: <><path d="M6 5.5 14 12l-8 6.5z" /><path d="M18 5v14" /></>,
    undo: <><path d="M9 8H5V4" /><path d="M5 8a8 8 0 1 1 1.6 8.4" /></>,
    redo: <><path d="M15 8h4V4" /><path d="M19 8a8 8 0 1 0-1.6 8.4" /></>,
    zoomIn: <><circle cx="11" cy="11" r="6.5" /><path d="M16 16l4.5 4.5" /><path d="M8.5 11h5" /><path d="M11 8.5v5" /></>,
    zoomOut: <><circle cx="11" cy="11" r="6.5" /><path d="M16 16l4.5 4.5" /><path d="M8.5 11h5" /></>,
    fit: <><path d="M4 9V4h5" /><path d="M20 9V4h-5" /><path d="M4 15v5h5" /><path d="M20 15v5h-5" /></>,
    freeze: <><path d="M12 3v18" /><path d="M4.2 7.5 19.8 16.5" /><path d="M19.8 7.5 4.2 16.5" /></>,
    live: <><circle cx="12" cy="12" r="3" /><path d="M6.5 6.5a8 8 0 0 0 0 11" /><path d="M17.5 6.5a8 8 0 0 1 0 11" /></>,
    open: <><path d="M4 8V6a1 1 0 0 1 1-1h4l2 2h8a1 1 0 0 1 1 1v2" /><path d="M3.5 10h17l-2 8.5a1 1 0 0 1-1 .8H6.5a1 1 0 0 1-1-.8z" /></>,
    saveIn: <><path d="M12 3v11" /><path d="M8 10.5 12 14.5l4-4" /><path d="M4 17v2.5a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1V17" /></>,
    saveOut: <><path d="M12 14.5v-11" /><path d="M8 7.5 12 3.5l4 4" /><path d="M4 17v2.5a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1V17" /></>,
    trash: <><path d="M4.5 6.5h15" /><path d="M9.5 6.5V4.5h5v2" /><path d="M6.5 6.5 7.5 20a1 1 0 0 0 1 .9h7a1 1 0 0 0 1-.9l1-13.5" /><path d="M10.5 10v7" /><path d="M13.5 10v7" /></>,
    revert: <><path d="M4 5v5h5" /><path d="M4 10a8.5 8.5 0 1 1 1.4 7.6" /></>,
    filterOff: <><path d="M3.5 5h17l-6.5 7.5v6l-4 2.5v-8.5z" /><path d="M17 17l4 4" /><path d="M21 17l-4 4" /></>,
    eye: <><path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z" /><circle cx="12" cy="12" r="2.8" /></>,
    eyeOff: <><path d="M4 4l16 16" /><path d="M9.6 6.1A9.7 9.7 0 0 1 12 5.5c6 0 9.5 6.5 9.5 6.5a17 17 0 0 1-3.3 4" /><path d="M6.3 8.2A16.8 16.8 0 0 0 2.5 12S6 18.5 12 18.5a9.6 9.6 0 0 0 3.4-.6" /><path d="M9.8 9.9a2.8 2.8 0 0 0 4 4" /></>,
    search: <><circle cx="11" cy="11" r="6.5" /><path d="M16 16l4.5 4.5" /></>,
    plus: <><path d="M12 5v14" /><path d="M5 12h14" /></>,
    bookmark: <path d="M6.5 4h11v16l-5.5-4-5.5 4z" />,
    copy: <><rect x="9" y="9" width="11" height="11" /><path d="M15 6H5a1 1 0 0 0-1 1v10" /></>,
    clock: <><circle cx="12" cy="12" r="8.5" /><path d="M12 7v5.3l3.3 2" /></>,
    grip: <><path d="M9.5 5v14" /><path d="M14.5 5v14" /></>,
    check: <path d="M4.5 12.5 9.5 17.5 19.5 6.5" />,
    close: <><path d="M5.5 5.5l13 13" /><path d="M18.5 5.5l-13 13" /></>,
    warning: <><path d="M12 3.5 22 20H2z" /><path d="M12 9.5v4.5" /><path d="M12 17h.01" /></>,
    info: <><circle cx="12" cy="12" r="8.5" /><path d="M12 11v5.5" /><path d="M12 7.6h.01" /></>,
    sun: <><circle cx="12" cy="12" r="4" /><path d="M12 2v2.5" /><path d="M12 19.5V22" /><path d="M2 12h2.5" /><path d="M19.5 12H22" /><path d="M5 5l1.8 1.8" /><path d="M17.2 17.2 19 19" /><path d="M19 5l-1.8 1.8" /><path d="M6.8 17.2 5 19" /></>,
    moon: <path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z" />,
    monitor: <><rect x="3" y="4.5" width="18" height="12" /><path d="M8.5 20.5h7" /><path d="M12 16.5v4" /></>,
    help: <><circle cx="12" cy="12" r="8.5" /><path d="M9.6 9.5a2.5 2.5 0 1 1 3.2 2.4c-.5.2-.8.7-.8 1.2v.6" /><path d="M12 17h.01" /></>,
    // Three panes with their seams: the thing the button restores.
    layout: <><rect x="3.5" y="4.5" width="17" height="15" /><path d="M10 4.5v15" /><path d="M10 14h10.5" /></>,
    chevron: <path d="M9 5.5 15.5 12 9 18.5" />,
    // Stacked planes: what the display is made of, one sheet per message type.
    layers: <><path d="M12 3.5 21 8l-9 4.5L3 8z" /><path d="M3.5 12.5 12 16.8l8.5-4.3" /><path d="M3.5 16.5 12 20.8l8.5-4.3" /></>,
    drag: <><circle cx="9" cy="6" r="1.3" /><circle cx="15" cy="6" r="1.3" /><circle cx="9" cy="12" r="1.3" /><circle cx="15" cy="12" r="1.3" /><circle cx="9" cy="18" r="1.3" /><circle cx="15" cy="18" r="1.3" /></>,
    link: <><path d="M10 14a4 4 0 0 0 5.7 0l2.6-2.6a4 4 0 1 0-5.7-5.7L11.4 7" /><path d="M14 10a4 4 0 0 0-5.7 0l-2.6 2.6a4 4 0 1 0 5.7 5.7L12.6 17" /></>,
}

export function Icon({ name, size = 14, className = '', strokeWidth = 1.9 }: {
    name: IconName
    size?: number
    className?: string
    strokeWidth?: number
}) {
    return (
        <svg
            width={size}
            height={size}
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth={strokeWidth}
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
            focusable="false"
            className={`shrink-0 ${className}`}
        >
            {PATHS[name]}
        </svg>
    )
}
