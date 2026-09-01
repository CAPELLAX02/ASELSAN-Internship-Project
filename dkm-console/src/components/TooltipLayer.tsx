import { useEffect, useRef, useState } from 'react'

/**
 * Replaces the browser's `title` bubble everywhere, from one place.
 *
 * <p>Three things the native tooltip cannot do that this interface needs. It
 * cannot be styled, so on a warm-neutral panel it arrives as an operating-system
 * rectangle from somewhere else. It waits about a second, which is long when the
 * label being explained is one of forty on screen. And it will not wrap, so the
 * multi-line explanations this tool actually wants to give arrive as one
 * unreadable line.
 *
 * <p>Done as a layer that watches the document rather than a component wrapped
 * around each control, because there are fifty-odd of them and a wrapper would
 * have to be remembered every time someone adds the fifty-first. Here a plain
 * `title` attribute keeps working and gets the treatment for free.
 *
 * <p>The attribute is moved to `data-tip` on first hover -- leaving it in place
 * would show both bubbles -- and mirrored to `aria-description` so assistive
 * technology keeps the hint that `title` used to give it, without overwriting
 * the control's own name.
 */
export function TooltipLayer() {
    const [tip, setTip] = useState<{ text: string; rect: DOMRect } | null>(null)
    const [at, setAt] = useState({ left: -9999, top: -9999 })
    const bubble = useRef<HTMLDivElement | null>(null)
    const timer = useRef<number | undefined>(undefined)

    useEffect(() => {
        const hostOf = (target: EventTarget | null): HTMLElement | null => {
            let node = target instanceof Element ? target : null
            while (node) {
                if (node instanceof HTMLElement && (node.title || node.dataset.tip)) {
                    return node
                }
                node = node.parentElement
            }
            return null
        }

        const enter = (event: Event) => {
            const host = hostOf(event.target)
            if (!host) return
            if (host.title) {
                host.dataset.tip = host.title
                host.setAttribute('aria-description', host.title)
                host.removeAttribute('title')
            }
            const text = host.dataset.tip
            if (!text) return
            window.clearTimeout(timer.current)
            timer.current = window.setTimeout(
                () => setTip({ text, rect: host.getBoundingClientRect() }), 250)
        }

        const leave = () => {
            window.clearTimeout(timer.current)
            setTip(null)
            setAt({ left: -9999, top: -9999 })
        }

        document.addEventListener('mouseover', enter, true)
        document.addEventListener('mouseout', leave, true)
        document.addEventListener('focusin', enter, true)
        document.addEventListener('focusout', leave, true)
        // A tooltip that outlives the thing it explained is worse than none.
        window.addEventListener('scroll', leave, true)
        document.addEventListener('mousedown', leave, true)
        document.addEventListener('keydown', leave, true)
        return () => {
            window.clearTimeout(timer.current)
            document.removeEventListener('mouseover', enter, true)
            document.removeEventListener('mouseout', leave, true)
            document.removeEventListener('focusin', enter, true)
            document.removeEventListener('focusout', leave, true)
            window.removeEventListener('scroll', leave, true)
            document.removeEventListener('mousedown', leave, true)
            document.removeEventListener('keydown', leave, true)
        }
    }, [])

    // Placed after it renders, because where it fits depends on how tall the
    // text turned out once it wrapped.
    useEffect(() => {
        if (!tip || !bubble.current) return
        const box = bubble.current.getBoundingClientRect()
        const margin = 8
        let left = tip.rect.left + tip.rect.width / 2 - box.width / 2
        left = Math.max(margin, Math.min(left, window.innerWidth - box.width - margin))
        let top = tip.rect.bottom + 6
        if (top + box.height > window.innerHeight - margin) {
            top = tip.rect.top - box.height - 6
        }
        setAt({ left, top: Math.max(margin, top) })
    }, [tip])

    if (!tip) return null

    return (
        <div
            ref={bubble}
            role="tooltip"
            style={{ left: at.left, top: at.top }}
            className="fixed z-[60] max-w-xs pointer-events-none border border-ink-600
                       bg-ink-850 text-ink-200 px-2 py-1.5 text-mini leading-snug
                       shadow-xl whitespace-pre-line toast-in"
        >
            {tip.text}
        </div>
    )
}
