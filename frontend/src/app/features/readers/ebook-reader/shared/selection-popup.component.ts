import {Component, EventEmitter, HostListener, inject, Input, Output, signal, WritableSignal} from '@angular/core';
import {HttpErrorResponse} from '@angular/common/http';
import {Subscription} from 'rxjs';
import {NgTemplateOutlet} from '@angular/common';
import {TranslocoDirective} from '@jsverse/transloco';
import {ReaderIconComponent} from './icon.component';
import {ReaderTranslationService, TranslationLanguage} from '../features/translation/translation.service';
import {containsChinese, RubySegment, toRuby} from '../features/translation/pinyin';

export type AnnotationStyle = 'highlight' | 'underline' | 'strikethrough' | 'squiggly';

export interface TextSelectionAction {
  type: 'select' | 'annotate' | 'delete' | 'dismiss' | 'preview' | 'search' | 'note' | 'go-to-link';
  color?: string;
  style?: AnnotationStyle;
  annotationId?: number;
  searchText?: string;
}

@Component({
  selector: 'app-text-selection-popup',
  standalone: true,
  imports: [NgTemplateOutlet, TranslocoDirective, ReaderIconComponent],
  templateUrl: './selection-popup.component.html',
  styleUrls: ['./selection-popup.component.scss']
})
export class TextSelectionPopupComponent {
  @Input() set visible(value: boolean) {
    this._visible = value;
    if (value) {
      this.showAnnotationOptions = false;
      this.hasPreview = false;
    }
    this.resetTranslation();
  }
  get visible(): boolean {
    return this._visible;
  }
  private _visible = false;

  @Input() position = {x: 0, y: 0};
  @Input() showBelow = false;
  @Input() overlappingAnnotationId: number | null = null;
  @Input() selectedText = '';
  @Input() linkUrl?: string;
  /** The book's metadata language, used as the translation source when the server supports it. */
  @Input() sourceLanguage: string | null = null;
  @Output() action = new EventEmitter<TextSelectionAction>();

  // Translation state is in signals: the app is zoneless, so values set in HTTP or async callbacks
  // only re-render the panel when they are signals.
  protected readonly translation = inject(ReaderTranslationService);
  readonly showTranslation = signal(false);
  readonly translating = signal(false);
  readonly translatedText = signal('');
  readonly translationError = signal('');
  readonly translationSource = signal<string | null>(null);
  readonly translationAutoDetected = signal(false);
  readonly translationLanguages = signal<TranslationLanguage[]>([]);
  /** Pinyin for the selection when it contains Chinese, else null. */
  readonly selectionPinyin = signal<RubySegment[] | null>(null);
  /** Pinyin for the translation when it contains Chinese (e.g. translating into Chinese), else null. */
  readonly translationPinyin = signal<RubySegment[] | null>(null);
  private translationRequest?: Subscription;
  // Bumped on every new translation so a slow pinyin lookup from an older selection is ignored.
  private translationRun = 0;

  showAnnotationOptions = false;
  selectedColor = '#FACC15';
  selectedStyle: AnnotationStyle = 'highlight';
  private hasPreview = false;

  highlightColors = [
    {value: '#FACC15', label: 'Yellow'},
    {value: '#4ADE80', label: 'Green'},
    {value: '#38BDF8', label: 'Blue'},
    {value: '#F472B6', label: 'Pink'},
    {value: '#FB923C', label: 'Orange'}
  ];

  lineColors = [
    {value: '#B8860B', label: 'Dark Gold'},
    {value: '#228B22', label: 'Forest Green'},
    {value: '#1E90FF', label: 'Dodger Blue'},
    {value: '#DC143C', label: 'Crimson'},
    {value: '#FF8C00', label: 'Dark Orange'}
  ];

  get colors() {
    return this.selectedStyle === 'highlight' ? this.highlightColors : this.lineColors;
  }

  styles: { value: AnnotationStyle; label: string; icon: string }[] = [
    {value: 'highlight', label: 'Highlight', icon: 'H'},
    {value: 'underline', label: 'Underline', icon: 'U'},
    {value: 'squiggly', label: 'Squiggly', icon: '~'},
    {value: 'strikethrough', label: 'Strikethrough', icon: 'S'}
  ];

  onSelect(): void {
    this.action.emit({type: 'select'});
    this.showAnnotationOptions = false;
    this.hasPreview = false;
  }

  toggleTranslation(): void {
    if (this.showTranslation()) {
      this.resetTranslation();
      return;
    }
    this.showTranslation.set(true);
    this.showAnnotationOptions = false;
    this.translation.languages().subscribe({
      next: languages => this.translationLanguages.set(languages),
      error: () => this.translationLanguages.set([]),
    });
    this.runTranslation();
  }

  onTargetLanguageChange(code: string): void {
    this.translation.setTargetLanguage(code);
    this.runTranslation();
  }

  private runTranslation(): void {
    const text = this.selectedText.trim();
    if (!text) return;

    const run = ++this.translationRun;
    this.translationRequest?.unsubscribe();
    this.translating.set(true);
    this.translationError.set('');
    this.translatedText.set('');
    this.translationPinyin.set(null);
    this.loadPinyin(text, run, this.selectionPinyin);

    this.translationRequest = this.translation.translate(text, this.sourceLanguage, this.translation.targetLanguage()).subscribe({
      next: result => {
        this.translating.set(false);
        this.translatedText.set(result.translatedText);
        this.translationSource.set(result.sourceLanguage);
        this.translationAutoDetected.set(result.autoDetected);
        this.loadPinyin(result.translatedText, run, this.translationPinyin);
      },
      error: (err: HttpErrorResponse) => {
        this.translating.set(false);
        this.translationError.set(err.error?.message ?? err.message);
      },
    });
  }

  /** Fills {@code target} with pinyin when {@code text} contains Chinese; leaves it null otherwise. */
  private loadPinyin(text: string, run: number, target: WritableSignal<RubySegment[] | null>): void {
    target.set(null);
    if (!containsChinese(text)) return;
    toRuby(text)
      .then(segments => {
        if (run === this.translationRun) target.set(segments);
      })
      .catch(() => target.set(null));
  }

  private resetTranslation(): void {
    this.translationRun++;
    this.translationRequest?.unsubscribe();
    this.showTranslation.set(false);
    this.translating.set(false);
    this.translatedText.set('');
    this.translationError.set('');
    this.translationSource.set(null);
    this.selectionPinyin.set(null);
    this.translationPinyin.set(null);
  }

  /** Display name for a language code, from the server's language list when available. */
  languageName(code: string | null): string {
    if (!code) return '';
    return this.translationLanguages().find(language => language.code === code)?.name ?? code;
  }

  toggleAnnotationOptions(): void {
    this.showTranslation.set(false);
    this.showAnnotationOptions = !this.showAnnotationOptions;
    if (this.showAnnotationOptions) {
      this.emitPreview();
    }
  }

  selectColor(color: string): void {
    this.selectedColor = color;
    this.emitPreview();
  }

  selectStyle(style: AnnotationStyle): void {
    const wasHighlight = this.selectedStyle === 'highlight';
    const isHighlight = style === 'highlight';

    this.selectedStyle = style;

    if (wasHighlight !== isHighlight) {
      this.selectedColor = isHighlight ? this.highlightColors[0].value : this.lineColors[0].value;
    }
    this.emitPreview();
  }

  private emitPreview(): void {
    this.hasPreview = true;
    this.action.emit({
      type: 'preview',
      color: this.selectedColor,
      style: this.selectedStyle
    });
  }

  onDelete(): void {
    if (this.overlappingAnnotationId) {
      this.action.emit({type: 'delete', annotationId: this.overlappingAnnotationId});
    }
  }

  onSearch(): void {
    this.action.emit({type: 'search', searchText: this.selectedText});
    this.showAnnotationOptions = false;
    this.hasPreview = false;
  }

  onGoToLink(): void {
    if (this.linkUrl) {
      this.action.emit({type: 'go-to-link'});
    }
  }

  onNote(): void {
    this.action.emit({type: 'note'});
    this.showAnnotationOptions = false;
    this.hasPreview = false;
  }

  @HostListener('document:touchend', ['$event'])
  onDocumentTouchEnd(event: TouchEvent): void {
    if (!window.matchMedia('(pointer: coarse)').matches) {
      return;
    }

    const target = event.target;
    if (target instanceof Element && target.closest('.text-selection-popup, foliate-view')) {
      return;
    }

    this.onDismiss();
  }

  onDismiss(event?: Event): void {
    if (!this.visible) {
      return;
    }

    event?.stopPropagation();
    event?.preventDefault();

    if (this.hasPreview) {
      this.action.emit({
        type: 'annotate',
        color: this.selectedColor,
        style: this.selectedStyle
      });
    } else {
      this.action.emit({type: 'dismiss'});
    }

    this.showAnnotationOptions = false;
    this.hasPreview = false;
  }
}
