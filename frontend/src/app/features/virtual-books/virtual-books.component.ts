import {ChangeDetectionStrategy, Component, computed, DestroyRef, ElementRef, inject, OnInit, signal, viewChild} from '@angular/core';
import {HttpErrorResponse} from '@angular/common/http';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {MessageService} from '@openng/optimus-ui/api';
import {AppButtonComponent} from '../../shared/ui/button/app-button.component';
import {AppInputComponent} from '../../shared/ui/input/app-input.component';
import {AppSelectComponent} from '../../shared/ui/select/app-select.component';
import {AppSpinnerComponent} from '../../shared/ui/spinner/app-spinner.component';
import {type SelectOption} from '../../shared/ui/select/app-select.options';
import {AppIconDirective} from '../../shared/components/icon/app-icon.directive';
import {LibraryService} from '../book/service/library.service';
import {VirtualBook, VirtualBookPage, VirtualBookService} from './virtual-book.service';

const PAGE_SIZES = [25, 50, 100] as const;
const SEARCH_DEBOUNCE_MS = 300;
/** Page buttons shown either side of the current page before collapsing into an ellipsis. */
const PAGE_WINDOW = 2;

@Component({
  selector: 'app-virtual-books',
  standalone: true,
  imports: [
    TranslocoDirective,
    AppButtonComponent,
    AppInputComponent,
    AppSelectComponent,
    AppSpinnerComponent,
    AppIconDirective,
  ],
  templateUrl: './virtual-books.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VirtualBooksComponent implements OnInit {

  private readonly virtualBookService = inject(VirtualBookService);
  private readonly libraryService = inject(LibraryService);
  private readonly messageService = inject(MessageService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly search = signal('');
  protected readonly pageIndex = signal(0);
  protected readonly pageSize = signal<number>(PAGE_SIZES[0]);
  protected readonly pageSizeOptions: SelectOption<number>[] = PAGE_SIZES.map(size => ({label: String(size), value: size}));
  protected readonly result = signal<VirtualBookPage | null>(null);

  private readonly tableScroller = viewChild<ElementRef<HTMLElement>>('tableScroller');
  protected readonly loading = signal(false);
  protected readonly loadError = signal(false);

  /** Mirror chosen per virtual book; falls back to the highest-quality mirror. */
  private readonly mirrorChoice = signal<Record<number, number>>({});
  /** Virtual books whose download has been queued in this session. */
  protected readonly queued = signal<ReadonlySet<number>>(new Set());
  protected readonly submitting = signal<number | null>(null);

  protected readonly libraryPathId = signal<number | null>(null);

  protected readonly books = computed(() => this.result()?.content ?? []);
  protected readonly totalPages = computed(() => this.result()?.page.totalPages ?? 0);
  protected readonly totalElements = computed(() => this.result()?.page.totalElements ?? 0);

  /** Page indexes to render as buttons; null marks a gap shown as an ellipsis. Always includes first and last. */
  protected readonly pageButtons = computed<(number | null)[]>(() => {
    const total = this.totalPages();
    const current = this.pageIndex();
    if (total <= 1) return total === 1 ? [0] : [];

    const start = Math.max(1, current - PAGE_WINDOW);
    const end = Math.min(total - 2, current + PAGE_WINDOW);
    const buttons: (number | null)[] = [0];
    if (start > 1) buttons.push(null);
    for (let i = start; i <= end; i++) buttons.push(i);
    if (end < total - 2) buttons.push(null);
    buttons.push(total - 1);
    return buttons;
  });

  protected readonly destinationOptions = computed<SelectOption<number>[]>(() =>
    this.libraryService.libraries().flatMap(library =>
      library.paths
        .filter(path => path.id != null)
        .map(path => ({label: `${library.name} — ${path.path}`, value: path.id!}))
    )
  );

  protected readonly selectedDestination = computed(() =>
    this.libraryPathId() ?? this.destinationOptions()[0]?.value ?? null
  );

  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => {
      if (this.searchTimer) clearTimeout(this.searchTimer);
    });
  }

  ngOnInit(): void {
    this.load();
  }

  protected onSearchChange(value: string): void {
    this.search.set(value);
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.pageIndex.set(0);
      this.load();
    }, SEARCH_DEBOUNCE_MS);
  }

  protected goToPage(index: number): void {
    if (index < 0 || index >= this.totalPages() || index === this.pageIndex()) return;
    this.pageIndex.set(index);
    this.load();
  }

  protected setPageSize(size: number | null): void {
    if (size == null || size === this.pageSize()) return;
    this.pageSize.set(size);
    this.pageIndex.set(0);
    this.load();
  }

  protected mirrorOptions(book: VirtualBook): SelectOption<number>[] {
    return book.mirrors.map(mirror => ({
      label: this.transloco.translate('virtualBooks.mirrorOption', {
        format: mirror.format,
        provider: this.formatProvider(mirror.provider),
        score: mirror.qualityScore,
      }),
      value: mirror.id,
    }));
  }

  protected selectedMirror(book: VirtualBook): number | null {
    return this.mirrorChoice()[book.id] ?? book.mirrors[0]?.id ?? null;
  }

  protected setMirror(book: VirtualBook, mirrorId: number | null): void {
    if (mirrorId == null) return;
    this.mirrorChoice.update(choice => ({...choice, [book.id]: mirrorId}));
  }

  protected canDownload(book: VirtualBook): boolean {
    return !book.downloaded
      && !this.queued().has(book.id)
      && this.selectedMirror(book) != null
      && this.selectedDestination() != null;
  }

  protected download(book: VirtualBook): void {
    const mirrorId = this.selectedMirror(book);
    const libraryPathId = this.selectedDestination();
    if (mirrorId == null || libraryPathId == null) return;

    this.submitting.set(book.id);
    this.virtualBookService.download(book.id, mirrorId, libraryPathId).subscribe({
      next: () => {
        this.submitting.set(null);
        this.queued.update(ids => new Set(ids).add(book.id));
        this.messageService.add({
          severity: 'success',
          summary: this.transloco.translate('virtualBooks.queuedSummary'),
          detail: this.transloco.translate('virtualBooks.queuedDetail', {title: book.title}),
          life: 4000,
        });
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(null);
        this.messageService.add({
          severity: 'error',
          summary: this.transloco.translate('virtualBooks.queueErrorSummary'),
          detail: err.error?.message ?? err.message,
          life: 5000,
        });
      },
    });
  }

  private load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.virtualBookService.list(this.search(), this.pageIndex(), this.pageSize()).subscribe({
      next: page => {
        this.result.set(page);
        this.loading.set(false);
        this.tableScroller()?.nativeElement.scrollTo({top: 0});
      },
      error: () => {
        this.loadError.set(true);
        this.loading.set(false);
      },
    });
  }

  private formatProvider(provider: string): string {
    return provider
      .toLowerCase()
      .split('_')
      .map(word => word.charAt(0).toUpperCase() + word.slice(1))
      .join(' ');
  }
}
