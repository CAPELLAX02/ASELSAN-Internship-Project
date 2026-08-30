import { useCallback } from 'react'

import { useStore } from '../store/useStore'
import { translate, type TranslationKey } from './index'

export type Translate = (key: TranslationKey, vars?: Record<string, string | number>) => string

/** Re-renders the calling component when the language changes, and nothing else does. */
export function useT(): Translate {
    const lang = useStore((s) => s.lang)
    return useCallback((key, vars) => translate(lang, key, vars), [lang])
}
