import {inject, Injectable, signal} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable, shareReplay} from 'rxjs';
import {TranslocoService} from '@jsverse/transloco';
import {API_CONFIG} from '../../../../../core/config/api-config';

export interface TranslationLanguage {
  code: string;
  name: string;
}

export interface TranslationResult {
  translatedText: string;
  /** Language the text was translated from. */
  sourceLanguage: string | null;
  /** True when LibreTranslate detected the source; false when the book's metadata language was used. */
  autoDetected: boolean;
}

const TARGET_STORAGE_KEY = 'readerTranslateTarget';

/** Talks to the backend's /api/v1/translate, which proxies the server's LibreTranslate instance. */
@Injectable({providedIn: 'root'})
export class ReaderTranslationService {

  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/translate`;
  private readonly http = inject(HttpClient);
  private readonly transloco = inject(TranslocoService);

  /** False until the server confirms a translation backend is configured; the Translate button stays hidden. */
  readonly enabled = signal(false);
  readonly targetLanguage = signal(this.readStoredTarget());

  private languages$?: Observable<TranslationLanguage[]>;

  constructor() {
    this.http.get<{enabled: boolean}>(`${this.url}/status`).subscribe({
      next: status => this.enabled.set(status.enabled),
      error: () => this.enabled.set(false),
    });
  }

  languages(): Observable<TranslationLanguage[]> {
    this.languages$ ??= this.http.get<TranslationLanguage[]>(`${this.url}/languages`).pipe(shareReplay(1));
    return this.languages$;
  }

  /**
   * @param source the book's metadata language in any common form ("en", "eng", "English", "zh-Hant"); the server
   *               maps it to a supported code, or auto-detects when it is missing, unsupported or equals the target.
   */
  translate(text: string, source: string | null, target: string): Observable<TranslationResult> {
    return this.http.post<TranslationResult>(this.url, {text, source: source ?? 'auto', target});
  }

  setTargetLanguage(code: string): void {
    this.targetLanguage.set(code);
    try {
      localStorage.setItem(TARGET_STORAGE_KEY, code);
    } catch {
      // Storage can be unavailable (private mode); the choice then lasts for this session only.
    }
  }

  private readStoredTarget(): string {
    try {
      const stored = localStorage.getItem(TARGET_STORAGE_KEY);
      if (stored) return stored;
    } catch {
      // fall back to the UI language
    }
    return (this.transloco.getActiveLang() || 'en').split('-')[0];
  }
}
